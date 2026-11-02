"""분석 근거 JSON을 검색 단위로 나눠 저장하고, 질문과 관련된 조각만 골라 모델 입력을 줄인다.

원본 JSON 구조와 백엔드 계약은 바꾸지 않는다. 임베딩 모델이 없으면 어휘 검색(BM25)만 사용한다.
"""

import asyncio
import copy
import hashlib
import json
import logging
import math
import re
import sqlite3
from collections import Counter
from pathlib import Path

logger = logging.getLogger(__name__)

# 종류별로 모델에 전달할 최대 조각 수. 전체가 이보다 적으면 그 종류는 줄이지 않는다.
BUDGETS = {"component": 40, "tableRef": 60, "link": 40, "route": 20, "unresolved": 10, "dbTable": 12}
EMBED_BATCH = 64
_TOKEN = re.compile(r"[A-Za-z0-9]+|[가-힣]+")
_CAMEL = re.compile(r"[A-Z]+(?=[A-Z][a-z])|[A-Z]?[a-z]+|[A-Z]+|[0-9]+")


def tokenize(text):
    """식별자는 camelCase·snake_case 단위로, 한글은 낱말과 두 글자 조각으로 나눈다."""
    tokens = []
    for word in _TOKEN.findall(text or ""):
        if "가" <= word[0] <= "힣":
            tokens.append(word)
            tokens.extend(word[i:i + 2] for i in range(len(word) - 1))
        else:
            tokens.append(word.lower())
            parts = [part.lower() for part in _CAMEL.findall(word)]
            if len(parts) > 1:
                tokens.extend(parts)
    return [token for token in tokens if len(token) > 1]


def query_text(requirement):
    """검색 질의는 요구사항 제목·내용·인수 조건·사람 답변·최근 추가 질문으로 만든다."""
    if not isinstance(requirement, dict):
        return ""
    values = [requirement.get(key) for key in ("title", "refinedContent", "acceptanceCriteria", "latestFollowUpQuestion")]
    for item in requirement.get("clarifications", []) or []:
        if isinstance(item, dict):
            values.extend([item.get("question"), item.get("answer")])
    return "\n".join(value for value in values if isinstance(value, str))


def _table_name(table):
    return f"{table.get('schema')}.{table.get('name')}"


def build_chunks(payload):
    """분석 요청 JSON을 (종류, 검색 문장, 원본 항목) 조각 목록으로 나눈다."""
    source = payload.get("sourceSummary", {})
    graph = source.get("sourceConnections")
    graph = graph if isinstance(graph, dict) else {}
    chunks = []

    def add(kind, text, item):
        chunks.append({"kind": kind, "text": text, "item": item})

    for item in source.get("components", []):
        if isinstance(item, dict):
            add("component", f"{item.get('path', '')} {item.get('type', '')} {item.get('language', '')}", item)
    for item in payload.get("relations", []):
        if isinstance(item, dict):
            add("tableRef", f"{item.get('file', '')} {item.get('schema', '')}.{item.get('table', '')} {item.get('kind', '')}", item)
    for item in graph.get("links", []):
        if isinstance(item, dict):
            add("link", f"{item.get('file', '')} {item.get('symbol', '')} {item.get('targetFile', '')} {item.get('kind', '')}", item)
    for item in graph.get("routes", []):
        if isinstance(item, dict):
            add("route", f"{item.get('file', '')} {item.get('route', '')} {item.get('kind', '')}", item)
    for item in graph.get("unresolvedReferences", []):
        if isinstance(item, dict):
            add("unresolved", f"{item.get('file', '')} {item.get('symbol', '')} {item.get('reason', '')}", item)
    for table in payload.get("databaseSummary", {}).get("tables", []):
        if isinstance(table, dict):
            columns = [column.get("name", "") for column in table.get("columns", []) or [] if isinstance(column, dict)]
            comments = [column.get("comment", "") for column in table.get("columns", []) or [] if isinstance(column, dict)]
            targets = [key.get("referencedTable", "") for key in table.get("foreignKeys", []) or [] if isinstance(key, dict)]
            add("dbTable", " ".join([_table_name(table), str(table.get("comment") or ""), *columns, *map(str, comments), *targets]), table)
    return chunks


