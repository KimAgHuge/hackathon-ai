"""실제 GPU·포트·공유 전송 없이 새 모델 생성의 상태와 무결성을 검사한다."""

import copy
import io
import json
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import DatasetError, required_validation_features, validate_case
from gpu_lease import GpuLease
from model_build_jobs import ModelBuildError, ModelBuildStore
from model_registry import QWEN_BASE, QWEN_SOURCE
from receiver_service import make_handler
from shared_transfer import signed_headers
from trained_model_delivery import (BASE_MODEL_REVISION, LLAMA_CPP_REVISION, MLX_LM_VERSION, PackageError, checksum,
                                    create_package, publish_package, verify_package)
from test_data_tools import case

IDENTIFIER = "1c63d5be-5f69-4cca-8aee-8e34a983471a"
SECRET = "synthetic-model-build-secret-for-unit-tests-only"
BASE = {"modelId": QWEN_BASE, "revision": BASE_MODEL_REVISION}
TARGET = {"sourceModel": QWEN_SOURCE, "baseModel": QWEN_BASE, "revision": BASE["revision"], "digest": "2" * 64}
SUMMARY = {"qualityEvaluation": "NOT_EVALUATED", "productionApproved": False, "sourceAdapterSha256": "3" * 64}


class FinishedProcess:
    returncode = 0

    def poll(self):
        return self.returncode


class ModelBuildTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        state, shared = self.root / "training-state", self.root / "shared"
        state.mkdir()
        shared.mkdir()
        self.source = {"target": TARGET, "model_dir": self.root / "base", "adapter": self.root / "adapter",
                       "reports": self.root / "reports", "summary": SUMMARY, "adapterSha256": "3" * 64}
        self.jobs = SimpleNamespace(root=self.root, state_dir=state, receiver=SimpleNamespace(shared_dir=shared),
                                    model_build_source=lambda identifier: copy.deepcopy(self.source))
        self.calls = []
        self.store = ModelBuildStore(self.jobs, SECRET, environ={}, popen_factory=getattr(self, "execute", None))
        self.addCleanup(self.store.close)
        self.configuration = {"repository": self.root / "pinned-tool", "python": Path(sys.executable), "quantizerSha256": "4" * 64}

    def ready(self):
        self.store._configuration = lambda: (self.configuration, None)

    def execute(self, arguments, **options):
        self.calls.append((arguments, options))
        output = Path(arguments[arguments.index("--output") + 1])
        output.mkdir(exist_ok=True)
        artifact = output / "model.gguf"
        artifact.write_bytes(b"GGUF" + b"synthetic-model-payload" * 4)
        value, size = checksum(artifact)
        report = {"status": "complete", "artifactSha256": value, "artifactSize": size,
                  "quantization": "Q4_K_M", "architecture": "qwen35",
                  "toolchain": {"mlxLmVersion": MLX_LM_VERSION, "llamaCppRevision": LLAMA_CPP_REVISION}}
        (output / "export_report.json").write_text(json.dumps(report))
        return FinishedProcess()

    def finish(self):
        self.store.worker.join(timeout=3)
        self.assertFalse(self.store.worker.is_alive())
        return self.store.get(IDENTIFIER)

    def test_unconfigured_lookup_refuses_generation_without_executing_commands(self):
        state = self.store.get(IDENTIFIER)
        self.assertEqual(state["status"], "NOT_BUILT")
        self.assertFalse(state["available"])
        self.assertEqual(state["reasonCode"], "MODEL_BUILD_UNAVAILABLE")
        with self.assertRaises(ModelBuildError) as caught:
            self.store.submit(IDENTIFIER, {})
        self.assertEqual(caught.exception.code, "MODEL_BUILD_UNAVAILABLE")
        self.assertEqual(self.calls, [])

    def test_preflight_checks_pinned_tools_dependencies_and_exact_disk_threshold(self):
        repository = self.configuration["repository"]
        (repository / "build/bin").mkdir(parents=True)
        converter = repository / "convert_hf_to_gguf.py"
        converter.write_text("# 합성 변환 도구\n")
        quantizer = repository / "build/bin/llama-quantize"
        quantizer.write_bytes(b"synthetic-pinned-tool")
        quantizer.chmod(0o700)
        self.store.environment = {"DOENAAI_TRAINING_LLAMA_CPP_DIR": str(repository),
            "DOENAAI_TRAINING_CONVERTER_PYTHON": sys.executable,
            "DOENAAI_TRAINING_QUANTIZER_SHA256": checksum(quantizer)[0]}
        def result(arguments, **options):
            return SimpleNamespace(stdout=LLAMA_CPP_REVISION if arguments[-2:] == ["rev-parse", "HEAD"] else "")
        with patch("model_build_jobs.sys.platform", "darwin"), \
             patch("model_build_jobs.importlib.metadata.version", return_value=MLX_LM_VERSION), \
             patch("model_build_jobs.subprocess.run", side_effect=result) as run, \
             patch("model_build_jobs.shutil.disk_usage", return_value=SimpleNamespace(free=180 * 1024**3 - 1)) as disk:
            self.assertEqual(self.store._configuration()[1], "MODEL_BUILD_DISK_SPACE")
            disk.return_value.free += 1
            self.assertIsNone(self.store._configuration()[1])
            self.assertTrue(any("find_spec" in " ".join(call.args[0]) for call in run.call_args_list))
            self.store.environment["DOENAAI_TRAINING_QUANTIZER_SHA256"] = "0" * 64
            self.assertEqual(self.store._configuration()[1], "MODEL_BUILD_UNAVAILABLE")

    def test_success_publishes_signed_package_and_repeated_submit_reuses_result(self):
        self.ready()
        initial = self.store.submit(IDENTIFIER, {})
        self.assertEqual(initial["status"], "PENDING")
        final = self.finish()
        self.assertEqual(final["status"], "SUCCEEDED")
        result = final["result"]
        package = self.jobs.receiver.shared_dir / "model-deliveries" / ("trained-web-" + IDENTIFIER)
        self.assertEqual(verify_package(package, SECRET), result)
        self.assertEqual(self.store.submit(IDENTIFIER, {})["result"], result)
        self.assertEqual(len(self.calls), 1)
        arguments, options = self.calls[0]
        self.assertEqual(arguments[0], sys.executable)
        self.assertEqual(Path(arguments[1]).name, "model_export_worker.py")
        self.assertFalse(options["shell"])
        self.assertTrue(options["start_new_session"])
        self.assertEqual(len(options["pass_fds"]), 2)
        self.assertEqual(options["stdout"], subprocess.DEVNULL)
        self.assertNotIn("DOENAAI_TRAINING_TRANSFER_KEY", options["env"])
        self.assertNotIn(SECRET, json.dumps(final))
        self.assertNotIn(str(self.root), json.dumps(final))
        self.assertIsNone(self.store.gpu_lease.descriptor)
        self.assertFalse(list(self.store.work_root.glob("*/attempt-*")))

    def test_gpu_lease_excludes_training_and_other_builds(self):
        self.ready()
        competing = GpuLease(self.jobs.state_dir / ".gpu.lock")
        self.assertTrue(competing.acquire())
        self.addCleanup(competing.release)
        with self.assertRaises(ModelBuildError) as caught:
            self.store.submit(IDENTIFIER, {})
        self.assertEqual(caught.exception.code, "MODEL_BUILD_BUSY")
        self.assertEqual(self.calls, [])
        self.assertFalse(self.store._path(IDENTIFIER).exists())
        self.assertFalse(self.store.get(IDENTIFIER)["available"])
        self.assertEqual(self.store.get(IDENTIFIER)["reasonCode"], "MODEL_BUILD_BUSY")

    def test_forged_export_hash_fails_without_publishing_and_can_retry(self):
        self.ready()
        normal = self.execute
        def altered(arguments, **options):
            process = normal(arguments, **options)
            output = Path(arguments[arguments.index("--output") + 1])
            (output / "model.gguf").write_bytes(b"GGUF" + b"changed" * 20)
            return process
        self.store.popen_factory = altered
        self.store.submit(IDENTIFIER, {})
        self.assertEqual(self.finish()["errorCode"], "MODEL_BUILD_FAILED")
        self.assertFalse(list(self.store.work_root.glob("*/attempt-*")))
        self.assertFalse((self.jobs.receiver.shared_dir / "model-deliveries").exists())
        self.store.popen_factory = normal
        self.store.submit(IDENTIFIER, {})
        self.assertEqual(self.finish()["status"], "SUCCEEDED")
        self.assertEqual(len(self.calls), 2)

    def test_source_change_during_build_blocks_publication(self):
        self.ready()
        normal = self.execute
        def changed(arguments, **options):
            process = normal(arguments, **options)
            self.source["adapterSha256"] = "5" * 64
            return process
        self.store.popen_factory = changed
        self.store.submit(IDENTIFIER, {})
        self.assertEqual(self.finish()["errorCode"], "MODEL_BUILD_SOURCE_INVALID")
        self.assertFalse((self.jobs.receiver.shared_dir / "model-deliveries").exists())

    def test_cancel_terminates_only_owned_process_group_and_releases_gpu(self):
        self.ready()
        entered = threading.Event()
        process = SimpleNamespace(pid=12345, returncode=None)
        process.poll = lambda: process.returncode
        def wait(timeout):
            process.returncode = -15
            return -15
        process.wait = wait
        def running(*arguments, **options):
            entered.set()
            return process
        self.store.popen_factory = running
        with patch("model_build_jobs.os.killpg") as kill:
            self.store.submit(IDENTIFIER, {})
            self.assertTrue(entered.wait(1))
            self.store.cancel(IDENTIFIER, {})
            final = self.finish()
            self.assertEqual(final["status"], "CANCELLED")
            self.assertFalse(list(self.store.work_root.glob("*/attempt-*")))
            kill.assert_called_once()
            self.assertEqual(kill.call_args.args[0], 12345)
        self.assertIsNone(self.store.gpu_lease.descriptor)

    def test_timeout_terminates_owned_process_and_records_safe_error(self):
        self.ready()
        self.store.timeout = -1
        process = SimpleNamespace(pid=12345, returncode=None)
        process.poll = lambda: process.returncode
        def wait(timeout):
            process.returncode = -15
        process.wait = wait
        self.store.popen_factory = lambda *args, **kwargs: process
        with patch("model_build_jobs.os.killpg"):
            self.store.submit(IDENTIFIER, {})
            self.assertEqual(self.finish()["errorCode"], "MODEL_BUILD_TIMEOUT")
        self.assertIsNone(self.store.gpu_lease.descriptor)

    def test_attempt_cleanup_never_follows_replaced_symlink_or_removes_completed_package(self):
        parent = self.store.work_root / IDENTIFIER
        parent.mkdir()
        external = self.root / "protected-output"
        external.mkdir()
        marker = external / "keep"
        marker.write_text("보존할 원본")
        attempt = parent / "attempt-1"
        attempt.symlink_to(external, target_is_directory=True)
        self.store._cleanup_attempt(IDENTIFIER, 1, attempt)
        self.assertFalse(attempt.exists())
        self.assertEqual(marker.read_text(), "보존할 원본")
        with self.assertRaises(ModelBuildError):
            self.store._cleanup_attempt(IDENTIFIER, 1, external)
        self.assertTrue(marker.is_file())

    def test_restart_marks_interrupted_build_failed_without_auto_execution(self):
        record = {"jobId": IDENTIFIER, "status": "CONVERTING", "progress": 40, "attempt": 1,
                  "createdAt": "2026-10-21T00:00:00+00:00", "updatedAt": "2026-10-21T00:00:00+00:00"}
        self.store._path(IDENTIFIER).write_text(json.dumps(record))
        abandoned = self.store.work_root / IDENTIFIER / "attempt-1/export"
        abandoned.mkdir(parents=True)
        (abandoned / "partial.gguf").write_bytes("일회성 변환 조각".encode())
        self.store.close()
        replacement = ModelBuildStore(self.jobs, SECRET, environ={}, popen_factory=self.execute)
        self.addCleanup(replacement.close)
        final = replacement.get(IDENTIFIER)
        self.assertEqual(final["status"], "FAILED")
        self.assertEqual(final["errorCode"], "MODEL_BUILD_INTERRUPTED")
        self.assertEqual(self.calls, [])
        self.assertFalse(abandoned.parent.exists())

    def test_nonempty_body_and_path_like_identifier_are_rejected(self):
        for identifier, body in ((IDENTIFIER, {"path": "/private/model"}), ("../model", {})):
            with self.assertRaises(ModelBuildError) as caught:
                self.store.submit(identifier, body)
            self.assertEqual(caught.exception.http_status, 422)
        self.assertEqual(self.calls, [])


