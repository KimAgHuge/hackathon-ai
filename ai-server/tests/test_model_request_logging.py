import copy
import json

import pytest

from app.models.analysis import FORBIDDEN_SECRET_KEYS
from app.services.model_request_logging import request_log_json


def test_plain_request_keeps_the_actual_payload_and_uses_readable_korean_json():
    content = '{  "설명": "목표 완료일을 추가해 주세요.", "columns": [{"name": "password"}] }\n'
    request = {
        "model": "qwen3:27b",
        "messages": [{"role": "user", "content": content}],
        "stream": False,
        "think": "medium",
        "options": {"temperature": 0, "num_predict": 4096},
        "format": {"type": "object", "required": ["summary"]},
    }

    rendered = request_log_json(request)

    assert rendered == json.dumps(request, ensure_ascii=False, indent=2)
    assert json.loads(rendered) == request
    assert "목표 완료일" in rendered
    assert "\n  \"model\"" in rendered
    assert json.loads(rendered)["messages"][0]["content"] == content


@pytest.mark.parametrize("key", sorted(FORBIDDEN_SECRET_KEYS) + ["API_Key", "Db-Password", "ACCESS.TOKEN", "Private Key"])
def test_sensitive_keys_are_normalized_and_masked_at_any_depth(key):
    request = {"messages": [{"content": {"nested": [{key: "fake-structured-secret", "name": "token"}]}}]}

    rendered = request_log_json(request)

    assert "fake-structured-secret" not in rendered
    nested = json.loads(rendered)["messages"][0]["content"]["nested"][0]
    assert nested == {key: "[REDACTED]", "name": "token"}


def test_json_message_content_masks_only_sensitive_values_and_preserves_the_original():
    nested = {"api_key": "fake-json-key", "columns": [{"name": "password", "type": "varchar"}],
              "설명": "token 컬럼과 password 컬럼을 검토하세요.", "details": {"AUTHORIZATION": "fake-auth"}}
    original_content = json.dumps(nested, ensure_ascii=False, indent=4)
    request = {"messages": [{"role": "user", "content": original_content}]}
    original_request = copy.deepcopy(request)

    rendered = request_log_json(request)
    logged_content = json.loads(json.loads(rendered)["messages"][0]["content"])

    assert logged_content["api_key"] == "[REDACTED]"
    assert logged_content["details"]["AUTHORIZATION"] == "[REDACTED]"
    assert logged_content["columns"] == nested["columns"]
    assert logged_content["설명"] == nested["설명"]
    assert request == original_request


@pytest.mark.parametrize("content,secret", [
    ("Authorization: Bearer fake-bearer-value", "fake-bearer-value"),
    ("Bearer fake-standalone-value", "fake-standalone-value"),
    ("authorization=Basic ZmFrZTpzZWNyZXQ=", "ZmFrZTpzZWNyZXQ="),
    ("password=fake-password-value", "fake-password-value"),
    ("token: 'fake quoted value'", "fake quoted value"),
    ('api_key = "fake quoted key"', "fake quoted key"),
    ('본문: {"password": "fake-malformed-value",}', "fake-malformed-value"),
    ("https://fake-user:fake-url-password@host.example/path", "fake-url-password"),
    ("postgresql://fake-user:fake-db-password@db.example/app", "fake-db-password"),
    ("https://host.example/path?token=fake-query-value&limit=10", "fake-query-value"),
    ("-----BEGIN RSA PRIVATE KEY-----\nfake-private-key-body\n-----END RSA PRIVATE KEY-----", "fake-private-key-body"),
    ("ghp_" + "x" * 30, "ghp_" + "x" * 30),
    ("sk-proj-" + "z" * 30, "sk-proj-" + "z" * 30),
])
def test_known_secret_forms_are_masked_without_touching_the_input(content, secret):
    request = {"messages": [{"content": content}]}
    original_request = copy.deepcopy(request)

    rendered = request_log_json(request)

    assert secret not in rendered
    assert "[REDACTED]" in rendered
    assert request == original_request


def test_registered_secret_values_are_hidden_in_plain_and_embedded_json_messages():
    secret = "fake-registered-secret"
    request = {"messages": [
        {"content": "주소와 함께 쓰는 값 " + secret},
        {"content": json.dumps({"text": "가려야 할 값 " + secret, "count": 3})},
    ]}
    original_request = copy.deepcopy(request)

    rendered = request_log_json(request, secret_values=(secret, "", None, secret[:10]))

    assert secret not in rendered
    assert json.loads(json.loads(rendered)["messages"][1]["content"])["count"] == 3
    assert request == original_request


def test_plain_column_names_and_instruction_words_are_not_hidden():
    content = "password, token, api_key, authorization 필드는 모델에 보내지 마세요."
    request = {"messages": [{"content": content}], "columns": [
        {"name": "password"}, {"name": "token"}, {"name": "api_key"}, {"name": "authorization"},
    ], "properties": {"tableName": {"type": "string"}}}

    assert json.loads(request_log_json(request)) == request


def test_nested_json_string_is_sanitized_without_mutating_other_fields():
    inner = json.dumps({"token": "fake-inner-token", "name": "token"})
    request = {"messages": [{"content": json.dumps({"nested": inner, "keep": True})}], "stream": False}

    logged = json.loads(request_log_json(request))
    content = json.loads(logged["messages"][0]["content"])

    assert json.loads(content["nested"]) == {"token": "[REDACTED]", "name": "token"}
    assert content["keep"] is True
    assert logged["stream"] is False
