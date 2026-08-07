import re

import pytest

from app.security.internal_api_key import (
    ENV_NAME,
    format_internal_api_key,
    generate_internal_api_key,
)


def test_generate_internal_api_key_is_long_and_url_safe():
    api_key = generate_internal_api_key()

    assert len(api_key) >= 64
    assert re.fullmatch(r"doenaai_[A-Za-z0-9_-]+", api_key)


def test_generate_internal_api_key_returns_different_values():
    assert generate_internal_api_key() != generate_internal_api_key()


def test_generate_internal_api_key_rejects_low_entropy():
    with pytest.raises(ValueError, match="at least 32"):
        generate_internal_api_key(entropy_bytes=31)


def test_format_internal_api_key_for_env_file():
    assert format_internal_api_key("secret", "env") == f"{ENV_NAME}=secret"


def test_format_internal_api_key_for_all_environments_uses_same_key():
    output = format_internal_api_key("same-secret")

    assert f"{ENV_NAME}=same-secret" in output
    assert f"export {ENV_NAME}='same-secret'" in output
    assert f"$env:{ENV_NAME}='same-secret'" in output
    assert output.count("same-secret") == 3


def test_format_internal_api_key_for_shell():
    assert (
        format_internal_api_key("secret", "shell")
        == f"export {ENV_NAME}='secret'"
    )


def test_format_internal_api_key_for_powershell():
    assert (
        format_internal_api_key("secret", "powershell")
        == f"$env:{ENV_NAME}='secret'"
    )


def test_format_internal_api_key_as_raw_value():
    assert format_internal_api_key("secret", "raw") == "secret"
