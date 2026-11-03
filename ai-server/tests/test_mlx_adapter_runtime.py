"""모델 다운로드와 GPU 실행 없이 어댑터 전달·실행 경계를 검증한다."""

import asyncio
import hashlib
import hmac
import json
import sys
from pathlib import Path
from types import SimpleNamespace, ModuleType
from unittest.mock import AsyncMock, Mock

import pytest
from pydantic import SecretStr

from app.services.mlx_adapter_runtime import MlxAdapterError, MlxAdapterRuntime, PREFIX
from model_delivery import DOMAIN, canonical

IDENTIFIER = "1c63d5be-5f69-4cca-8aee-8e34a983471a"
SECRET = "synthetic-mlx-adapter-key-for-tests-only"
BASE = "mlx-community/Qwen3.8-27B-4bit"
SOURCE = "qwen3.8:27b"
REVISION = "a" * 40


def write_json(path, value):
    path.write_bytes(canonical(value))


@pytest.fixture
def prepared(tmp_path):
    model = tmp_path / "model"
    model.mkdir()
    write_json(model / "download_manifest.json", {"modelId": BASE, "revision": REVISION})
    write_json(model / "config.json", {"model_type": "qwen3_5", "text_config": {"tie_word_embeddings": False}})
    (model / "model.safetensors").write_bytes(b"synthetic-base")
    version = "web-" + IDENTIFIER
    package = tmp_path / "shared/model-deliveries" / version
    package.mkdir(parents=True)
    (package / "adapters.safetensors").write_bytes(b"synthetic-adapter")
    write_json(package / "adapter_config.json", {"fine_tune_type": "lora"})
    write_json(package / "evaluation-summary.json", {"qualityEvaluated": False})
    files = [{"path": name, "size": (package / name).stat().st_size,
              "sha256": hashlib.sha256((package / name).read_bytes()).hexdigest()}
             for name in ("adapters.safetensors", "adapter_config.json", "evaluation-summary.json")]
    manifest = {"formatVersion": 1, "version": version, "packageType": "MLX_LORA_ADAPTER",
                "model": {"modelId": BASE, "revision": REVISION}, "files": files,
                "productionApproved": False, "ollamaCompatible": False, "qualityEvaluation": "NOT_EVALUATED"}
    write_json(package / "manifest.json", manifest)
    checksum = hashlib.sha256((package / "manifest.json").read_bytes()).hexdigest()
    write_json(package / "READY.json", {"formatVersion": 1, "version": version, "manifestSha256": checksum,
               "signature": hmac.new(SECRET.encode(), DOMAIN + canonical(manifest), hashlib.sha256).hexdigest()})
    settings = SimpleNamespace(ai_mlx_model_dir=str(model), ai_mlx_python=sys.executable,
        ai_mlx_adapter_dir=str(tmp_path / "cache"), training_shared_dir=str(tmp_path / "shared"),
        training_transfer_key=SecretStr(SECRET), ai_request_timeout_seconds=1)
    metadata = {"jobId": IDENTIFIER, "version": version, "manifestSha256": checksum,
                "sourceModel": SOURCE, "baseModel": BASE, "baseRevision": REVISION}
    runner = AsyncMock(return_value={"available": True})
    return MlxAdapterRuntime(settings, runner=runner), metadata, package, runner


def run(awaitable):
    return asyncio.run(awaitable)


def test_prepare_verifies_copies_and_persists_only_metadata(prepared):
    runtime, metadata, package, runner = prepared
    result = run(runtime.prepare(metadata))
    assert result["status"] == "READY" and result["qualityEvaluated"] is False
    assert str(package) not in json.dumps(result) and SECRET not in json.dumps(result)
    local = runtime.cache / metadata["version"]
    assert (local / "adapters.safetensors").read_bytes() == b"synthetic-adapter"
    assert (local.stat().st_mode & 0o777) == 0o700
    assert ((local / "adapters.safetensors").stat().st_mode & 0o777) == 0o600
    assert runner.call_args.kwargs == {"check": True}
    restarted = MlxAdapterRuntime(runtime.settings, runner=runner)
    listing = run(restarted.list())["models"]
    assert listing[0]["name"] == PREFIX + metadata["version"]
    assert listing[0]["digest"] == metadata["manifestSha256"]
    assert restarted.source_model(listing[0]["name"]) == SOURCE
    assert run(restarted.show(listing[0]["name"]))["provider"] == "mlx"


def test_missing_files_wait_and_no_gpu_process_is_started(prepared):
    runtime, metadata, package, runner = prepared
    (package / "adapters.safetensors").unlink()
    result = run(runtime.prepare(metadata))
    assert result["status"] == "WAITING" and result["digest"] == metadata["manifestSha256"]
    runner.assert_not_called()
    assert run(runtime.list()) == {"models": []}


