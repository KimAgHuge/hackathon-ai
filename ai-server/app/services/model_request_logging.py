import json
import re

from app.models.analysis import FORBIDDEN_SECRET_KEYS


_REDACTED = "[REDACTED]"
_KEY_NORMALIZATION = re.compile(r"[^a-z0-9]")
_SECRET_LABEL = (
    r"access[_. -]*token|api[_. -]*key|db[_. -]*password|private[_. -]*key|"
    r"authorization|ciphertext|credential|password|passphrase|secret|token"
)
_ASSIGNED_SECRET = re.compile(
    rf"(?<![A-Za-z0-9_])(?P<key>[\"']?(?:{_SECRET_LABEL})[\"']?)"
    r"(?P<separator>[ \t]*[:=][ \t]*)"
    r"(?P<value>\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|"
    r"(?:Bearer|Basic)[ \t]+[^\s,;&\"'<>]+|[A-Za-z0-9][^\s,;&\"'<>]*)",
    re.IGNORECASE,
)
_AUTH_VALUE = re.compile(r"\b(?P<kind>Bearer|Basic)[ \t]+[A-Za-z0-9._~+/-]+=*", re.IGNORECASE)
_URL_USERINFO = re.compile(r"\b(?P<scheme>[a-z][a-z0-9+.-]*://)[^\s/@]+@", re.IGNORECASE)
_PRIVATE_KEY = re.compile(
    r"-----BEGIN (?P<kind>(?:[A-Z0-9]+ )?PRIVATE KEY)-----.*?-----END (?P=kind)-----",
    re.DOTALL,
)
_PREFIXED_TOKEN = re.compile(
    r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
    r"sk-(?:proj-)?[A-Za-z0-9_-]{20,})\b"
)


def request_log_json(request: dict, secret_values=()) -> str:
    """모델 요청 원본을 건드리지 않고 민감값을 가린 JSON 복사본을 만든다."""
    secrets = tuple(sorted(
        {value for value in secret_values if isinstance(value, str) and value},
        key=len,
        reverse=True,
    ))

    def redact_text(value: str) -> str:
        # JSON 본문은 값 단위로 살피고, 바뀐 값이 없으면 공백과 줄바꿈까지 보존한다.
        if value.lstrip().startswith(("{", "[")):
            try:
                parsed = json.loads(value)
            except (ValueError, TypeError):
                pass
            else:
                sanitized = copy_value(parsed)
                if sanitized == parsed:
                    return value
                return json.dumps(sanitized, ensure_ascii=False)

        sanitized = _PRIVATE_KEY.sub(_REDACTED, value)
        sanitized = _URL_USERINFO.sub(lambda match: match["scheme"] + _REDACTED + "@", sanitized)
        sanitized = _ASSIGNED_SECRET.sub(_redact_assignment, sanitized)
        sanitized = _AUTH_VALUE.sub(lambda match: match["kind"] + " " + _REDACTED, sanitized)
        sanitized = _PREFIXED_TOKEN.sub(_REDACTED, sanitized)
        for secret in secrets:
            sanitized = sanitized.replace(secret, _REDACTED)
        return sanitized

    def copy_value(value):
        if isinstance(value, dict):
            # 분석 입력의 민감 필드 검사와 같은 키 정규화 기준을 사용한다.
            return {
                key: _REDACTED if _KEY_NORMALIZATION.sub("", str(key).lower()) in FORBIDDEN_SECRET_KEYS
                else copy_value(nested)
                for key, nested in value.items()
            }
        if isinstance(value, (list, tuple)):
            return [copy_value(nested) for nested in value]
        if isinstance(value, str):
            return redact_text(value)
        return value

    return json.dumps(copy_value(request), ensure_ascii=False, indent=2)


def _redact_assignment(match: re.Match) -> str:
    value = match["value"]
    replacement = value[0] + _REDACTED + value[0] if value[0] in "\"'" else _REDACTED
    return match["key"] + match["separator"] + replacement