class ModelPackageTests(unittest.TestCase):
    setUp = ModelBuildTests.setUp

    def package(self):
        artifact = self.root / "source.gguf"
        artifact.write_bytes(b"GGUF" + b"synthetic" * 5)
        output = self.root / "package"
        result = create_package(artifact, SUMMARY, output, IDENTIFIER, BASE, SECRET)
        return artifact, output, result

    def test_signed_model_package_cannot_use_adapter_domain_or_changed_file(self):
        artifact, package, result = self.package()
        self.assertEqual(verify_package(package, SECRET), result)
        with self.assertRaises(PackageError):
            verify_package(package, "other-secret-for-unit-tests-only-value")
        (package / "model.gguf").write_bytes(b"GGUF" + b"tampered" * 5)
        with self.assertRaises(PackageError):
            verify_package(package, SECRET)
        self.assertTrue(artifact.is_file())

    def test_publish_reuses_equal_content_and_preserves_conflicting_package(self):
        _, package, result = self.package()
        self.assertEqual(publish_package(package, self.jobs.receiver.shared_dir, SECRET), result)
        self.assertEqual(publish_package(package, self.jobs.receiver.shared_dir, SECRET), result)
        destination = self.jobs.receiver.shared_dir / "model-deliveries" / result["version"]
        (destination / "model.gguf").write_bytes(b"GGUF-conflicting-package")
        with self.assertRaises(PackageError):
            publish_package(package, self.jobs.receiver.shared_dir, SECRET)
        self.assertEqual((destination / "model.gguf").read_bytes(), b"GGUF-conflicting-package")

    def test_copy_cancel_removes_unfinished_package_and_keeps_source(self):
        artifact = self.root / "source.gguf"
        artifact.write_bytes(b"GGUF" + b"synthetic" * 5)
        def cancel():
            raise InterruptedError()
        with self.assertRaises(InterruptedError):
            create_package(artifact, SUMMARY, self.root / "package", IDENTIFIER, BASE, SECRET, check=cancel)
        self.assertTrue(artifact.is_file())
        self.assertFalse((self.root / "package").exists())
        self.assertFalse(list(self.root.glob(".model-package-*")))


