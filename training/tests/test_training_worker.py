"""실제 GPU·토크나이저 다운로드 없이 학습 워커의 경계와 영속 상태를 검사한다."""

import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dataset_contract import read_jsonl, DatasetError
from gpu_lease import GpuLease
from receiver_config import load_receiver_env
from receiver_service import ReceiverStore
from shared_transfer import canonical_bytes, write_json_atomic
from training_job_prepare import automatic_splits
from training_jobs import TrainingJobError, TrainingJobStore
from transfer_cases import content_hash
from trial_dataset import prepare_trial
from test_trial_dataset import Tokenizer, records


class LongTokenizer(Tokenizer):
    """표시한 합성 사례만 길이 상한 밖으로 보내며 접두부 일치는 유지한다."""

    def apply_chat_template(self, messages, **kwargs):
        result = super().apply_chat_template(messages, **kwargs)
        return ([0] * 20000 if "LONG_FIXTURE" in str(messages) else []) + result


class Process:
    """운영 프로세스를 만들거나 종료하지 않는 대체 실행 결과다."""

    def __init__(self, returncode=0):
        self.returncode = returncode
        self.pid = 987654321

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        self.returncode = -15
        return self.returncode


class TrainingWorkerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.receiver = ReceiverStore(self.root / "shared", self.root / "receiver", self.root / "staging", self.root / "inbox")
        (self.root / "model").mkdir()
        write_json_atomic(self.root / "model/download_manifest.json", {"modelId": "mlx-community/Qwen3.8-27B-4bit", "revision": "a" * 40})
        self.store = TrainingJobStore(self.receiver, self.root / "model", root=self.root)
        self.addCleanup(self.store.close)
        self.configuration = patch.object(self.store, "_configuration_error", return_value=None)
        self.configuration.start()
        self.addCleanup(self.configuration.stop)
        self.threads = patch("training_jobs.threading.Thread")
        mocked = self.threads.start()
        mocked.return_value.is_alive.return_value = False
        self.addCleanup(self.threads.stop)

    def submit_body(self, body):
        target = self.store.training_target()
        return self.store.submit({"model": "qwen3.8:27b", "digest": target["digest"] if target else "a" * 64, **body})

    def submit(self, iterations=2):
        return self.submit_body({"jobId": str(uuid4()), "iterations": iterations})

    def verified(self, values=None, source_model="qwen3.8:27b"):
        values = records() if values is None else values
        for record in values:
            if source_model is not None:
                record.setdefault("sourceModel", source_model)
            payload = canonical_bytes(record)
            temporary = self.root / "case.jsonl"
            temporary.write_bytes(payload)
            checksum, size = content_hash(temporary)
            temporary.rename(self.receiver.inbox / f"{checksum}.jsonl")
            write_json_atomic(self.receiver.state_dir / f"{checksum}.json",
                              {"sha256": checksum, "cases": 1, "size": size, "status": "VERIFIED"})

    def test_catalog_and_worker_exclude_other_models_and_unknown_legacy_sources(self):
        values = records()
        for value in values:
            value["sourceModel"] = "qwen3.8:27b"
        other = copy.deepcopy(values[0])
        other.update(caseId="other-model-case", sourceModel="ingu627/exaone4.0:32b")
        legacy = copy.deepcopy(values[0])
        legacy["caseId"] = "legacy-model-case"
        legacy.pop("sourceModel")
        self.verified([*values, other, legacy], source_model=None)
        catalog = self.store.dataset_catalog()
        self.assertEqual(catalog["total"], 3)
        self.assertEqual(catalog["excluded"], 2)
        directory = self.root / "snapshot-test"
        directory.mkdir()
        sources = self.store._snapshot(directory)
        selected = [value for path in sources.glob("*.jsonl") for value in read_jsonl(path)]
        self.assertEqual(len(selected), 3)
        self.assertTrue(all(value["sourceModel"] == "qwen3.8:27b" for value in selected))
        self.assertEqual(len(list(self.receiver.inbox.glob("*.jsonl"))), 5)

    def test_model_build_gpu_lease_blocks_training_and_releases_after_cancel(self):
        competing = GpuLease(self.store.state_dir / ".gpu.lock")
        self.assertTrue(competing.acquire())
        try:
            with self.assertRaises(TrainingJobError) as caught:
                self.submit()
            self.assertEqual(caught.exception.code, "TRAINING_BUSY")
            self.assertEqual(self.store.list_jobs()["jobs"], [])
        finally:
            competing.release()
        job = self.submit()
        self.assertIsNotNone(self.store.gpu_lease.descriptor)
        self.store.cancel(job["id"], {})
        self.store._run(job["id"])
        self.assertIsNone(self.store.gpu_lease.descriptor)
        self.assertTrue(competing.acquire())
        competing.release()

    def test_wrong_model_or_changed_base_revision_cannot_start_training(self):
        target = self.store.training_target()
        for model, checksum in (("gemma4:26b", target["digest"]), (target["sourceModel"], "0" * 64)):
            with self.assertRaises(TrainingJobError):
                self.store.submit({"jobId": str(uuid4()), "iterations": 2, "model": model, "digest": checksum})
        with self.assertRaises(TrainingJobError):
            self.store.submit({"jobId": str(uuid4()), "iterations": 2})
        self.assertEqual(self.store.list_jobs()["jobs"], [])

    def test_model_change_after_acceptance_stops_before_dataset_or_gpu_work(self):
        job = self.submit()
        write_json_atomic(self.root / "model/download_manifest.json", {"modelId": "mlx-community/Qwen3.8-27B-4bit", "revision": "b" * 40})
        with patch.object(self.store, "_execute") as execute:
            self.store._run(job["id"])
        execute.assert_not_called()
        self.assertEqual(self.store.get_job(job["id"])["errorCode"], "TRAINING_UNAVAILABLE")

    def test_other_model_capability_query_does_not_change_an_accepted_job(self):
        job = self.submit()
        original_target = job["target"]
        status = self.store.capabilities(source_model="gemma4:26b")
        self.assertFalse(status["trainingExecution"])
        self.assertEqual(status["trainingReasonCode"], "MODEL_NOT_REGISTERED")
        self.assertIsNone(status["dataset"]["target"])
        self.assertEqual(self.store.get_job(job["id"])["target"], original_target)
        self.assertEqual(self.store.model_dir, self.root / "model")

    def test_registered_but_unsupported_trainer_is_not_run_as_qwen(self):
        other = self.root / "other-model"
        other.mkdir()
        write_json_atomic(other / "download_manifest.json", {"modelId": "local/other-base", "revision": "a" * 40})
        registry = self.root / "registry.json"
        write_json_atomic(registry, {"devstral:latest": {"directory": str(other), "baseModel": "local/other-base", "trainer": "unavailable"}})
        self.store.models_file = registry
        self.configuration.stop()
        status = self.store.capabilities(source_model="devstral:latest")
        self.assertFalse(status["trainingExecution"])
        self.assertEqual(status["trainingReasonCode"], "TRAINER_UNSUPPORTED")
        target = status["dataset"]["target"]
        with self.assertRaises(TrainingJobError):
            self.store.submit({"jobId": str(uuid4()), "iterations": 2, "model": "devstral:latest", "digest": target["digest"]})
        self.assertEqual(self.store.list_jobs()["jobs"], [])

    def test_only_exact_id_and_bounded_integer_request_is_accepted(self):
        for value in ({"jobId": "../private", "iterations": 2}, {"jobId": str(uuid4()), "iterations": True},
                      {"jobId": str(uuid4()), "iterations": 0}, {"jobId": str(uuid4()), "iterations": 21},
                      {"jobId": str(uuid4()), "iterations": 2, "model": "/private/model"},
                      {"jobId": str(uuid4()), "iterations": 2, "command": "unsafe"}):
            with self.subTest(value=value), self.assertRaises(TrainingJobError):
                self.submit_body(value)
        self.assertEqual(self.store.list_jobs()["jobs"], [])

    def test_job_is_idempotent_and_other_conditions_or_concurrent_job_are_refused(self):
        job = self.submit()
        self.assertEqual(self.submit_body({"jobId": job["id"], "iterations": 2}), job)
        for body, code in (({"jobId": job["id"], "iterations": 3}, "JOB_CONFLICT"),
                           ({"jobId": str(uuid4()), "iterations": 2}, "TRAINING_BUSY")):
            with self.assertRaises(TrainingJobError) as error:
                self.submit_body(body)
            self.assertEqual(error.exception.code, code)

    def test_two_store_instances_share_process_lock_and_restart_does_not_retrain(self):
        job = self.submit()
        second = TrainingJobStore(self.receiver, self.root / "model", root=self.root)
        self.addCleanup(second.close)
        with patch.object(second, "_configuration_error", return_value=None):
            self.assertFalse(second.capabilities()["trainingExecution"])
            self.store.close()
            recovered = second.get_job(job["id"])
            self.assertEqual(recovered["status"], "FAILED")
            self.assertEqual(recovered["errorCode"], "JOB_INTERRUPTED")
            self.assertEqual(second.submit({"jobId": job["id"], "iterations": 2, "model": "qwen3.8:27b", "digest": job["target"]["digest"]}), recovered)

    def test_empty_or_unverified_inbox_never_starts_subprocess(self):
        self.verified(records()[:1])
        receipt = next(self.receiver.state_dir.glob("*.json"))
        value = json.loads(receipt.read_text())
        value["status"] = "WAITING_FOR_FILE"
        write_json_atomic(receipt, value)
        job = self.submit()
        with patch.object(self.store, "_execute") as execute:
            self.store._run(job["id"])
        execute.assert_not_called()
        self.assertEqual(self.store.get_job(job["id"])["errorCode"], "DATASET_TOO_SMALL")

    def test_changed_and_unapproved_received_files_are_refused(self):
        for change in ("hash", "approval"):
            with self.subTest(change=change):
                values = records()
                if change == "approval":
                    values[0]["humanApproved"] = False
                self.verified(values)
                if change == "hash":
                    next(self.receiver.inbox.glob("*.jsonl")).write_text("private original")
                job = self.submit()
                with patch.object(self.store, "_execute") as execute:
                    self.store._run(job["id"])
                execute.assert_not_called()
                self.assertEqual(self.store.get_job(job["id"])["errorCode"], "DATASET_INVALID")
                for path in self.receiver.inbox.glob("*.jsonl"):
                    path.unlink()
                for path in self.receiver.state_dir.glob("*.json"):
                    path.unlink()

    def test_model_paths_are_only_server_configuration_and_missing_configuration_is_explicit(self):
        self.configuration.stop()
        self.store.model_dir = None
        self.assertFalse(self.store.capabilities()["trainingExecution"])
        self.assertEqual(self.store.capabilities()["trainingReasonCode"], "MODEL_NOT_REGISTERED")
        with self.assertRaises(TrainingJobError) as error:
            self.submit()
        self.assertEqual(error.exception.code, "TRAINING_UNAVAILABLE")

    def test_compatible_local_configuration_is_checked_without_importing_gpu_modules(self):
        self.configuration.stop()
        model = self.store.model_dir
        model.mkdir(exist_ok=True)
        write_json_atomic(model / "config.json", {"model_type": "qwen3_5", "text_config": {"tie_word_embeddings": False}})
        write_json_atomic(model / "download_manifest.json", {"modelId": "mlx-community/Qwen3.8-27B-4bit", "revision": "a" * 40})
        (model / "weights.safetensors").write_bytes(b"synthetic-not-a-model")
        with patch("training_jobs.sys.platform", "darwin"), patch("training_jobs.importlib.util.find_spec", return_value=object()):
            self.assertTrue(self.store.capabilities()["trainingExecution"])
            write_json_atomic(model / "config.json", {"model_type": "other"})
            self.assertFalse(self.store.capabilities()["trainingExecution"])

    def test_symlink_cannot_replace_a_verified_input(self):
        self.verified()
        package = next(self.receiver.inbox.glob("*.jsonl"))
        preserved = self.root / "preserved.jsonl"
        package.rename(preserved)
        package.symlink_to(preserved)
        job = self.submit()
        with patch.object(self.store, "_execute") as execute:
            self.store._run(job["id"])
        execute.assert_not_called()
        self.assertEqual(self.store.get_job(job["id"])["errorCode"], "DATASET_INVALID")

    def test_process_arguments_are_fixed_offline_and_do_not_inherit_transfer_key(self):
        start = Mock(return_value=Process())
        self.store.popen_factory = start
        with patch.dict(os.environ, {"DOENAAI_TRAINING_TRANSFER_KEY": "private-key", "HF_HUB_OFFLINE": "0"}):
            self.store._execute([self.root / "trial_train.py", "train", "--model", self.store.model_dir], "TRAINING_FAILED")
        arguments, options = start.call_args
        self.assertEqual(arguments[0][0], sys.executable)
        self.assertEqual(arguments[0][1], str(self.root / "trial_train.py"))
        self.assertIs(options["shell"], False)
        self.assertTrue(options["start_new_session"])
        self.assertEqual(options["pass_fds"], (self.store.owner,))
        self.assertEqual(options["stdout"], subprocess.DEVNULL)
        self.assertEqual(options["stderr"], subprocess.DEVNULL)
        self.assertEqual(options["env"]["HF_HUB_OFFLINE"], "1")
        self.assertNotIn("DOENAAI_TRAINING_TRANSFER_KEY", options["env"])

    def test_timeout_terminates_only_the_process_group_started_for_the_job(self):
        process = Process(None)
        self.store.popen_factory = Mock(return_value=process)
        self.store.stage_timeout = 1
        with patch("training_jobs.time.monotonic", side_effect=[0, 100]), patch("training_jobs.os.killpg") as kill:
            with self.assertRaises(TrainingJobError) as error:
                self.store._execute([self.root / "trial_train.py"], "TRAINING_FAILED")
        self.assertEqual(error.exception.code, "JOB_TIMEOUT")
        kill.assert_called_once_with(process.pid, 15)

    def test_failed_subprocess_propagates_only_fixed_error_code(self):
        self.store.popen_factory = Mock(return_value=Process(1))
        result = self.root / "preparation-result.json"
        write_json_atomic(result, {"code": "DATASET_TOO_SMALL", "private": "private-original"})
        with self.assertRaises(TrainingJobError) as error:
            self.store._execute([self.root / "training_job_prepare.py"], "PREPARATION_FAILED", result)
        self.assertEqual(error.exception.code, "DATASET_TOO_SMALL")
        self.assertNotIn("private-original", str(error.exception))

    def test_unstoppable_owned_process_disables_further_execution(self):
        process = Process(None)
        process.wait = Mock(side_effect=subprocess.TimeoutExpired("private-command", 5))
        with patch("training_jobs.os.killpg"):
            with self.assertRaises(subprocess.TimeoutExpired):
                self.store._stop_process(process)
        self.assertTrue(self.store.unsafe_process)
        self.assertFalse(self.store.capabilities()["trainingExecution"])

    def test_active_process_cancellation_terminates_only_owned_group(self):
        process = Process(None)
        self.store.popen_factory = Mock(return_value=process)
        def request_cancel(timeout):
            self.store.cancel_requested.set()
            return True
        with patch.object(self.store.cancel_requested, "wait", side_effect=request_cancel), patch("training_jobs.os.killpg") as kill:
            with self.assertRaises(InterruptedError):
                self.store._execute([self.root / "trial_train.py"], "TRAINING_FAILED")
        kill.assert_called_once_with(process.pid, 15)

    def test_cancelled_queued_work_never_launches_a_process(self):
        job = self.submit()
        self.store.cancel(job["id"], {})
        with patch.object(self.store, "_execute") as execute:
            self.store._run(job["id"])
        execute.assert_not_called()
        self.assertEqual(self.store.get_job(job["id"])["status"], "CANCELLED")

    def test_complete_pipeline_calls_preparation_training_reload_and_returns_only_safe_metrics(self):
        self.verified()
        calls = []

        def execute(arguments, failure, failure_result=None):
            calls.append(list(map(str, arguments)))
            directory = self.store.work_root / job["id"] / "dataset"
            if len(calls) == 1:
                write_json_atomic(directory / "manifest.json", {"stageSampleCounts": {"train": 1, "valid": 1, "test": 0},
                    "details": [{"split": "test", "included": False}]})
            elif len(calls) == 2:
                write_json_atomic(directory / "training-report.json", {"status": "complete", "protectedFilesUnchanged": True,
                    "steps": [{"loss": 1.2}], "validationLossBefore": {"valid": 1.1}, "validationLossAfter": {"valid": 1.0},
                    "adapterTensors": 6, "peakGpuGiB": 17, "private": "do-not-export"})
            else:
                write_json_atomic(directory / "reload-report.json", {"status": "complete", "maxLogitDifference": 0})

        job = self.submit()
        with patch.object(self.store, "_execute", side_effect=execute):
            self.store._run(job["id"])
        self.assertEqual(len(calls), 3)
        self.assertEqual(Path(calls[0][0]).name, "training_job_prepare.py")
        self.assertEqual(calls[1][1], "train")
        self.assertEqual(calls[2][1], "reload")
        result = self.store.get_job(job["id"])
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertFalse(result["metrics"]["independentTestExecuted"])
        self.assertFalse(result["metrics"]["qualityEvaluated"])
        self.assertEqual(result["metrics"]["excludedTestSamples"], 1)
        self.assertIn("독립 시험", result["message"])
        self.assertIn("자르지 않고 제외", result["message"])
        self.assertNotIn("do-not-export", json.dumps(result))
        self.assertNotIn(str(self.root), json.dumps(result))

    def test_worker_state_write_failure_does_not_log_exception_or_private_path(self):
        private = "private-original-and-path"
        with patch.object(self.store, "_run", side_effect=OSError(private)):
            with self.assertLogs("doenaai.training.jobs", level="ERROR") as logs:
                self.store._worker_entry(str(uuid4()))
        self.assertIn("TRAINING_STATE_UNAVAILABLE", str(logs.output))
        self.assertNotIn(private, str(logs.output))

    def test_list_is_bounded_and_public_records_ignore_saved_private_fields(self):
        original = self.submit()
        for _ in range(100):
            value = {**original, "id": str(uuid4()), "status": "SUCCEEDED", "message": "private-value"}
            write_json_atomic(self.store._path(value["id"]), value)
        result = self.store.list_jobs()
        self.assertEqual(len(result["jobs"]), 100)
        self.assertTrue(result["truncated"])
        self.assertNotIn("private-value", json.dumps(result))

    def test_env_loader_accepts_model_path_without_executing_or_overwriting_it(self):
        path = self.root / ".env"
        path.write_text("DOENAAI_TRAINING_MODEL_DIR='/local/model folder'\n")
        values = {}
        load_receiver_env(path, environ=values)
        self.assertEqual(values["DOENAAI_TRAINING_MODEL_DIR"], "/local/model folder")


