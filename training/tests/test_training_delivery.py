"""합성 산출물로 완료 작업의 공유 폴더 게시와 재시도 무결성을 검사한다."""

import json
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_registry import QWEN_BASE, QWEN_SOURCE, QWEN_TRAINER
from training_jobs import TrainingJobStore, TrainingJobError
import test_model_delivery as delivery_fixture

SECRET, MODEL = delivery_fixture.SECRET, delivery_fixture.MODEL


class TrainingDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.fixture = delivery_fixture.ModelDeliveryTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root / "training"
        self.root.mkdir()
        self.identifier = "1c63d5be-5f69-4cca-8aee-8e34a983471a"
        adapter = self.root / "adapters" / ("trial-web-" + self.identifier)
        adapter.parent.mkdir()
        self.fixture.adapter.rename(adapter)
        self.adapter = adapter
        reports = self.root / "outputs/web-jobs" / self.identifier / "dataset"
        reports.parent.mkdir(parents=True)
        self.fixture.reports.rename(reports)
        self.reports = reports
        self.store = TrainingJobStore(SimpleNamespace(shared_dir=self.fixture.shared), root=self.root)
        self.addCleanup(self.store.close)
        self.record = {"id": self.identifier, "status": "SUCCEEDED", "iterations": 2, "progress": 100,
            "createdAt": "2026-10-21T00:00:00+00:00", "updatedAt": "2026-10-21T00:01:00+00:00",
            "target": {"sourceModel": QWEN_SOURCE, "baseModel": QWEN_BASE, "revision": MODEL["revision"], "trainer": QWEN_TRAINER}}
        self.save_record()

    def save_record(self):
        self.store._path(self.identifier).write_text(json.dumps(self.record))

    def test_delivery_preserves_sources_and_reuses_identical_package(self):
        weights = (self.adapter / "adapters.safetensors").read_bytes()
        result = self.store.delivery(self.identifier, SECRET)
        self.assertEqual(result["status"], "PUBLISHED")
        self.assertEqual(result["sourceModel"], QWEN_SOURCE)
        self.assertEqual(result["baseRevision"], MODEL["revision"])
        self.assertNotIn(str(self.root), json.dumps(result))
        self.assertNotIn(SECRET, json.dumps(result))
        shared = self.fixture.shared / "model-deliveries" / ("web-" + self.identifier)
        self.assertTrue((shared / "READY.json").is_file())
        self.assertEqual((self.adapter / "adapters.safetensors").read_bytes(), weights)
        self.assertEqual(self.store.delivery(self.identifier, SECRET), result)
        self.assertIsNone(self.store.worker)

    def test_incomplete_unknown_and_wrong_model_jobs_cannot_publish(self):
        for change in ({"status": "FAILED"}, {"target": {}}, {"target": {**self.record["target"], "sourceModel": "gemma4:26b"}}):
            original = dict(self.record)
            self.record.update(change)
            self.save_record()
            with self.assertRaises(TrainingJobError) as caught:
                self.store.delivery(self.identifier, SECRET)
            self.assertEqual(caught.exception.code, "DELIVERY_NOT_READY")
            self.record = original
        self.assertFalse((self.fixture.shared / "model-deliveries").exists())

    def test_changed_weights_report_and_target_revision_cannot_publish(self):
        target = self.record["target"]
        self.record["target"] = {**target, "revision": "f" * 40}
        self.save_record()
        with self.assertRaises(TrainingJobError):
            self.store.delivery(self.identifier, SECRET)
        self.record["target"] = target
        self.save_record()
        (self.adapter / "adapters.safetensors").write_bytes(b"changed")
        with self.assertRaises(TrainingJobError):
            self.store.delivery(self.identifier, SECRET)
        self.assertFalse((self.fixture.shared / "model-deliveries").exists())

    def test_model_build_source_requires_unchanged_base_files_from_training_report(self):
        model = self.root / "fixed-model"
        model.mkdir()
        (model / "download_manifest.json").write_text(json.dumps(MODEL))
        weights = model / "model.safetensors"
        weights.write_bytes(b"synthetic-base-weights")
        self.store.model_dir = model
        self.record["target"] = self.store.training_target(QWEN_SOURCE)
        self.save_record()
        report_path = self.reports / "training-report.json"
        report = json.loads(report_path.read_text())
        report["protectedFiles"] = {"model": {path.name: [path.stat().st_size, path.stat().st_mtime_ns]
            for path in model.iterdir() if path.is_file()}}
        report_path.write_text(json.dumps(report))
        source = self.store.model_build_source(self.identifier)
        self.assertEqual(source["model_dir"], model)
        self.assertEqual(source["adapter"], self.adapter)
        weights.write_bytes(b"changed-after-training")
        with self.assertRaises(TrainingJobError) as caught:
            self.store.model_build_source(self.identifier)
        self.assertEqual(caught.exception.code, "DELIVERY_NOT_READY")

    def test_modified_existing_package_is_not_replaced(self):
        self.store.delivery(self.identifier, SECRET)
        package = self.root / "outputs/model-packages" / ("web-" + self.identifier)
        (package / "adapters.safetensors").write_bytes(b"tampered")
        with self.assertRaises(TrainingJobError):
            self.store.delivery(self.identifier, SECRET)
        self.assertEqual((package / "adapters.safetensors").read_bytes(), b"tampered")
