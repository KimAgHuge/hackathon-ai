"""추가 패키지나 셸 실행 없이 수신 서버의 두 설정만 .env에서 읽는다."""

import os
import re
import shlex
from pathlib import Path

RECEIVER_ENV_KEYS = {"DOENAAI_TRAINING_SHARED_DIR", "DOENAAI_TRAINING_TRANSFER_KEY"}


def _without_comment(value):
    """따옴표 안이나 값 중간의 #은 보존하고 공백 뒤의 주석만 제거한다."""
    quote, escaped = None, False
    for index, char in enumerate(value):
        if escaped:
            escaped = False
            continue
        if char == "\\" and quote != "'":
            escaped = True
        elif quote is not None:
            if char == quote:
                quote = None
        elif char in {"'", '"'}:
            quote = char
        elif char == "#" and (index == 0 or value[index - 1].isspace()):
            return value[:index]
    return value


def load_receiver_env(path, *, environ=None, required=False):
    """기존 환경변수를 우선하며 값·비밀키를 오류 메시지에 포함하지 않는다."""
    environ = os.environ if environ is None else environ
    path = Path(path)
    try:
        if not path.exists() and not required:
            return False
        if not path.is_file() or path.stat().st_size > 65536:
            raise ValueError()
        lines = path.read_text(encoding="utf-8-sig").splitlines()
        pending = {}
        for line in lines:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            match = re.fullmatch(r"(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)", stripped)
            if not match:
                raise ValueError()
            key, raw = match.groups()
            if key not in RECEIVER_ENV_KEYS or key in environ:
                continue
            values = shlex.split(_without_comment(raw), comments=False, posix=True)
            if len(values) > 1:
                raise ValueError()
            pending[key] = values[0] if values else ""
        environ.update(pending)
        return True
    except (OSError, UnicodeError, ValueError):
        raise ValueError(".env 파일을 읽지 못했습니다. 파일 존재·UTF-8 형식과 따옴표를 확인하고 공백이 있는 값은 따옴표로 감싸 주세요.") from None
