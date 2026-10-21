"""작은 합성 어댑터로 게시 무결성·인증·원본 보존을 검증한다."""

import hashlib
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_delivery import PackageError, create_package, publish_package, verify_package


SECRET = "synthetic-model-delivery-secret-for-tests-only"
PRIVATE_MARKER = "합성 자료의 내부 검토 내용은 공유하지 않습니다."
PURPOSE = "approved_case_smoke_test_do_not_deploy"
MODEL = {"modelId": "mlx-community/Qwen3.8-27B-4bit", "revision": "1" * 40}


def write_json(path, value):
    """시험 입력을 실제 보고서와 같은 JSON 형식으로 저장한다."""
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def synthetic_weights(value=1.0):
    """GPU 없이 읽을 수 있는 두 개의 작은 Safetensors 텐서를 만든다."""
    header = json.dumps({
        "model.layers.0.mlp.gate_proj.lora_a": {"dtype": "F32", "shape": [1, 1], "data_offsets": [0, 4]},
        "model.layers.0.mlp.gate_proj.lora_b": {"dtype": "F32", "shape": [1, 1], "data_offsets": [4, 8]},
    }).encode()
    header += b" " * (-len(header) % 8)
    return struct.pack("<Q", len(header)) + header + struct.pack("<ff", value, value)


class ModelDeliveryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.adapter = self.root / "adapter"
        self.reports = self.root / "reports"
        self.package = self.root / "package"
        self.shared = self.root / "shared"
        self.adapter.mkdir()
        self.reports.mkdir()
        self.shared.mkdir()
        self.version = "trial-20261021-v1"
        (self.adapter / "adapters.safetensors").write_bytes(synthetic_weights())
        self.config = {
            "fine_tune_type": "lora", "num_layers": 1, "purpose": PURPOSE,
            "lora_parameters": {"rank": 1, "scale": 2.0, "dropout": 0.0, "keys": ["mlp.gate_proj"]},
        }
        write_json(self.adapter / "adapter_config.json", self.config)
        self.dataset = {
            "purpose": PURPOSE, "contractSha256": "2" * 64, "messageFormatVersion": 3,
            "caseAssignment": {"private-case": "train"},
            "requirementGroups": {"private-case": "3" * 64},
            "maxTokens": 18000, "thinking": False, "truncatedSamples": 0,
            "caseSetSha256": "4" * 64,
            "stageSampleCounts": {"train": 1, "valid": 0, "test": 0},
            "details": [{"caseId": "private-case", "reason": PRIVATE_MARKER}],
            "notice": PRIVATE_MARKER,
            "sources": [{"file": "private-source.jsonl", "sha256": "5" * 64, "bytes": 123}],
            "model": dict(MODEL),
        }
        write_json(self.reports / "manifest.json", self.dataset)
        self.training = {
            "status": "complete", "purpose": PURPOSE,
            "datasetManifestSha256": checksum(self.reports / "manifest.json"),
            "contractSha256": self.dataset["contractSha256"], "model": dict(MODEL),
            "versions": {"mlx": "0.32.3", "mlx-lm": "0.32.0", "transformers": "5.0.0"},
            "iterations": 2, "layers": 1, "rank": 1, "learningRate": 1e-5,
            "thinking": False, "trainableModules": ["mlp.gate_proj"], "truncatedSamples": 0,
            "steps": [{"step": 1, "loss": 1.5, "gradientNorm": 0.1, "seconds": 1.0, "peakGpuGiB": 1.0},
                      {"step": 2, "loss": 1.4, "gradientNorm": 0.1, "seconds": 2.0, "peakGpuGiB": 1.0}],
            "validationLossBefore": {"valid": None}, "validationLossAfter": {"valid": None},
            "adapterMaxDelta": 0.01, "adapterTensors": 2,
            "adapterSha256": checksum(self.adapter / "adapters.safetensors"),
            "lossEquivalenceMaxDifference": 0.0, "gradientEquivalenceMaxDifference": 0.0,
            "cachedLossEquivalenceMaxDifference": 0.0, "cachedGradientEquivalenceMaxDifference": 0.0,
            "frozenContextReused": True, "protectedFilesUnchanged": True,
            "protectedFiles": {"private": PRIVATE_MARKER}, "probeLogits": [[0.25]],
            "privateNote": PRIVATE_MARKER, "seconds": 2.0, "peakGpuGiB": 1.0,
        }
        self.reload = {"status": "complete", "adapterTensors": 2,
                       "maxLogitDifference": 0.0, "seconds": 1.0, "peakGpuGiB": 1.0}
        self.save_reports()

    def save_reports(self):
        write_json(self.reports / "training-report.json", self.training)
        write_json(self.reports / "reload-report.json", self.reload)

    def create(self, **overrides):
        arguments = dict(adapter_dir=self.adapter, report_dir=self.reports,
                         output_dir=self.package, version=self.version, secret=SECRET)
        arguments.update(overrides)
        return create_package(**arguments)

    def snapshot(self, directory):
        return {str(path.relative_to(directory)): path.read_bytes()
                for path in directory.rglob("*") if path.is_file()}

    def test_package_has_verified_payload_hashes_and_trial_restrictions(self):
        original_adapter, original_reports = self.snapshot(self.adapter), self.snapshot(self.reports)
        self.create()
        self.assertEqual({path.name for path in self.package.iterdir()}, {
            "adapters.safetensors", "adapter_config.json", "evaluation-summary.json", "manifest.json", "READY.json",
        })
        result = verify_package(self.package, SECRET)
        self.assertEqual(result["status"], "VERIFIED_TRIAL_ONLY")
        self.assertEqual(result["version"], self.version)
        manifest = json.loads((self.package / "manifest.json").read_text())
        self.assertEqual(manifest["packageType"], "MLX_LORA_ADAPTER")
        self.assertIs(manifest["productionApproved"], False)
        self.assertIs(manifest["ollamaCompatible"], False)
        self.assertEqual(manifest["qualityEvaluation"], "NOT_EVALUATED")
        self.assertEqual(manifest["model"]["modelId"], MODEL["modelId"])
        self.assertEqual(manifest["model"]["revision"], MODEL["revision"])
        self.assertEqual({row["path"] for row in manifest["files"]}, {
            "adapters.safetensors", "adapter_config.json", "evaluation-summary.json",
        })
        for row in manifest["files"]:
            file = self.package / row["path"]
            self.assertEqual(row["sha256"], checksum(file))
            self.assertEqual(row["size"], file.stat().st_size)
        self.assertEqual(self.snapshot(self.adapter), original_adapter)
        self.assertEqual(self.snapshot(self.reports), original_reports)

    def test_private_reports_and_secret_are_not_copied_to_shared_package(self):
        self.create()
        original_names = set(self.snapshot(self.package))
        publish_package(self.package, self.shared, SECRET)
        destination = self.shared / "model-deliveries" / self.version
        self.assertEqual(set(self.snapshot(destination)), original_names)
        for payload in self.snapshot(destination).values():
            self.assertNotIn(SECRET.encode(), payload)
            self.assertNotIn(PRIVATE_MARKER.encode(), payload)
            self.assertNotIn(b"private-case", payload)
            self.assertNotIn(b"private-source.jsonl", payload)
            self.assertNotIn(str(self.reports).encode(), payload)

    def test_short_or_empty_secret_is_rejected_before_creating_output(self):
        for secret in ("", "short-key", "x" * 31):
            with self.subTest(length=len(secret)):
                with self.assertRaises(PackageError):
                    self.create(secret=secret)
                self.assertFalse(self.package.exists())

    def test_versions_cannot_escape_directory_or_use_unexpected_characters(self):
        for version in ("../escape", "/absolute", "nested/version", ".", "..", "한글", "x" * 81):
            with self.subTest(version=version):
                with self.assertRaises(PackageError):
                    self.create(version=version)
                self.assertFalse(self.package.exists())

    def test_existing_output_is_preserved(self):
        self.package.mkdir()
        protected = self.package / "do-not-overwrite.txt"
        protected.write_text("기존 결과를 보존합니다.")
        before = self.snapshot(self.package)
        with self.assertRaises(PackageError):
            self.create()
        self.assertEqual(self.snapshot(self.package), before)

    def test_adapter_modified_after_training_is_rejected(self):
        (self.adapter / "adapters.safetensors").write_bytes(synthetic_weights(2.0))
        with self.assertRaises(PackageError):
            self.create()
        self.assertFalse(self.package.exists())

    def test_dataset_changed_after_training_is_rejected(self):
        self.dataset["stageSampleCounts"]["train"] = 2
        write_json(self.reports / "manifest.json", self.dataset)
        with self.assertRaises(PackageError):
            self.create()
        self.assertFalse(self.package.exists())

    def test_contract_digest_cannot_carry_private_text_even_when_reports_agree(self):
        self.dataset["contractSha256"] = PRIVATE_MARKER
        self.training["contractSha256"] = PRIVATE_MARKER
        write_json(self.reports / "manifest.json", self.dataset)
        self.training["datasetManifestSha256"] = checksum(self.reports / "manifest.json")
        self.save_reports()
        with self.assertRaises(PackageError):
            self.create()
        self.assertFalse(self.package.exists())

    def test_training_and_reload_evidence_must_agree(self):
        mutations = (
            ("training", "status", "failed"),
            ("training", "protectedFilesUnchanged", False),
            ("training", "rank", 2),
            ("training", "layers", 2),
            ("training", "trainableModules", ["mlp.down_proj"]),
            ("training", "iterations", 3),
            ("training", "model", {"modelId": MODEL["modelId"], "revision": "6" * 40}),
            ("reload", "status", "failed"),
            ("reload", "adapterTensors", 3),
            ("reload", "maxLogitDifference", 0.1),
            ("reload", "maxLogitDifference", float("nan")),
        )
        for target, key, value in mutations:
            with self.subTest(target=target, key=key, value=value):
                report = self.training if target == "training" else self.reload
                original = report[key]
                report[key] = value
                self.save_reports()
                with self.assertRaises(PackageError):
                    self.create()
                self.assertFalse(self.package.exists())
                report[key] = original
                self.save_reports()

    def test_missing_required_source_is_rejected(self):
        (self.adapter / "adapter_config.json").unlink()
        with self.assertRaises(PackageError):
            self.create()
        self.assertFalse(self.package.exists())

    def test_different_secret_cannot_verify_or_publish(self):
        self.create()
        other_secret = "different-synthetic-model-delivery-secret"
        with self.assertRaises(PackageError):
            verify_package(self.package, other_secret)
        with self.assertRaises(PackageError):
            publish_package(self.package, self.shared, other_secret)
        self.assertEqual(list(self.shared.iterdir()), [])

    def test_payload_tampering_is_rejected_before_publication(self):
        self.create()
        (self.package / "adapters.safetensors").write_bytes(synthetic_weights(3.0))
        with self.assertRaises(PackageError):
            verify_package(self.package, SECRET)
        with self.assertRaises(PackageError):
            publish_package(self.package, self.shared, SECRET)
        self.assertEqual(list(self.shared.iterdir()), [])

    def test_missing_sync_file_can_be_rechecked_after_arrival(self):
        self.create()
        payload = self.package / "adapters.safetensors"
        held = self.root / "held.safetensors"
        payload.rename(held)
        with self.assertRaises(PackageError):
            verify_package(self.package, SECRET)
        held.rename(payload)
        self.assertEqual(verify_package(self.package, SECRET)["status"], "VERIFIED_TRIAL_ONLY")

    def test_ready_marker_and_manifest_are_authenticated(self):
        self.create()
        for name, mutate in (
            ("READY.json", lambda row: row.update({"manifestSha256": "0" * 64})),
            ("manifest.json", lambda row: row.update({"productionApproved": True})),
        ):
            with self.subTest(name=name):
                file = self.package / name
                original = file.read_bytes()
                row = json.loads(original)
                mutate(row)
                write_json(file, row)
                with self.assertRaises(PackageError):
                    verify_package(self.package, SECRET)
                file.write_bytes(original)

    def test_missing_ready_marker_does_not_count_as_verified_publication(self):
        self.create()
        (self.package / "READY.json").unlink()
        with self.assertRaises(PackageError):
            verify_package(self.package, SECRET)
        with self.assertRaises(PackageError):
            publish_package(self.package, self.shared, SECRET)
        self.assertEqual(list(self.shared.iterdir()), [])

    def test_symlink_cannot_replace_verified_payload(self):
        self.create()
        payload = self.package / "adapters.safetensors"
        outside = self.root / "outside.safetensors"
        outside.write_bytes(payload.read_bytes())
        payload.unlink()
        payload.symlink_to(outside)
        with self.assertRaises(PackageError):
            verify_package(self.package, SECRET)
        self.assertEqual(outside.read_bytes(), synthetic_weights())

    def test_publish_is_idempotent_and_preserves_local_source(self):
        self.create()
        local_before = self.snapshot(self.package)
        first = publish_package(self.package, self.shared, SECRET)
        self.assertEqual(first["status"], "PUBLISHED")
        destination = self.shared / "model-deliveries" / self.version
        self.assertEqual(verify_package(destination, SECRET)["status"], "VERIFIED_TRIAL_ONLY")
        published_before = self.snapshot(destination)
        second = publish_package(self.package, self.shared, SECRET)
        self.assertEqual(second["status"], "ALREADY_PUBLISHED")
        self.assertEqual(self.snapshot(destination), published_before)
        local_after = self.snapshot(self.package)
        self.assertEqual({name: local_after[name] for name in local_before}, local_before)
        self.assertEqual(set(local_after) - set(local_before), {".publish.lock", ".publish-state.json"})
        state = json.loads((self.package / ".publish-state.json").read_text())
        self.assertEqual(state["status"], "ALREADY_PUBLISHED")
        self.assertEqual(state["deliveryState"], "WAITING_FOR_REMOTE_VERIFICATION")
        self.assertIs(state["cloudUploadVerified"], False)

    def test_interrupted_publication_cleans_partial_files_and_can_be_retried(self):
        self.create()
        import model_delivery
        original_copy = model_delivery.copy_file
        written = []

        def fail_after_first_payload(source, destination):
            written.append(Path(destination).name)
            if len(written) == 2:
                raise OSError("합성 디스크 쓰기 오류")
            return original_copy(source, destination)

        with patch("model_delivery.copy_file", side_effect=fail_after_first_payload):
            with self.assertRaises(OSError):
                publish_package(self.package, self.shared, SECRET)
        self.assertFalse((self.shared / "model-deliveries" / self.version).exists())
        self.assertEqual(list((self.shared / "model-deliveries").iterdir()), [])
        self.assertFalse((self.package / ".publish-state.json").exists())
        self.assertEqual(publish_package(self.package, self.shared, SECRET)["status"], "PUBLISHED")

    def test_manifest_cannot_redirect_payload_outside_package_even_when_signed(self):
        self.create()
        import hmac
        import model_delivery
        manifest_file, ready_file = self.package / "manifest.json", self.package / "READY.json"
        manifest = json.loads(manifest_file.read_text())
        manifest["files"][0]["path"] = "../outside.safetensors"
        (self.root / "outside.safetensors").write_bytes(synthetic_weights())
        write_json(manifest_file, manifest)
        ready = json.loads(ready_file.read_text())
        ready["manifestSha256"] = checksum(manifest_file)
        ready["signature"] = hmac.new(SECRET.encode(), model_delivery.DOMAIN + model_delivery.canonical(manifest), hashlib.sha256).hexdigest()
        write_json(ready_file, ready)
        with self.assertRaises(PackageError):
            verify_package(self.package, SECRET)

    def test_same_version_with_different_adapter_does_not_overwrite_publication(self):
        self.create()
        publish_package(self.package, self.shared, SECRET)
        destination = self.shared / "model-deliveries" / self.version
        published_before = self.snapshot(destination)
        (self.adapter / "adapters.safetensors").write_bytes(synthetic_weights(4.0))
        self.training["adapterSha256"] = checksum(self.adapter / "adapters.safetensors")
        self.save_reports()
        other_package = self.root / "other-package"
        self.create(output_dir=other_package)
        with self.assertRaises(PackageError):
            publish_package(other_package, self.shared, SECRET)
        self.assertEqual(self.snapshot(destination), published_before)

    def test_missing_shared_root_does_not_create_cloud_folder(self):
        self.create()
        self.shared.rmdir()
        with self.assertRaises((PackageError, OSError)):
            publish_package(self.package, self.shared, SECRET)
        self.assertFalse(self.shared.exists())

    def test_shared_delivery_directory_cannot_redirect_publication_with_symlink(self):
        self.create()
        outside = self.root / "outside-shared"
        outside.mkdir()
        (self.shared / "model-deliveries").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(PackageError):
            publish_package(self.package, self.shared, SECRET)
        self.assertEqual(list(outside.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
