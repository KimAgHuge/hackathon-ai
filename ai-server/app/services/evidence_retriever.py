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
# 검색 문장이나 범위 계산이 바뀌면 이전 색인을 다시 만든다.
INDEX_VERSION = 1
# 화면에서 RAG를 고른(ALWAYS) 경우에만 쓰는 기준. 종류별 최고 점수의 이 비율 미만인 조각은 관련 없다고 본다.
MIN_RELATIVE_SCORE = 0.4
# 임베딩 유사도가 이 값 미만이면 관련 없는 조각으로 보고 점수에 반영하지 않는다. 모델에 따라 조정한다.
EMBED_FLOOR = 0.4
NO_EVIDENCE_NOTICE = "요구사항과 관련된 근거를 찾지 못해 근거를 전달하지 않았습니다. 관련 근거 없음은 영향이 없다는 뜻이 아닙니다."
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

    def text_values(value):
        if isinstance(value, str):
            if value.strip():
                yield value
        elif isinstance(value, list):
            for item in value:
                yield from text_values(item)
        elif isinstance(value, dict):
            for item in value.values():
                yield from text_values(item)

    values = [requirement.get(key) for key in ("title", "refinedContent", "acceptanceCriteria", "latestFollowUpQuestion")]
    for item in requirement.get("clarifications", []) or []:
        if isinstance(item, dict):
            # 자동 생성된 질문의 추측은 질의로 쓰지 않고 사람이 남긴 답변만 반영한다.
            values.append(item.get("answer"))
            if item.get("kind") == "USER_QUESTION":
                values.append(item.get("question"))
    return "\n".join(dict.fromkeys(text for value in values for text in text_values(value)))

def selection_mode(requirement):
    """화면에서 고른 근거 전달 방식. 알 수 없는 값은 기존 동작인 AUTO로 본다."""
    value = requirement.get("evidenceSelection") if isinstance(requirement, dict) else None
    return value if value in {"ALWAYS", "OFF"} else "AUTO"