def evidence_key(payload):
    """질문이 아닌 수집 근거만으로 키를 만들어 같은 근거는 한 번만 색인한다."""
    source = {key: value for key, value in payload.get("sourceSummary", {}).items()
              if key not in {"changeRequirement", "promptCoverage", "analysisScope"}}
    database = {key: value for key, value in payload.get("databaseSummary", {}).items() if key != "capturedAt"}
    raw = json.dumps({"s": source, "d": database, "r": payload.get("relations", [])},
                     ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class EvidenceStore:
    """SQLite에 프로젝트별 검색 조각과 임베딩을 보관한다. 최신 근거 하나만 유지한다."""

    def __init__(self, path):
        self.path = Path(path)

    def _connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=10)
        connection.execute("create table if not exists chunks (project_id text, evidence_key text, seq integer, "
                           "kind text, text text, item text, embedding text, model text, primary key (project_id, evidence_key, seq))")
        return connection

    def has(self, project_id, key):
        with self._connect() as db:
            return db.execute("select 1 from chunks where project_id=? and evidence_key=? limit 1", (project_id, key)).fetchone() is not None

    def save(self, project_id, key, chunks, embeddings, model):
        with self._connect() as db:
            db.execute("delete from chunks where project_id=?", (project_id,))
            db.executemany("insert into chunks values (?,?,?,?,?,?,?,?)", [
                (project_id, key, seq, chunk["kind"], chunk["text"], json.dumps(chunk["item"], ensure_ascii=False),
                 json.dumps(embeddings[seq]) if embeddings else None, model if embeddings else None)
                for seq, chunk in enumerate(chunks)])

    def load(self, project_id, key):
        with self._connect() as db:
            rows = db.execute("select seq, kind, text, item, embedding, model from chunks where project_id=? and evidence_key=? "
                              "order by seq", (project_id, key)).fetchall()
        return [{"seq": seq, "kind": kind, "text": text, "item": json.loads(item),
                 "embedding": json.loads(embedding) if embedding else None, "model": model}
                for seq, kind, text, item, embedding, model in rows]


def _bm25(query_tokens, chunks):
    """질의 낱말이 드문 조각일수록 높은 점수를 준다."""
    documents = [Counter(tokenize(chunk["text"])) for chunk in chunks]
    average = (sum(sum(document.values()) for document in documents) / len(documents)) or 1
    frequency = Counter(token for document in documents for token in document)
    scores = []
    for document in documents:
        length = sum(document.values())
        score = 0.0
        for token in set(query_tokens):
            if token in document:
                idf = math.log(1 + (len(documents) - frequency[token] + 0.5) / (frequency[token] + 0.5))
                score += idf * document[token] * 2.5 / (document[token] + 1.5 * (0.25 + 0.75 * length / average))
        scores.append(score)
    return scores


def _cosine(left, right):
    dot = sum(a * b for a, b in zip(left, right))
    norm = math.sqrt(sum(a * a for a in left)) * math.sqrt(sum(b * b for b in right))
    return max(0.0, dot / norm) if norm else 0.0


class EvidenceRetriever:
    """색인(분할·임베딩·저장)과 질문별 검색을 담당한다. 임베딩 실패는 어휘 검색으로 대체한다."""

    def __init__(self, store, embed=None, embedding_model=""):
        self.store, self.embed, self.embedding_model = store, embed, embedding_model

    async def _embeddings(self, texts):
        if not self.embed or not self.embedding_model or not texts:
            return None
        try:
            vectors = []
            for start in range(0, len(texts), EMBED_BATCH):
                response = await self.embed(model=self.embedding_model, input=texts[start:start + EMBED_BATCH])
                batch = response.get("embeddings") if isinstance(response, dict) else getattr(response, "embeddings", None)
                vectors.extend(list(vector) for vector in batch)
            return vectors if len(vectors) == len(texts) else None
        except Exception:
            logger.warning("evidence_embedding_skipped model=%s", self.embedding_model)
            return None

    async def index(self, payload):
        """근거가 처음이면 조각으로 나눠 저장한다. 같은 근거는 다시 만들지 않는다."""
        project_id, key = str(payload["projectId"]), evidence_key(payload)
        if await asyncio.to_thread(self.store.has, project_id, key):
            return key
        chunks = build_chunks(payload)
        embeddings = await self._embeddings([chunk["text"] for chunk in chunks])
        await asyncio.to_thread(self.store.save, project_id, key, chunks, embeddings, self.embedding_model)
        logger.info("evidence_indexed chunks=%s embedded=%s", len(chunks), embeddings is not None)
        return key

    async def retrieve(self, payload):
        """요구사항과 관련된 조각만 남긴 새 요청 JSON을 반환한다. 요구사항이 없거나 작으면 그대로 반환한다."""
        requirement = payload.get("sourceSummary", {}).get("changeRequirement")
        query = query_text(requirement)
        if not query.strip():
            return payload
        key = await self.index(payload)
        chunks = await asyncio.to_thread(self.store.load, str(payload["projectId"]), key)
        if not any(sum(1 for c in chunks if c["kind"] == kind) > limit for kind, limit in BUDGETS.items()):
            return payload

        scores = _bm25(tokenize(query), chunks)
        top = max(scores) or 1
        scores = [score / top for score in scores]
        if self.embedding_model and all(chunk["embedding"] and chunk["model"] == self.embedding_model for chunk in chunks):
            query_vector = await self._embeddings([query])
            if query_vector:
                scores = [(lexical + _cosine(query_vector[0], chunk["embedding"])) / 2 for lexical, chunk in zip(scores, chunks)]

        selected = set()
        for kind, limit in BUDGETS.items():
            ranked = [index for index, chunk in enumerate(chunks) if chunk["kind"] == kind]
            if len(ranked) <= limit:
                selected.update(ranked)
                continue
            ranked.sort(key=lambda index: (-scores[index], index))
            hits = [index for index in ranked if scores[index] > 0]
            # 질문과 맞는 조각이 없으면 기존처럼 앞쪽 항목을 전달해 근거가 비지 않게 한다.
            selected.update((hits or sorted(ranked))[:limit])
        return self._reduce(payload, chunks, selected)

    @staticmethod
    def _reduce(payload, chunks, selected):
        """선택한 조각과 그 조각이 가리키는 파일·테이블(외래키 한 단계)을 함께 남긴다."""
        picked = [chunk for index, chunk in enumerate(chunks) if index in selected]
        files = {chunk["item"].get(field) for chunk in picked if chunk["kind"] != "dbTable"
                 for field in ("file", "targetFile", "path")}
        tables = {_table_name(chunk["item"]) for chunk in picked if chunk["kind"] == "dbTable"}
        tables |= {f"{chunk['item'].get('schema')}.{chunk['item'].get('table')}" for chunk in picked if chunk["kind"] == "tableRef"}
        all_tables = {_table_name(chunk["item"]): chunk["item"] for chunk in chunks if chunk["kind"] == "dbTable"}
        seeds = set(tables)
        for name, table in all_tables.items():
            for foreign in table.get("foreignKeys", []) or []:
                target = f"{foreign.get('referencedSchema') or table.get('schema')}.{foreign.get('referencedTable')}" if isinstance(foreign, dict) else None
                if (name in seeds or target in seeds) and target in all_tables:
                    tables.update({name, target})
        keep = {"component": lambda item: item.get("path") in files,
                "tableRef": lambda item: f"{item.get('schema')}.{item.get('table')}" in tables,
                "dbTable": lambda item: _table_name(item) in tables}
        kept = {kind: [] for kind in BUDGETS}
        for index, chunk in enumerate(chunks):
            if index in selected or keep.get(chunk["kind"], lambda item: False)(chunk["item"]):
                kept[chunk["kind"]].append(chunk["item"])

        reduced = copy.deepcopy(payload)
        source = reduced["sourceSummary"]
        source["components"] = kept["component"]
        reduced["relations"] = kept["tableRef"]
        reduced["databaseSummary"]["tables"] = kept["dbTable"]
        if isinstance(source.get("sourceConnections"), dict):
            source["sourceConnections"].update(links=kept["link"], routes=kept["route"], unresolvedReferences=kept["unresolved"])
        totals = Counter(chunk["kind"] for chunk in chunks)
        coverage = source.setdefault("promptCoverage", {})
        coverage["retrieval"] = {"mode": "RAG", "selected": {kind: len(kept[kind]) for kind in BUDGETS},
                                 "total": {kind: totals[kind] for kind in BUDGETS},
                                 "notice": "요구사항과 관련된 근거만 검색해 전달했습니다. 전달하지 않은 항목이 없다는 뜻이 아닙니다."}
        coverage["truncated"] = True
        return reduced