class ModelBuildApiTests(unittest.TestCase):
    def request(self, method, suffix="", body=None, authorized=True):
        payload = b"" if body is None else json.dumps(body).encode()
        path = f"/api/training/jobs/{IDENTIFIER}/model-build" + suffix
        handler_type = make_handler(Mock(), SECRET, Mock(), self.builds)
        handler = handler_type.__new__(handler_type)
        handler.command, handler.path = method, path
        handler.headers = {"Content-Length": str(len(payload))}
        if authorized:
            handler.headers.update(signed_headers(SECRET, method, path, payload))
        handler.rfile = io.BytesIO(payload)
        response = []
        handler._send = lambda status, value: response.append((status, value))
        getattr(handler, "do_" + method)()
        return response[0]

    def setUp(self):
        self.builds = Mock()
        self.builds.get.return_value = {"jobId": IDENTIFIER, "status": "NOT_BUILT", "available": False}
        self.builds.submit.return_value = {"jobId": IDENTIFIER, "status": "PENDING"}
        self.builds.cancel.return_value = {"jobId": IDENTIFIER, "status": "CANCELLED"}

    def test_all_build_routes_require_valid_signature(self):
        for method, suffix, body in (("GET", "", None), ("POST", "", {}), ("POST", "/cancel", {})):
            self.assertEqual(self.request(method, suffix, body, False)[0], 401)
        self.assertEqual(self.builds.mock_calls, [])

    def test_state_start_and_cancel_use_distinct_store_operations(self):
        self.assertEqual(self.request("GET")[0], 200)
        self.assertEqual(self.request("POST", body={})[0], 202)
        self.assertEqual(self.request("POST", "/cancel", {})[0], 200)
        self.builds.get.assert_called_once_with(IDENTIFIER)
        self.builds.submit.assert_called_once_with(IDENTIFIER, {})
        self.builds.cancel.assert_called_once_with(IDENTIFIER, {})

    def test_build_errors_do_not_echo_paths_or_exception_details(self):
        self.builds.submit.side_effect = ModelBuildError("MODEL_BUILD_DISK_SPACE", 503)
        status, value = self.request("POST", body={})
        self.assertEqual(status, 503)
        self.assertEqual(value["code"], "MODEL_BUILD_DISK_SPACE")
        self.assertNotIn(SECRET, json.dumps(value))


class TrainedModelSourceTests(unittest.TestCase):
    def test_source_tracks_exact_registered_model_without_changing_base_group(self):
        value = case()
        value["sourceModel"] = QWEN_SOURCE
        value["sourceTrainedModel"] = {"model": "doenaai-trained:web-" + IDENTIFIER, "digest": "a" * 64}
        validate_case(value)
        self.assertIn("SOURCE_TRAINED_MODEL_V1", required_validation_features(value))
        self.assertEqual(value["sourceModel"], QWEN_SOURCE)

    def test_invalid_trained_source_is_never_accepted(self):
        for source in ({"model": "doenaai-trained:web-" + IDENTIFIER, "digest": "a" * 64, "path": "/private/model"},
                       {"model": "qwen3.8:27b", "digest": "a" * 64},
                       {"model": "doenaai-trained:web-" + IDENTIFIER, "digest": "sha256:invalid"}):
            value = case()
            value["sourceModel"], value["sourceTrainedModel"] = QWEN_SOURCE, source
            with self.assertRaises(DatasetError):
                validate_case(value)