def _without_option(payload):
    """모델 입력에 설정값이 섞이지 않게 evidenceSelection만 뺀다. 값이 없으면 같은 객체를 돌려준다."""
    requirement = payload.get("sourceSummary", {}).get("changeRequirement")
    if not isinstance(requirement, dict) or "evidenceSelection" not in requirement:
        return payload
    cleaned = copy.deepcopy(payload)
    cleaned["sourceSummary"]["changeRequirement"].pop("evidenceSelection", None)
    return cleaned


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
    raw = json.dumps({"version": INDEX_VERSION, "s": source, "d": database, "r": payload.get("relations", [])},
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

    def has_embeddings(self, project_id, key, model):
        """저장된 조각이 모두 해당 임베딩 모델로 만들어졌는지 확인한다."""
        with self._connect() as db:
            missing = db.execute("select count(*) from chunks where project_id=? and evidence_key=? "
                                 "and (embedding is null or model is not ?)", (project_id, key, model)).fetchone()[0]
        return missing == 0

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


def _valid_vectors(vectors, count):
    """개수·차원·숫자 형식이 같은 벡터만 색인이나 검색에 재사용한다."""
    if not isinstance(vectors, list) or len(vectors) != count or not vectors:
        return False
    dimensions = len(vectors[0]) if isinstance(vectors[0], list) else 0
    try:
        return dimensions > 0 and all(isinstance(vector, list) and len(vector) == dimensions
            and all(type(value) in {int, float} and math.isfinite(value) for value in vector)
            for vector in vectors)
    except (TypeError, OverflowError):
        return False


def _strict_selection(chunks, selected):
    """검색한 파일의 연결 한 단계와 해당 테이블의 외래키 이웃만 보충한다."""
    result = set(selected)
    seed_files = {chunk["item"].get(field) for index, chunk in enumerate(chunks) if index in selected
                  and chunk["kind"] != "dbTable" for field in ("file", "targetFile", "path")}
    seed_files.discard(None)
    files = set(seed_files)
    for index, chunk in enumerate(chunks):
        item = chunk["item"]
        if chunk["kind"] == "link" and seed_files.intersection({item.get("file"), item.get("targetFile")}):
            result.add(index)
            files.update(value for value in (item.get("file"), item.get("targetFile")) if value)
    for index, chunk in enumerate(chunks):
        item = chunk["item"]
        if (chunk["kind"] == "component" and item.get("path") in files
                or chunk["kind"] in {"tableRef", "route", "unresolved"} and item.get("file") in files):
            result.add(index)
    tables = {_table_name(chunk["item"]): chunk["item"] for chunk in chunks if chunk["kind"] == "dbTable"}
    seeds = {_table_name(chunk["item"]) for index, chunk in enumerate(chunks)
             if index in result and chunk["kind"] == "dbTable"}
    seeds.update(f"{chunk['item'].get('schema')}.{chunk['item'].get('table')}" for index, chunk in enumerate(chunks)
                 if index in result and chunk["kind"] == "tableRef")
    included = set(seeds)
    for name, table in tables.items():
        for foreign in table.get("foreignKeys", []) or []:
            if not isinstance(foreign, dict):
                continue
            schema, target_name = foreign.get("referencedSchema"), foreign.get("referencedTable")
            if schema:
                candidates = [f"{schema}.{target_name}"]
            else:
                candidates = [target for target, value in tables.items() if value.get("name") == target_name]
            if len(candidates) == 1 and candidates[0] in tables:
                target = candidates[0]
                if name in seeds or target in seeds:
                    included.update({name, target})
    result.update(index for index, chunk in enumerate(chunks) if chunk["kind"] == "dbTable" and _table_name(chunk["item"]) in included)
    return result


def _clear_derived_context(payload, kept):
    """이전 파생 문맥과 생략 이름 목록으로 선택 범위가 다시 넓어지는 일을 막는다."""
    derived = {"sourceContext", "databaseReviewScope", "databaseFacts", "reconciliationFacts", "generalAnalysis", "databaseImpact",
               "supportedTargetTables", "tableCatalog", "observedConstraints", "retrieval"}
    for owner in (payload, payload["sourceSummary"], payload["databaseSummary"]):
        for key in derived:
            owner.pop(key, None)
    source = payload["sourceSummary"]
    for key in ("promptCoverage", "scanMetrics"):
        if isinstance(source.get(key), dict):
            source[key] = {name: value for name, value in source[key].items() if not isinstance(value, (dict, list))}
    graph = source.get("sourceConnections")
    if isinstance(graph, dict):
        source["sourceConnections"] = {name: value for name, value in graph.items()
                                       if not isinstance(value, (dict, list)) or name in {"inspectedLanguages", "unsupportedLanguages"}}
    files = {chunk.get("path") for chunk in kept["component"]}
    for kind in ("tableRef", "link", "route", "unresolved"):
        files.update(chunk.get("file") for chunk in kept[kind])
    if isinstance(source.get("frameworks"), list):
        for framework in source["frameworks"]:
            if not isinstance(framework, dict) or not isinstance(framework.get("evidences"), list):
                continue
            evidences = framework["evidences"]
            framework["collectedEvidenceCount"] = max(len(evidences), framework.get("collectedEvidenceCount", 0))
            framework["evidences"] = [item for item in evidences if isinstance(item, dict) and item.get("file", item.get("path")) in files]
            framework["includedEvidenceCount"] = len(framework["evidences"])


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
            return vectors if _valid_vectors(vectors, len(texts)) else None
        except Exception:
            logger.warning("evidence_embedding_skipped model=%s", self.embedding_model)
            return None

    async def index(self, payload):
        """근거와 사용 가능한 임베딩을 확인하고 누락·실패·모델 변경 때 다시 색인한다."""
        project_id, key = str(payload["projectId"]), evidence_key(payload)
        cached = []
        if await asyncio.to_thread(self.store.has, project_id, key):
            if not self.embedding_model or not self.embed:
                return key
            cached = await asyncio.to_thread(self.store.load, project_id, key)
            if (cached and all(chunk.get("model") == self.embedding_model for chunk in cached)
                    and _valid_vectors([chunk.get("embedding") for chunk in cached], len(cached))):
                return key
        chunks = build_chunks(payload)
        embeddings = await self._embeddings([chunk["text"] for chunk in chunks])
        # 복구 시도가 실패해도 기존 어휘 색인과 다른 모델의 성공한 벡터는 지우지 않는다.
        if cached and embeddings is None:
            return key
        await asyncio.to_thread(self.store.save, project_id, key, chunks, embeddings, self.embedding_model)
        logger.info("evidence_indexed chunks=%s embedded=%s", len(chunks), embeddings is not None)
        return key

    async def retrieve(self, payload):
        """선택한 전달 방식을 지키며 관련 근거를 검색한다. 빈 검색 결과를 전체 원본으로 채우지 않는다."""
        requirement = payload.get("sourceSummary", {}).get("changeRequirement")
        mode = selection_mode(requirement)
        query = query_text(requirement)
        strict = mode == "ALWAYS"
        if mode == "OFF":
            return _without_option(payload)
        if not query.strip():
            return self._reduce(payload, build_chunks(payload), set(), strict=True, empty_reason="EMPTY_QUERY") if strict else _without_option(payload)
        key = await self.index(payload)
        chunks = await asyncio.to_thread(self.store.load, str(payload["projectId"]), key)
        if not chunks:
            return self._reduce(payload, build_chunks(payload), set(), strict=True, empty_reason="EMPTY_INDEX") if strict else _without_option(payload)
        over_budget = any(sum(1 for chunk in chunks if chunk["kind"] == kind) > limit for kind, limit in BUDGETS.items())
        if not strict and not over_budget:
            return _without_option(payload)
        scores = _bm25(tokenize(query), chunks)
        top = max(scores) or 1
        scores = [score / top for score in scores]
        if (self.embedding_model and all(chunk.get("model") == self.embedding_model for chunk in chunks)
                and _valid_vectors([chunk.get("embedding") for chunk in chunks], len(chunks))):
            query_vector = await self._embeddings([query])
            if query_vector and len(query_vector[0]) == len(chunks[0]["embedding"]):
                similarities = [_cosine(query_vector[0], chunk["embedding"]) for chunk in chunks]
                if strict:
                    similarities = [value if value >= EMBED_FLOOR else 0.0 for value in similarities]
                scores = [(lexical + similarity) / 2 for lexical, similarity in zip(scores, similarities)]
        selected = set()
        for kind, limit in BUDGETS.items():
            ranked = [index for index, chunk in enumerate(chunks) if chunk["kind"] == kind]
            if len(ranked) <= limit and not strict:
                selected.update(ranked)
                continue
            ranked.sort(key=lambda index: (-scores[index], index))
            hits = [index for index in ranked if scores[index] > 0]
            if strict:
                cutoff = scores[hits[0]] * MIN_RELATIVE_SCORE if hits else 0
                selected.update([index for index in hits if scores[index] >= cutoff][:limit])
            else:
                selected.update((hits or sorted(ranked))[:limit])
        reduced = self._reduce(payload, chunks, selected, strict, empty_reason="NO_MATCHES" if strict and not selected else None)
        retrieval = reduced["sourceSummary"]["promptCoverage"]["retrieval"]
        logger.info("evidence_retrieved mode=%s selected=%s total=%s", mode, retrieval["selected"], retrieval["total"])
        return reduced

    @staticmethod
    def _reduce(payload, chunks, selected, strict=None, *, empty_reason=None):
        """선택한 조각과 그 조각이 가리키는 파일·테이블(외래키 한 단계)을 함께 남긴다."""
        strict = selection_mode(payload.get("sourceSummary", {}).get("changeRequirement")) == "ALWAYS" if strict is None else strict
        direct_count = len(selected)
        if strict:
            selected = _strict_selection(chunks, selected)
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
            if index in selected or (not strict and keep.get(chunk["kind"], lambda item: False)(chunk["item"])):
                kept[chunk["kind"]].append(chunk["item"])

        reduced = _without_option(copy.deepcopy(payload))
        source = reduced["sourceSummary"]
        if strict:
            _clear_derived_context(reduced, kept)
        source["components"] = copy.deepcopy(kept["component"])
        reduced["relations"] = copy.deepcopy(kept["tableRef"])
        totals = Counter(chunk["kind"] for chunk in chunks)
        omissions = {kind: totals[kind] - len(kept[kind]) for kind in BUDGETS}
        database = reduced["databaseSummary"]
        from app.services.database_scope import database_coverage
        original_coverage = database_coverage(database)
        database.update(original_coverage)
        original_db_truncated = original_coverage["truncated"]
        kept_tables = {_table_name(table) for table in kept["dbTable"]}
        database["tables"] = copy.deepcopy(kept["dbTable"])
        database["retrieval"] = {"mode": "RAG", "inputTableCount": totals["dbTable"],
                                 "includedTableCount": len(kept["dbTable"]),
                                 "omittedTables": [_table_name(chunk["item"]) for chunk in chunks
                                                   if chunk["kind"] == "dbTable" and _table_name(chunk["item"]) not in kept_tables],
                                 "originalTruncated": original_db_truncated}
        if original_coverage.get("coverageUnknown") is True:
            database["retrieval"]["originalCoverageUnknown"] = True
        if strict:
            database["retrieval"].pop("omittedTables")
            database["retrieval"].update(selectionMode="ALWAYS", omittedTableCount=omissions["dbTable"])
        database["truncated"] = True if original_db_truncated is True or omissions["dbTable"] > 0 else original_db_truncated
        if isinstance(source.get("sourceConnections"), dict):
            graph = source["sourceConnections"]
            graph["truncated"] = bool(graph.get("truncated", False)) or any(omissions[kind] > 0 for kind in ("link", "route", "unresolved"))
            graph.update(links=copy.deepcopy(kept["link"]), routes=copy.deepcopy(kept["route"]), unresolvedReferences=copy.deepcopy(kept["unresolved"]),
                         includedLinks=len(kept["link"]), includedRoutes=len(kept["route"]),
                         includedUnresolvedReferences=len(kept["unresolved"]))
        coverage = source.setdefault("promptCoverage", {})
        original_truncated = bool(coverage.get("truncated", False))
        coverage["retrieval"] = {"mode": "RAG", "selected": {kind: len(kept[kind]) for kind in BUDGETS},
                                 "selectionMode": "ALWAYS" if strict else "AUTO",
                                 "total": {kind: totals[kind] for kind in BUDGETS},
                                 "omitted": omissions, "originalTruncated": original_truncated,
                                 "notice": "요구사항과 관련된 근거만 검색해 전달했습니다. 전달하지 않은 항목이 없다는 뜻이 아닙니다."}
        if strict:
            retrieval = coverage["retrieval"]
            retrieval.update(userSelected=True, directMatchCount=direct_count)
            if empty_reason:
                retrieval["emptyReason"] = empty_reason
            if not any(kept.values()):
                retrieval.update(noRelevantEvidence=True, notice=NO_EVIDENCE_NOTICE)
        coverage.update(includedComponents=len(kept["component"]), includedRelations=len(kept["tableRef"]),
                        includedTables=len(kept["dbTable"]))
        if isinstance(source.get("sourceConnections"), dict):
            coverage["sourceConnectionsTruncated"] = bool(coverage.get("sourceConnectionsTruncated", False)) or source["sourceConnections"]["truncated"]
        coverage["truncated"] = original_truncated or any(omissions.values())
        return reduced