@pytest.mark.parametrize("failure", ["hash", "signature", "revision", "symlink"])
def test_wrong_or_changed_package_is_never_listed(prepared, failure):
    runtime, metadata, package, runner = prepared
    if failure == "hash":
        (package / "adapters.safetensors").write_bytes(b"changed")
    elif failure == "signature":
        ready = json.loads((package / "READY.json").read_text())
        ready["signature"] = "0" * 64
        write_json(package / "READY.json", ready)
    elif failure == "revision":
        metadata["baseRevision"] = "b" * 40
    else:
        weights = package / "adapters.safetensors"
        target = package.parent / "outside.safetensors"
        weights.rename(target)
        weights.symlink_to(target)
    assert run(runtime.prepare(metadata))["status"] == "UNAVAILABLE"
    runner.assert_not_called()
    assert run(runtime.list()) == {"models": []}


def test_missing_runtime_and_failed_environment_check_are_not_ready(prepared):
    runtime, metadata, _, runner = prepared
    runtime.settings.ai_mlx_python = ""
    assert run(runtime.prepare(metadata))["errorCode"] == "MLX_NOT_CONFIGURED"
    runner.assert_not_called()
    runtime.settings.ai_mlx_python = sys.executable
    runner.return_value = {"available": False}
    assert run(runtime.prepare(metadata))["status"] == "UNAVAILABLE"
    assert run(runtime.list()) == {"models": []}
    runner.return_value = {"available": True}
    assert run(runtime.prepare(metadata))["status"] == "READY"
    runner.return_value = {"available": False}
    assert run(runtime.prepare(metadata))["status"] == "UNAVAILABLE"
    assert run(runtime.list()) == {"models": []}


@pytest.mark.parametrize("text", [None, [], "invalid", {"tie_word_embeddings": True}])
def test_invalid_local_model_configuration_cannot_break_installed_listing(prepared, text):
    runtime, metadata, _, runner = prepared
    run(runtime.prepare(metadata))
    write_json(Path(runtime.settings.ai_mlx_model_dir) / "config.json", {"model_type": "qwen3_5", "text_config": text})
    result = run(runtime.prepare(metadata))
    assert result["status"] == "UNAVAILABLE" and result["errorCode"] == "MLX_NOT_CONFIGURED"
    assert run(runtime.list()) == {"models": []}
    assert runner.call_count == 1


@pytest.mark.parametrize("change", [{"sourceModel": "gemma4:26b"}, {"version": "../../outside"},
    {"jobId": "../private"}, {"manifestSha256": "bad"}, {"extra": "path"}])
def test_invalid_metadata_cannot_choose_a_path(prepared, change):
    runtime, metadata, _, runner = prepared
    with pytest.raises(MlxAdapterError) as caught:
        run(runtime.prepare({**metadata, **change}))
    assert caught.value.status == 422
    runner.assert_not_called()


def test_chat_uses_verified_local_adapter_and_preserves_truncation(prepared):
    runtime, metadata, _, runner = prepared
    run(runtime.prepare(metadata))
    runner.return_value = {"content": '{"message":"모델 응답 확인"}', "finishReason": "length",
                           "promptTokens": 10, "generationTokens": 5}
    result = run(runtime.chat(model=PREFIX + metadata["version"], messages=[{"role": "user", "content": "확인"}],
                              format={"type": "object"}, stream=False, options={"num_predict": 5, "temperature": 0}))
    assert result["done_reason"] == "length" and result["done"] is True
    assert runner.call_args.args[1] == runtime.cache / metadata["version"]
    assert runner.call_args.args[2]["maxTokens"] == 5
    assert runner.call_args.args[2]["thinkingEnabled"] is False
    (runtime.cache / metadata["version"] / "adapters.safetensors").write_bytes(b"changed")
    runner.reset_mock()
    with pytest.raises(MlxAdapterError):
        run(runtime.chat(model=PREFIX + metadata["version"], messages=[], format={}, stream=False))
    runner.assert_not_called()


def test_subprocess_environment_cleans_up_plaintext_and_does_not_pass_secrets(prepared, monkeypatch):
    runtime, metadata, _, _ = prepared
    runtime.runner = None
    observed = {}

    async def launch(*arguments, **kwargs):
        observed.update(arguments=arguments, kwargs=kwargs)
        request = Path(arguments[arguments.index("--request") + 1])
        observed["request"] = request
        assert request.stat().st_mode & 0o777 == 0o600
        assert request.parent.stat().st_mode & 0o777 == 0o700
        write_json(Path(arguments[arguments.index("--response") + 1]), {"available": True})
        return SimpleNamespace(returncode=0, wait=AsyncMock(return_value=0))

    monkeypatch.setenv("DOENAAI_AI_INTERNAL_API_KEY", "private-secret-not-for-worker")
    monkeypatch.setattr(asyncio, "create_subprocess_exec", launch)
    assert run(runtime.prepare(metadata))["status"] == "READY"
    assert not observed["request"].exists()
    assert "DOENAAI_AI_INTERNAL_API_KEY" not in observed["kwargs"]["env"]
    assert observed["kwargs"]["env"]["HF_HUB_OFFLINE"] == "1"
    assert observed["kwargs"]["start_new_session"] is True
    assert observed["kwargs"]["stdout"] == asyncio.subprocess.DEVNULL
    assert observed["kwargs"]["stderr"] == asyncio.subprocess.DEVNULL


