"""완료된 검증 결과만 서명해 보관한다. 원본 프롬프트와 접속 정보는 저장하지 않는다."""

import hashlib
import hmac
import json
import os
import re
import tempfile
import time
from pathlib import Path

KEY_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
MAX_FILE_BYTES = 1_000_000


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


class StageCheckpoints:
    """요청·모델·스키마가 같은 결과만 재사용하며 저장 실패가 분석 자체를 실패시키지는 않는다."""

    def __init__(self, directory, secret, max_age=86400):
        self.directory = Path(directory)
        self.secret = secret.encode("utf-8")
        self.max_age = max_age

    @staticmethod
    def key(value):
        return hashlib.sha256(canonical(value)).hexdigest()

    def _path(self, key):
        if not KEY_PATTERN.fullmatch(key):
            raise ValueError("중간 결과 식별자가 올바르지 않습니다.")
        if self.directory.is_symlink():
            raise ValueError("중간 결과 폴더에 연결 경로를 사용할 수 없습니다.")
        path = self.directory / f"{key}.json"
        if path.is_symlink():
            raise ValueError("중간 결과 파일에 연결 경로를 사용할 수 없습니다.")
        return path

    def read(self, key):
        try:
            path = self._path(key)
            if not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
                return None
            record = json.loads(path.read_bytes())
            value, signature = record["value"], record["signature"]
            expected = hmac.new(self.secret, canonical(value), hashlib.sha256).hexdigest()
            if not isinstance(signature, str) or not hmac.compare_digest(expected, signature):
                return None
            age = time.time() - value["createdAt"]
            if value["key"] != key or age < 0 or age > self.max_age or not isinstance(value["result"], dict):
                return None
            return value["result"]
        except (OSError, ValueError, TypeError, KeyError):
            return None

    def write(self, key, result):
        temporary = None
        try:
            path = self._path(key)
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            info = self.directory.stat()
            if info.st_uid != os.getuid() or info.st_mode & 0o077:
                return False
            if not path.exists() and sum(1 for _ in self.directory.glob("*.json")) >= 1000:
                return False
            value = {"key": key, "createdAt": time.time(), "result": result}
            encoded = canonical({"value": value, "signature": hmac.new(self.secret, canonical(value), hashlib.sha256).hexdigest()})
            if len(encoded) > MAX_FILE_BYTES:
                return False
            descriptor, name = tempfile.mkstemp(prefix=".stage-", dir=self.directory)
            temporary = Path(name)
            with os.fdopen(descriptor, "wb") as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
            return True
        except (OSError, ValueError, TypeError):
            return False
        finally:
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass
