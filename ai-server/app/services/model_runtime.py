"""설치된 로컬 Ollama 모델의 시험과 요청 단위 선택을 안전하게 관리한다."""

from __future__ import annotations

import asyncio
import fcntl
import hashlib
import json
import os
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

import httpx

from app.config import Settings

NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,199}\Z")
DIGEST = re.compile(r"(?:sha256:)?[a-fA-F0-9]{64}\Z")
PROBE_SCHEMA = {"type": "object", "properties": {"message": {"type": "string", "const": "모델 응답 확인"}},
                "required": ["message"], "additionalProperties": False}


class ModelRuntimeError(Exception):
    """요청 원문이나 내부 연결 정보를 노출하지 않는 관리 오류다."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


@dataclass(frozen=True)
class Selection:
    model: str
    digest: str | None
    revision: int
    previous_model: str | None = None
    previous_digest: str | None = None


def normalized_digest(value):
    if not isinstance(value, str) or not DIGEST.fullmatch(value):
        raise ModelRuntimeError(409, "MODEL_DIGEST_INVALID", "모델 파일 식별값을 확인하지 못했습니다. 설치 목록을 새로 불러오세요.")
    return value.removeprefix("sha256:").lower()


def checked_name(value):
    if not isinstance(value, str) or not NAME.fullmatch(value) or "://" in value or ".." in value:
        raise ModelRuntimeError(400, "MODEL_NAME_INVALID", "설치 목록에서 올바른 모델을 선택하세요.")
    return value


class RuntimeStore:
    """선택값·시험 증거·멱등 결과를 같은 SQLite 트랜잭션에 보관한다."""

    def __init__(self, path: Path, default_model: str):
        self.path, self.default_model = Path(path), default_model

    @contextmanager
    def connect(self):
        connection = None
        try:
            self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            if self.path.is_symlink() or not self.path.parent.is_dir():
                raise OSError()
            descriptor = os.open(self.path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            os.close(descriptor)
            connection = sqlite3.connect(self.path, timeout=1)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA synchronous=FULL")
            connection.executescript("""
                CREATE TABLE IF NOT EXISTS selection (
                    id INTEGER PRIMARY KEY CHECK(id=1), model TEXT NOT NULL, digest TEXT,
                    revision INTEGER NOT NULL, previous_model TEXT, previous_digest TEXT);
                CREATE TABLE IF NOT EXISTS probes (
                    token_hash TEXT PRIMARY KEY, model TEXT NOT NULL, digest TEXT NOT NULL,
                    revision INTEGER NOT NULL, expires REAL NOT NULL, consumed INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS operations (
                    id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, result TEXT NOT NULL);
            """)
            connection.execute("INSERT OR IGNORE INTO selection(id,model,revision) VALUES(1,?,0)", (self.default_model,))
            connection.commit()
            yield connection
            connection.commit()
        except ModelRuntimeError:
            if connection is not None:
                connection.rollback()
            raise
        except (OSError, sqlite3.Error, ValueError, TypeError):
            if connection is not None:
                connection.rollback()
            raise ModelRuntimeError(503, "MODEL_STATE_UNAVAILABLE", "모델 선택 기록을 읽거나 저장하지 못했습니다. 기존 설정을 임의로 바꾸지 않았습니다.") from None
        finally:
            if connection is not None:
                connection.close()

    def selection(self):
        with self.connect() as connection:
            row = connection.execute("SELECT * FROM selection WHERE id=1").fetchone()
            if (not row or type(row["revision"]) is not int or row["revision"] < 0
                    or not NAME.fullmatch(row["model"]) or "://" in row["model"] or ".." in row["model"]
                    or (row["digest"] is not None and not DIGEST.fullmatch(row["digest"]))
                    or (row["previous_model"] is not None and not NAME.fullmatch(row["previous_model"]))
                    or (row["previous_digest"] is not None and not DIGEST.fullmatch(row["previous_digest"]))):
                raise ModelRuntimeError(503, "MODEL_STATE_UNAVAILABLE", "저장된 모델 선택 기록의 형식을 확인하지 못했습니다.")
            return Selection(row["model"], row["digest"], row["revision"], row["previous_model"], row["previous_digest"])

    @contextmanager
    def transition_lock(self):
        """다른 프로세스의 동시 전환은 기다리며 덮어쓰지 않고 거절한다."""
        self.selection()
        descriptor = None
        acquired = False
        try:
            descriptor = os.open(str(self.path) + ".lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                acquired = True
            except BlockingIOError:
                raise ModelRuntimeError(409, "MODEL_TRANSITION_BUSY", "다른 모델 전환이 처리 중입니다. 상태를 새로 확인하세요.") from None
            yield
        except OSError:
            raise ModelRuntimeError(503, "MODEL_STATE_UNAVAILABLE", "모델 전환 잠금을 확보하지 못했습니다.") from None
        finally:
            if descriptor is not None:
                if acquired:
                    fcntl.flock(descriptor, fcntl.LOCK_UN)
                os.close(descriptor)

    def operation(self, operation_id):
        with self.connect() as connection:
            row = connection.execute("SELECT fingerprint,result FROM operations WHERE id=?", (str(operation_id),)).fetchone()
            return (row["fingerprint"], json.loads(row["result"])) if row else None


class OllamaAdminClient:
    """서버 설정 주소의 고정 API만 사용하고 응답 크기를 제한한다."""

    def __init__(self, settings):
        self.base = str(settings.ollama_url).rstrip("/")
        self.timeout = settings.ai_model_probe_timeout_seconds

    async def request(self, method, path, body=None):
        async with httpx.AsyncClient(timeout=self.timeout, follow_redirects=False) as client:
            async with client.stream(method, self.base + path, json=body) as response:
                response.raise_for_status()
                content = bytearray()
                async for chunk in response.aiter_bytes():
                    content.extend(chunk)
                    if len(content) > 2 * 1024 * 1024:
                        raise ModelRuntimeError(502, "MODEL_RESPONSE_INVALID", "모델 서버의 관리 응답이 허용 크기를 넘었습니다.")
                return json.loads(content)

    async def list(self):
        return await self.request("GET", "/api/tags")

    async def show(self, model):
        return await self.request("POST", "/api/show", {"model": model})

    async def chat(self, **body):
        return await self.request("POST", "/api/chat", body)


class ModelRuntimeService:
    """시험 통과 증거가 있는 로컬 모델만 새 분석 요청의 선택값으로 바꾼다."""

    def __init__(self, settings: Settings, client=None, store=None, clock=time.time):
        self.settings, self.clock = settings, clock
        path = settings.ai_model_state_file or str(Path(__file__).resolve().parents[2] / ".runtime" / "model-selection.sqlite3")
        self.store = store or RuntimeStore(Path(path), settings.ai_model)
        from app.services.mlx_adapter_runtime import MlxAdapterRuntime
        from app.services.hybrid_model_client import HybridModelClient
        from app.services.trained_model_runtime import TrainedModelRuntime
        self.adapters = MlxAdapterRuntime(settings)
        self.trained = TrainedModelRuntime(settings)
        self.client = client or HybridModelClient(OllamaAdminClient(settings), self.adapters)

    def selection(self):
        return self.store.selection()

    async def validate_selection(self, selection):
        """이전에 확인한 모델의 파일이 외부에서 바뀌면 새 분석을 차단한다."""
        if selection.digest is not None:
            await self._candidate(selection.model, selection.digest)

    async def _call(self, awaitable, probe=False):
        try:
            return await asyncio.wait_for(awaitable, self.settings.ai_model_probe_timeout_seconds if probe else self.settings.ai_model_admin_timeout_seconds)
        except (asyncio.TimeoutError, httpx.TimeoutException):
            raise ModelRuntimeError(504, "MODEL_TIMEOUT", "모델 서버 응답 시간이 초과됐습니다. 선택값은 변경하지 않았습니다.") from None
        except ModelRuntimeError:
            raise
        except (httpx.HTTPError, OSError, ConnectionError):
            raise ModelRuntimeError(503, "MODEL_UNAVAILABLE", "모델 서버에 연결하지 못했습니다. 설치 상태와 연결을 확인하세요.") from None
        except (ValueError, TypeError, KeyError, AttributeError):
            raise ModelRuntimeError(502, "MODEL_RESPONSE_INVALID", "모델 서버 응답 형식을 확인하지 못했습니다.") from None

    async def _listing(self):
        value = await self._call(self.client.list())
        if not isinstance(value, dict) or not isinstance(value.get("models"), list) or len(value["models"]) > 500:
            raise ModelRuntimeError(502, "MODEL_RESPONSE_INVALID", "모델 설치 목록의 형식이나 개수를 확인하세요.")
        models = []
        for item in value["models"]:
            if not isinstance(item, dict):
                continue
            try:
                name, digest = checked_name(item.get("name") or item.get("model")), normalized_digest(item.get("digest"))
            except ModelRuntimeError:
                continue
            size = item.get("size")
            models.append({"name": name, "digest": digest, "size": size if type(size) is int and size >= 0 else None})
        return models

    async def _candidate(self, model, digest, listing=None):
        model, digest = checked_name(model), normalized_digest(digest)
        entries = await self._listing() if listing is None else listing
        entry = next((item for item in entries if item["name"] == model), None)
        if not entry or entry["digest"] != digest:
            raise ModelRuntimeError(409, "MODEL_DIGEST_CHANGED", "선택 모델이 없거나 파일 식별값이 바뀌었습니다. 설치 목록을 새로 불러오세요.")
        show = await self._call(self.client.show(model))
        if not isinstance(show, dict):
            raise ModelRuntimeError(502, "MODEL_RESPONSE_INVALID", "모델 실행 기능을 확인하지 못했습니다.")
        capabilities = show.get("capabilities")
        if (show.get("remote_model") or show.get("remote_host") or ":cloud" in model.lower()
                or not isinstance(capabilities, list) or "completion" not in capabilities):
            raise ModelRuntimeError(409, "MODEL_NOT_EXECUTABLE", "로컬에서 문장 응답을 생성할 수 있는 설치 모델만 사용할 수 있습니다.")
        metadata = {}
        if self.trained.owns(model):
            from app.services.trained_model_runtime import TrainedModelError
            try:
                metadata = self.trained.metadata_for(model, digest)
            except TrainedModelError as error:
                raise ModelRuntimeError(error.status, error.code, error.message) from None
        if self.adapters.owns(model):
            source = show.get("sourceModel")
            if not isinstance(source, str) or not NAME.fullmatch(source):
                raise ModelRuntimeError(502, "MODEL_RESPONSE_INVALID", "어댑터의 기반 모델 출처를 확인하지 못했습니다.")
            metadata = {"provider": "mlx", "sourceModel": source, "qualityEvaluated": False}
            try:
                metadata["adapterId"] = str(UUID(show["adapterId"]))
            except (ValueError, TypeError, KeyError):
                raise ModelRuntimeError(502, "MODEL_RESPONSE_INVALID", "어댑터 학습 작업을 확인하지 못했습니다.") from None
        return {**entry, **metadata, "capabilities": [item for item in capabilities if isinstance(item, str) and re.fullmatch(r"[a-z_-]{1,40}", item)]}

    @staticmethod
    def _matching(entries, name):
        return next((item for item in entries if item["name"] == name or item["name"].removesuffix(":latest") == name.removesuffix(":latest")), None)

    async def installed(self):
        selection = self.selection()
        entries = await self._listing()
        semaphore = asyncio.Semaphore(4)

        async def describe(entry):
            async with semaphore:
                try:
                    return {**await self._candidate(entry["name"], entry["digest"], entries), "selectable": True, "reason": "설치된 로컬 응답 모델입니다. 전환 전 시험 응답 확인이 필요합니다."}
                except ModelRuntimeError as error:
                    return {**entry, "capabilities": [], "selectable": False, "reason": error.message}

        models = await self._call(asyncio.gather(*(describe(item) for item in entries[:100])))
        current = self._matching(entries, selection.model)
        selected = next((item for item in models if item["name"] == selection.model), {})
        metadata = {key: selected[key] for key in ("provider", "sourceModel", "adapterId", "trainingJobId", "trainedModel", "qualityEvaluated") if key in selected}
        return {**metadata, "model": selection.model, "currentDigest": selection.digest or (current["digest"] if current else None),
                "currentRevision": selection.revision, "previousModel": selection.previous_model,
                "canRollback": bool(selection.previous_model and selection.previous_digest),
                "checkedAt": datetime.now(timezone.utc).isoformat(), "models": models, "truncated": len(entries) > 100,
                "message": "설치 정보이며 응답 품질이나 운영 승인을 뜻하지 않습니다. 기존 분석은 시작할 때의 모델을 유지합니다."}

    async def probe(self, model, digest):
        selection = self.selection()
        candidate = await self._candidate(model, digest)
        result = await self._call(self.client.chat(model=candidate["name"], messages=[
            {"role": "system", "content": "짧은 한국어 JSON 응답을 검사합니다. 다른 내용 없이 {\"message\":\"모델 응답 확인\"}만 출력하세요."},
            {"role": "user", "content": "한국어로 응답 확인 문구를 반환하세요."}], stream=False, format=PROBE_SCHEMA,
            think=False, options={"temperature": 0, "num_predict": 128}, keep_alive="5m"), probe=True)
        try:
            if (not isinstance(result, dict) or result.get("done") is not True or result.get("done_reason") == "length"
                    or result.get("model", candidate["name"]).removesuffix(":latest") != candidate["name"].removesuffix(":latest")):
                raise ValueError()
            content = result["message"]["content"]
            if not isinstance(content, str) or len(content) > 1000 or json.loads(content) != {"message": "모델 응답 확인"}:
                raise ValueError()
        except (ValueError, TypeError, KeyError, AttributeError):
            raise ModelRuntimeError(502, "MODEL_PROBE_FAILED", "모델의 짧은 한국어 JSON 응답 검사를 통과하지 못했습니다.") from None
        await self._candidate(model, digest)
        token = secrets.token_urlsafe(32)
        expires = self.clock() + self.settings.ai_model_probe_ttl_seconds
        with self.store.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            revision = connection.execute("SELECT revision FROM selection WHERE id=1").fetchone()[0]
            if revision != selection.revision:
                raise ModelRuntimeError(409, "MODEL_REVISION_CHANGED", "시험 중 선택 모델이 바뀌었습니다. 목록을 새로 확인한 뒤 다시 시험하세요.")
            connection.execute("DELETE FROM probes WHERE expires < ? OR consumed=1", (self.clock(),))
            connection.execute("INSERT INTO probes(token_hash,model,digest,revision,expires) VALUES(?,?,?,?,?)",
                               (hashlib.sha256(token.encode()).hexdigest(), candidate["name"], candidate["digest"], revision, expires))
        return {"model": candidate["name"], "digest": candidate["digest"], "status": "PASSED", "probeToken": token,
                "expiresAt": datetime.fromtimestamp(expires, timezone.utc).isoformat(), "currentRevision": selection.revision,
                "message": "짧은 한국어 JSON 응답을 확인했습니다. 실제 업무 정확도나 운영 배포 승인을 보증하지 않습니다."}

    def operation(self, operation_id):
        found = self.store.operation(operation_id)
        if not found:
            raise ModelRuntimeError(404, "MODEL_OPERATION_NOT_FOUND", "해당 모델 전환 처리 기록을 찾을 수 없습니다.")
        return found[1]

    async def activate(self, operation_id, model, digest, probe_token, expected_revision):
        return await self._transition(operation_id, "ACTIVATE", expected_revision, model, digest, probe_token)

    async def rollback(self, operation_id, expected_revision):
        return await self._transition(operation_id, "ROLLBACK", expected_revision)

    async def _transition(self, operation_id, kind, expected_revision, model=None, digest=None, probe_token=None):
        operation_id = str(UUID(str(operation_id)))
        fingerprint = hashlib.sha256(json.dumps([kind, expected_revision, model, digest, probe_token], separators=(",", ":")).encode()).hexdigest()
        with self.store.transition_lock():
            existing = self.store.operation(operation_id)
            if existing:
                if existing[0] != fingerprint:
                    raise ModelRuntimeError(409, "MODEL_OPERATION_CONFLICT", "같은 처리 식별자를 다른 전환 요청에 사용할 수 없습니다.")
                if existing[1]["status"] == "FAILED":
                    raise ModelRuntimeError(existing[1]["httpStatus"], existing[1]["code"], existing[1]["message"])
                return existing[1]
            before = self.selection()
            try:
                if type(expected_revision) is not int or expected_revision != before.revision:
                    raise ModelRuntimeError(409, "MODEL_REVISION_CHANGED", "다른 작업으로 선택 모델이 바뀌었습니다. 목록을 새로 불러오세요.")
                token_hash = hashlib.sha256((probe_token or "").encode()).hexdigest()
                if kind == "ROLLBACK":
                    model, digest = before.previous_model, before.previous_digest
                    if not model or not digest:
                        raise ModelRuntimeError(409, "MODEL_ROLLBACK_UNAVAILABLE", "복구할 직전 모델 기록이 없습니다.")
                else:
                    with self.store.connect() as connection:
                        proof = connection.execute("SELECT * FROM probes WHERE token_hash=?", (token_hash,)).fetchone()
                    if (not proof or proof["consumed"] or proof["expires"] <= self.clock()
                            or proof["model"] != model or proof["digest"] != normalized_digest(digest) or proof["revision"] != before.revision):
                        raise ModelRuntimeError(409, "MODEL_PROBE_REQUIRED", "유효한 시험 응답 확인이 필요합니다. 선택 모델을 다시 시험하세요.")
                entries = await self._listing()
                target = await self._candidate(model, digest, entries)
                confirmed = await self._listing()
                if not any(item["name"] == target["name"] and item["digest"] == target["digest"] for item in confirmed):
                    raise ModelRuntimeError(409, "MODEL_DIGEST_CHANGED", "전환 확인 중 모델 파일이 바뀌었습니다. 다시 시험하세요.")
                current = self._matching(entries, before.model)
                previous_digest = before.digest or (current["digest"] if current else None)
                if target["name"] == before.model and target["digest"] == previous_digest:
                    raise ModelRuntimeError(409, "MODEL_ALREADY_SELECTED", "이미 선택된 모델입니다. 현재 상태를 새로 확인하세요.")
                metadata = {key: target[key] for key in ("provider", "sourceModel", "adapterId", "trainingJobId", "trainedModel", "qualityEvaluated") if key in target}
                result = {**metadata, "operationId": operation_id, "status": "APPLIED" if kind == "ACTIVATE" else "ROLLED_BACK",
                          "previousModel": before.model, "model": target["name"], "digest": target["digest"], "revision": before.revision + 1,
                          "message": "새 분석 요청에 사용할 모델을 변경했습니다. 이미 진행 중인 분석은 기존 모델을 유지합니다."}
                with self.store.connect() as connection:
                    connection.execute("BEGIN IMMEDIATE")
                    updated = connection.execute("UPDATE selection SET model=?,digest=?,revision=?,previous_model=?,previous_digest=? WHERE id=1 AND revision=?",
                                                 (target["name"], target["digest"], result["revision"], before.model if kind == "ACTIVATE" else None,
                                                  previous_digest if kind == "ACTIVATE" else None, expected_revision))
                    if updated.rowcount != 1:
                        raise ModelRuntimeError(409, "MODEL_REVISION_CHANGED", "선택 모델이 바뀌어 전환하지 않았습니다. 상태를 다시 확인하세요.")
                    if kind == "ACTIVATE":
                        connection.execute("UPDATE probes SET consumed=1 WHERE token_hash=?", (token_hash,))
                    connection.execute("INSERT INTO operations VALUES(?,?,?)", (operation_id, fingerprint, json.dumps(result, ensure_ascii=False)))
                return result
            except ModelRuntimeError as error:
                result = {"operationId": operation_id, "status": "FAILED", "previousModel": before.model,
                          "model": before.model, "digest": before.digest, "revision": before.revision,
                          "code": error.code, "httpStatus": error.status, "message": error.message}
                with self.store.connect() as connection:
                    connection.execute("INSERT OR IGNORE INTO operations VALUES(?,?,?)", (operation_id, fingerprint, json.dumps(result, ensure_ascii=False)))
                raise