@pytest.mark.parametrize("failure", [asyncio.TimeoutError, asyncio.CancelledError])
def test_timeout_and_cancellation_kill_child_group_and_remove_plaintext(prepared, monkeypatch, failure):
    runtime, _, package, _ = prepared
    runtime.runner = None
    observed = {}
    process = SimpleNamespace(returncode=None, pid=987654321)
    waits = 0

    async def wait():
        nonlocal waits
        waits += 1
        if waits == 1:
            raise failure()
        process.returncode = 0
        return 0

    async def launch(*arguments, **_):
        observed["request"] = Path(arguments[arguments.index("--request") + 1])
        return process

    process.wait = wait
    killed = Mock()
    monkeypatch.setattr(asyncio, "create_subprocess_exec", launch)
    monkeypatch.setattr("app.services.mlx_adapter_runtime.os.killpg", killed)
    if failure is asyncio.CancelledError:
        with pytest.raises(asyncio.CancelledError):
            run(runtime._execute(Path(runtime.settings.ai_mlx_model_dir), package, {"check": True}, check=True))
    else:
        with pytest.raises(MlxAdapterError) as caught:
            run(runtime._execute(Path(runtime.settings.ai_mlx_model_dir), package, {"check": True}, check=True))
        assert caught.value.code == "MLX_TIMEOUT"
    assert killed.call_args.args[0] == process.pid
    assert waits == 2 and process.returncode == 0
    assert not observed["request"].exists()


def test_gpu_lock_blocks_another_runtime_process_slot_without_starting_workers(prepared):
    runtime, _, _, runner = prepared
    second = MlxAdapterRuntime(runtime.settings, runner=runner)
    with runtime._gpu_lock():
        with pytest.raises(MlxAdapterError) as caught:
            with second._gpu_lock():
                raise AssertionError("겹치는 실행을 허용하면 안 됩니다.")
        assert caught.value.code == "MLX_BUSY"
    with second._gpu_lock():
        pass
    runner.assert_not_called()


@pytest.mark.parametrize("thinking,effort,text", [(False, None, "{}"), (True, None, "검토 내용</think>{}"),
                                                (True, "medium", "<think>검토 내용</think>{}"),
                                                (True, "high", "<think>검토 내용</think>{}")])
def test_worker_applies_adapter_schema_and_selected_thinking_without_real_mlx(monkeypatch, tmp_path, thinking, effort, text):
    import mlx_adapter_infer

    mx = ModuleType("mlx.core")
    mx.set_memory_limit, mx.set_cache_limit = Mock(), Mock()
    mx.random = SimpleNamespace(seed=Mock())
    mlx = ModuleType("mlx")
    mlx.core = mx
    tokenizer = SimpleNamespace(apply_chat_template=Mock(return_value="prompt"), encode=Mock(return_value=[1, 2]))
    lm = ModuleType("mlx_lm")
    lm.load = Mock(return_value=(object(), tokenizer))
    lm.stream_generate = Mock(return_value=iter([SimpleNamespace(text=text, finish_reason="stop", prompt_tokens=2, generation_tokens=1)]))
    sample = ModuleType("mlx_lm.sample_utils")
    sample.make_sampler = Mock(return_value="sampler")
    for name, module in {"mlx": mlx, "mlx.core": mx, "mlx_lm": lm, "mlx_lm.sample_utils": sample}.items():
        monkeypatch.setitem(sys.modules, name, module)
    result = mlx_adapter_infer.infer(tmp_path / "model", tmp_path / "adapter", {
        "messages": [{"role": "user", "content": "요구사항"}], "schema": {"type": "object"}, "maxTokens": 128,
        "thinkingEnabled": thinking, "thinkingEffort": effort})
    lm.load.assert_called_once_with(str(tmp_path / "model"), adapter_path=str(tmp_path / "adapter"))
    assert tokenizer.apply_chat_template.call_args.kwargs["enable_thinking"] is thinking
    if effort:
        assert tokenizer.apply_chat_template.call_args.kwargs["reasoning_effort"] == ("xhigh" if effort == "high" else effort)
    assert tokenizer.apply_chat_template.call_args.args[0][0]["role"] == "system"
    assert result == {"content": "{}", "finishReason": "stop", "promptTokens": 2, "generationTokens": 1}


def test_chat_forwards_enabled_thinking_and_rejects_non_boolean_mode(prepared):
    runtime, metadata, _, runner = prepared
    run(runtime.prepare(metadata))
    runner.return_value = {"content": "{}", "finishReason": "stop", "promptTokens": 10, "generationTokens": 5}
    body = dict(model=PREFIX + metadata["version"], messages=[{"role": "user", "content": "확인"}],
                format={"type": "object"}, stream=False, options={"temperature": 0})
    run(runtime.chat(**body, think=True))
    assert runner.call_args.args[2]["thinkingEnabled"] is True
    run(runtime.chat(**body, think="medium"))
    assert runner.call_args.args[2]["thinkingEnabled"] is True
    assert runner.call_args.args[2]["thinkingEffort"] == "medium"
    runner.reset_mock()
    with pytest.raises(MlxAdapterError):
        run(runtime.chat(**body, think="false"))
    runner.assert_not_called()
