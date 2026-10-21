"""선택한 모델의 공통 학습 분기와 부모 어댑터 보존을 실제 작업 저장소로 검사한다."""

import copy
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import digest
from model_registry import QWEN_BASE, QWEN_SOURCE
from shared_transfer import write_json_atomic
from training_jobs import TrainingJobError
from training_profiles import GENERIC_TRAINER, QWEN_PROFILE
import test_model_delivery as delivery_fixture
import test_training_worker as worker_fixture

checksum = delivery_fixture.checksum


class SelectedModelTrainingTests(unittest.TestCase):
    def setUp(self):
        self.fixture = worker_fixture.TrainingWorkerTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root, self.store = self.fixture.root, self.fixture.store

    def generic(self):
        """두 모델의 원본 폴더와 서로 다른 학습 프로필을 준비한다."""
        model = self.root / "exaone-model"
        model.mkdir()
        write_json_atomic(model / "download_manifest.json", {"modelId": "local/exaone4-fixture", "revision": "b" * 40})
        write_json_atomic(model / "config.json", {"model_type": "exaone4", "num_hidden_layers": 2})
        profile = {**copy.deepcopy(QWEN_PROFILE), "modelType": "exaone4", "maxSequenceLength": 2048,
                   "loraKeys": ["self_attn.q_proj", "self_attn.v_proj"], "gradientCheckpointing": True,
                   "exporter": "mlx-lm-hf-gguf-v1", "exportArchitecture": "exaone4"}
        registry = self.root / "registered-models.json"
        write_json_atomic(registry, {"exaone4:fixture": {"directory": str(model), "baseModel": "local/exaone4-fixture",
            "trainer": GENERIC_TRAINER, "profile": profile}})
        self.store.models_file = registry
        return profile

    def parent(self):
        """완료·서명 전 검사에 통과하는 부모 보고서와 작은 어댑터를 만든다."""
        fixture = delivery_fixture.ModelDeliveryTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.config["lora_parameters"] = {"rank": 4, "scale": 8.0, "dropout": 0.0,
                                             "keys": list(QWEN_PROFILE["loraKeys"])}
        fixture.training.update(rank=4, trainableModules=list(QWEN_PROFILE["loraKeys"]))
        write_json_atomic(fixture.adapter / "adapter_config.json", fixture.config)
        fixture.save_reports()
        identifier = str(uuid4())
        adapter = self.root / "adapters" / ("trial-web-" + identifier)
        adapter.parent.mkdir(exist_ok=True)
        fixture.adapter.rename(adapter)
        reports = self.store.work_root / identifier / "dataset"
        reports.parent.mkdir(parents=True)
        fixture.reports.rename(reports)
        write_json_atomic(self.root / "model/download_manifest.json", {"modelId": QWEN_BASE, "revision": "1" * 40})
        report = json.loads((reports / "training-report.json").read_text())
        report["protectedFiles"] = {"model": {path.name: [path.stat().st_size, path.stat().st_mtime_ns]
                                                for path in (self.root / "model").iterdir() if path.is_file()}}
        write_json_atomic(reports / "training-report.json", report)
        target = self.store.training_target(QWEN_SOURCE)
        record = {"id": identifier, "status": "SUCCEEDED", "iterations": 2, "progress": 100,
                  "createdAt": "2026-10-21T00:00:00+00:00", "updatedAt": "2026-10-21T00:01:00+00:00", "target": target}
        write_json_atomic(self.store._path(identifier), record)
        return identifier, adapter

    def execute(self, identifier, profile, calls):
        """실행 인자와 새 결과 경로를 검사하며 GPU 대신 최소 보고서를 작성한다."""
        def run(arguments, failure, failure_result=None):
            arguments = list(map(str, arguments))
            calls.append(arguments)
            directory = self.store.work_root / identifier / "dataset"
            if len(calls) == 1:
                write_json_atomic(directory / "manifest.json", {"stageSampleCounts": {"train": 1, "valid": 1, "test": 0}})
            elif len(calls) == 2:
                report = {"status": "complete", "protectedFilesUnchanged": True, "profileSha256": digest(profile),
                          "steps": [{"loss": 1.0}], "validationLossBefore": {"valid": 1.2},
                          "validationLossAfter": {"valid": 1.1}, "adapterTensors": 4, "peakGpuGiB": 1}
                if "--resume-adapter" in arguments:
                    parent = Path(arguments[arguments.index("--resume-adapter") + 1])
                    report["parentAdapterSha256"] = checksum(parent / "adapters.safetensors")
                    report["parentConfigSha256"] = checksum(parent / "adapter_config.json")
                write_json_atomic(directory / "training-report.json", report)
            else:
                write_json_atomic(directory / "reload-report.json", {"status": "complete", "maxLogitDifference": 0})
        return run

    def test_selected_exaone_uses_common_worker_profile_and_own_output(self):
        profile = self.generic()
        self.fixture.verified(source_model="exaone4:fixture")
        target = self.store.training_target("exaone4:fixture")
        qwen = self.store.training_target(QWEN_SOURCE)
        self.assertNotEqual(target["digest"], qwen["digest"])
        identifier = str(uuid4())
        self.store.submit({"jobId": identifier, "iterations": 2, "model": "exaone4:fixture", "digest": target["digest"]})
        calls = []
        with patch.object(self.store, "_execute", side_effect=self.execute(identifier, profile, calls)):
            self.store._run(identifier)
        self.assertEqual(self.store.get_job(identifier)["status"], "SUCCEEDED")
        self.assertEqual(Path(calls[1][0]).name, "mlx_lora_train.py")
        self.assertEqual(Path(calls[2][0]).name, "mlx_lora_train.py")
        self.assertEqual(calls[0][calls[0].index("--max-tokens") + 1], "2048")
        self.assertIn("--profile-file", calls[1])
        self.assertNotIn("--layers", calls[1])
        self.assertNotIn("--resume-adapter", calls[1])
        self.assertEqual(Path(calls[1][calls[1].index("--adapter") + 1]).name, "trial-web-" + identifier)

    def test_profile_changes_invalidate_old_start_request_and_do_not_fall_back(self):
        self.generic()
        target = self.store.training_target("exaone4:fixture")
        registry = json.loads(self.store.models_file.read_text())
        registry["exaone4:fixture"]["profile"]["loraRank"] = 8
        write_json_atomic(self.store.models_file, registry)
        with self.assertRaises(TrainingJobError):
            self.store.submit({"jobId": str(uuid4()), "iterations": 1, "model": "exaone4:fixture", "digest": target["digest"]})
        self.assertEqual(self.store.training_target("exaone4:fixture")["sourceModel"], "exaone4:fixture")

    def test_derived_selection_retains_origin_and_parent_hashes(self):
        identifier, adapter = self.parent()
        selected = "doenaai-trained:web-" + identifier
        target = self.store.training_target(selected)
        self.assertEqual(target["sourceModel"], QWEN_SOURCE)
        self.assertEqual(target["selectedModel"], selected)
        self.assertEqual(target["resumeJobId"], identifier)
        self.assertEqual(target["parentAdapterSha256"], checksum(adapter / "adapters.safetensors"))
        public = self.store.capabilities(source_model=selected)
        self.assertTrue(public["trainingProfile"]["continuation"])
        self.assertEqual(public["requestedModel"], selected)
        self.assertNotIn(str(self.root), json.dumps(public))

    def test_continuation_passes_parent_only_to_training_and_preserves_parent(self):
        parent_id, adapter = self.parent()
        before = {p.name: p.read_bytes() for p in adapter.iterdir()}
        self.fixture.verified()
        selected = "doenaai-mlx:web-" + parent_id
        target = self.store.training_target(selected)
        identifier = str(uuid4())
        self.store.submit({"jobId": identifier, "iterations": 1, "model": selected, "digest": target["digest"]})
        calls = []
        with patch.object(self.store, "_execute", side_effect=self.execute(identifier, QWEN_PROFILE, calls)):
            self.store._run(identifier)
        self.assertEqual(self.store.get_job(identifier)["status"], "SUCCEEDED")
        self.assertEqual(calls[1][calls[1].index("--resume-adapter") + 1], str(adapter))
        self.assertNotIn("--resume-adapter", calls[2])
        self.assertEqual({p.name: p.read_bytes() for p in adapter.iterdir()}, before)

    def test_changed_parent_blocks_stale_request_before_execution(self):
        identifier, adapter = self.parent()
        selected = "doenaai-trained:web-" + identifier
        target = self.store.training_target(selected)
        (adapter / "adapters.safetensors").write_bytes(b"changed-parent")
        self.assertIsNone(self.store.training_target(selected))
        with self.assertRaises(TrainingJobError):
            self.store.submit({"jobId": str(uuid4()), "iterations": 1, "model": selected, "digest": target["digest"]})

    def test_changed_base_files_block_continuation_even_with_unchanged_registered_identity(self):
        identifier, _ = self.parent()
        selected = "doenaai-trained:web-" + identifier
        target = self.store.training_target(selected)
        (self.root / "model/model.safetensors").write_bytes(b"different-base-weights")
        self.assertIsNone(self.store.training_target(selected))
        with self.assertRaises(TrainingJobError):
            self.store.submit({"jobId": str(uuid4()), "iterations": 1, "model": selected, "digest": target["digest"]})


if __name__ == "__main__":
    unittest.main()