class TrainingPreparationTests(unittest.TestCase):
    def test_long_independent_test_is_preserved_without_blocking_short_train_and_valid(self):
        values = records()
        values[2]["request"]["sourceSummary"]["changeRequirement"]["title"] = "LONG_FIXTURE"
        original = copy.deepcopy(values)
        plan = automatic_splits(values, LongTokenizer())
        samples, manifest = prepare_trial(values, plan, LongTokenizer(), 18000)
        self.assertEqual(len(samples["train"]), 1)
        self.assertEqual(len(samples["valid"]), 1)
        self.assertEqual(len(samples["test"]), 0)
        self.assertEqual(manifest["truncatedSamples"], 0)
        self.assertEqual(values, original)
        self.assertEqual(plan["test"], [values[2]["caseId"]])

    def test_two_groups_or_fewer_than_two_short_groups_fail_explicitly(self):
        values = records()
        with self.assertRaises(DatasetError) as error:
            automatic_splits(values[:2], Tokenizer())
        self.assertEqual(error.exception.code, "DATASET_TOO_SMALL")
        for value in values[1:]:
            value["request"]["sourceSummary"]["changeRequirement"]["title"] = "LONG_FIXTURE"
        with self.assertRaises(DatasetError) as error:
            automatic_splits(values, LongTokenizer())
        self.assertEqual(error.exception.code, "DATASET_TOO_SMALL")


if __name__ == "__main__":
    unittest.main()
