"""전송 중단·재시도·중복 수신에서도 승인 사례를 잃지 않는지 확인한다."""

import json
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_data_tools import case
from transfer_cases import TransferError, receive, remote_paths, send, stage


class TransferCaseTests(unittest.TestCase):
    """실제 SSH 없이도 로컬 영속성 및 수신 확인 계약을 검증한다."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.input = self.root / "reviewed_cases.jsonl"
        self.input.write_text(json.dumps(case(), ensure_ascii=False) + "\n")
        self.outbox = self.root / "outbox"
        self.staging = self.root / "staging"
        self.staging.mkdir()
        self.inbox = self.root / "inbox"

    def test_stage_and_receive_are_durable_and_idempotent(self):
        package = stage(self.input, self.outbox)
        self.assertEqual(package["cases"], 1)
        self.assertTrue(Path(package["package"]).exists())
        staged = self.staging / f'{package["sha256"]}.jsonl.part'
        shutil.copyfile(package["package"], staged)
        received = receive(staged, self.inbox)
        self.assertEqual(received["status"], "received")
        self.assertFalse(staged.exists())
        self.assertEqual(Path(received["file"]).read_bytes(), self.input.read_bytes())
        self.assertTrue(Path(package["package"]).exists())

        shutil.copyfile(package["package"], staged)
        again = receive(staged, self.inbox)
        self.assertEqual(again["status"], "already_received")
        self.assertFalse(staged.exists())
        self.assertEqual(Path(again["file"]).read_bytes(), self.input.read_bytes())

    def test_interrupted_file_is_not_committed_and_can_be_retried(self):
        package = stage(self.input, self.outbox)
        staged = self.staging / f'{package["sha256"]}.jsonl.part'
        staged.write_bytes(self.input.read_bytes()[:30])
        with self.assertRaisesRegex(TransferError, "SHA-256"):
            receive(staged, self.inbox)
        self.assertTrue(staged.exists())
        self.assertFalse(self.inbox.exists())
        shutil.copyfile(package["package"], staged)
        self.assertEqual(receive(staged, self.inbox)["status"], "received")

    def test_manual_copy_is_private_after_first_and_repeated_receive(self):
        package = stage(self.input, self.outbox)
        staged = self.staging / f'{package["sha256"]}.jsonl.part'
        shutil.copyfile(package["package"], staged)
        staged.chmod(0o644)
        received = receive(staged, self.inbox)
        destination = Path(received["file"])
        self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.inbox.stat().st_mode), 0o700)

        destination.chmod(0o644)
        shutil.copyfile(package["package"], staged)
        self.assertEqual(receive(staged, self.inbox)["status"], "already_received")
        self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)

    @unittest.skipUnless(shutil.which("rsync"), "rsync 실행 파일이 필요합니다.")
    def test_local_rsync_uses_supported_resume_flags(self):
        package = stage(self.input, self.outbox)
        staged = self.staging / f'{package["sha256"]}.jsonl.part'
        staged.write_bytes(self.input.read_bytes()[:30])
        result = subprocess.run(["rsync", "-a", "-c", "--partial", package["package"], str(staged)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(receive(staged, self.inbox)["status"], "received")

    def test_invalid_case_is_not_published_to_outbox(self):
        record = case()
        record["humanApproved"] = False
        self.input.write_text(json.dumps(record) + "\n")
        with self.assertRaises(ValueError):
            stage(self.input, self.outbox)
        self.assertEqual(list(self.outbox.iterdir()), [])

    def test_cli_stage_and_receive_return_matching_receipt(self):
        script = Path(__file__).resolve().parents[1] / "transfer_cases.py"
        staged_result = subprocess.run(
            [sys.executable, str(script), "stage", "--input", str(self.input), "--outbox", str(self.outbox)],
            capture_output=True, text=True)
        self.assertEqual(staged_result.returncode, 0, staged_result.stderr)
        package = json.loads(staged_result.stdout)
        partial = self.staging / f'{package["sha256"]}.jsonl.part'
        shutil.copyfile(package["package"], partial)
        received_result = subprocess.run(
            [sys.executable, str(script), "receive", "--staged", str(partial), "--inbox", str(self.inbox)],
            capture_output=True, text=True)
        self.assertEqual(received_result.returncode, 0, received_result.stderr)
        self.assertEqual(json.loads(received_result.stdout)["sha256"], package["sha256"])

    def test_sender_retries_and_requires_matching_receipt(self):
        package = stage(self.input, self.outbox)
        calls = []
        delays = []

        def runner(command, **_):
            calls.append(command)
            if len(calls) == 2:
                return SimpleNamespace(returncode=12, stdout="", stderr="connection lost")
            if command[0] == "ssh" and "receive" in command[-1]:
                receipt = {"sha256": package["sha256"], "cases": 1, "status": "received"}
                return SimpleNamespace(returncode=0, stdout=json.dumps(receipt), stderr="")
            return SimpleNamespace(returncode=0, stdout="", stderr="")

        result = send(package["package"], "user@100.64.1.2", "/Users/user/project",
                      attempts=3, runner=runner, sleeper=delays.append)
        self.assertEqual(result["attempts"], 2)
        self.assertEqual(delays, [1])
        self.assertTrue(Path(package["package"]).exists())
        self.assertEqual([command[0] for command in calls], ["ssh", "rsync", "ssh", "rsync", "ssh"])

    def test_sender_preserves_package_after_all_failures(self):
        package = stage(self.input, self.outbox)

        def runner(_command, **_):
            return SimpleNamespace(returncode=255, stdout="", stderr="secret-error")

        with self.assertRaisesRegex(TransferError, "원본과 전송 사본은 보존") as context:
            send(package["package"], "user@host", "/Users/user/project", attempts=2,
                 runner=runner, sleeper=lambda _: None)
        self.assertNotIn("secret-error", str(context.exception))
        self.assertTrue(Path(package["package"]).exists())

    def test_invalid_receipt_is_retried_without_exposing_contents(self):
        package = stage(self.input, self.outbox)
        valid = {"sha256": package["sha256"], "cases": 1, "status": "received"}
        malformed = [None, [], "private-receipt", 1, {**valid, "cases": True},
                     {**valid, "cases": 1.0}, {**valid, "sha256": "wrong"},
                     {**valid, "status": "pending"}, {**valid, "status": []}]
        for receipt in malformed:
            with self.subTest(receipt=receipt):
                receipts = iter([receipt, valid])
                delays = []

                def runner(command, **_):
                    output = json.dumps(next(receipts)) if command[0] == "ssh" and "receive" in command[-1] else ""
                    return SimpleNamespace(returncode=0, stdout=output, stderr="")

                result = send(package["package"], "user@host", "/Users/user/project", attempts=2,
                              runner=runner, sleeper=delays.append)
                self.assertEqual(result["attempts"], 2)
                self.assertEqual(delays, [1])
                self.assertTrue(Path(package["package"]).exists())

        def runner(command, **_):
            output = json.dumps("private-receipt") if command[0] == "ssh" and "receive" in command[-1] else ""
            return SimpleNamespace(returncode=0, stdout=output, stderr="")

        with self.assertRaises(TransferError) as context:
            send(package["package"], "user@host", "/Users/user/project", attempts=1, runner=runner)
        self.assertNotIn("private-receipt", str(context.exception))
        self.assertTrue(Path(package["package"]).exists())

    def test_lost_receipt_can_be_retried_without_duplicate_commit(self):
        package = stage(self.input, self.outbox)
        receives = 0

        def runner(command, **_):
            nonlocal receives
            if command[0] == "ssh" and "receive" in command[-1]:
                receives += 1
                if receives == 1:
                    return SimpleNamespace(returncode=0, stdout="", stderr="")
                receipt = {"sha256": package["sha256"], "cases": 1, "status": "already_received"}
                return SimpleNamespace(returncode=0, stdout=json.dumps(receipt), stderr="")
            return SimpleNamespace(returncode=0, stdout="", stderr="")

        result = send(package["package"], "user@host", "/Users/user/project", attempts=2,
                      runner=runner, sleeper=lambda _: None)
        self.assertEqual(result["status"], "already_received")
        self.assertEqual(result["attempts"], 2)
        self.assertTrue(Path(package["package"]).exists())

    def test_remote_path_must_not_contain_shell_syntax(self):
        for path in ("relative/repo", "/Users/user/my repo", "/Users/user/../other", "/tmp/repo;bad"):
            with self.assertRaises(TransferError):
                remote_paths(path)


if __name__ == "__main__":
    unittest.main()
