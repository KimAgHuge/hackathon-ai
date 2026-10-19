"""수신 서버 설정을 안전하게 읽고 기존 환경변수를 보존하는지 확인한다."""

import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from receiver_config import load_receiver_env
import receiver_service


class ReceiverConfigTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / ".env"

    def test_reads_quoted_spaces_comments_and_export_without_loading_other_values(self):
        self.path.write_text('\ufeff# 수신 설정\nDOENAAI_TRAINING_SHARED_DIR="/shared/Mobile Documents" # 경로\n'
                             "export DOENAAI_TRAINING_TRANSFER_KEY='synthetic-key-with-#-inside'\n"
                             "UNRELATED_SETTING=do-not-load\n", encoding="utf-8")
        values = {}
        self.assertTrue(load_receiver_env(self.path, environ=values))
        self.assertEqual(values, {"DOENAAI_TRAINING_SHARED_DIR": "/shared/Mobile Documents",
                                  "DOENAAI_TRAINING_TRANSFER_KEY": "synthetic-key-with-#-inside"})

    def test_does_not_override_process_environment(self):
        self.path.write_text("DOENAAI_TRAINING_TRANSFER_KEY=file-value\nDOENAAI_TRAINING_SHARED_DIR=/shared\n")
        values = {"DOENAAI_TRAINING_TRANSFER_KEY": "existing-environment-value"}
        load_receiver_env(self.path, environ=values)
        self.assertEqual(values["DOENAAI_TRAINING_TRANSFER_KEY"], "existing-environment-value")
        self.assertEqual(values["DOENAAI_TRAINING_SHARED_DIR"], "/shared")

    def test_missing_optional_file_keeps_environment_and_explicit_file_fails_safely(self):
        values = {}
        self.assertFalse(load_receiver_env(self.path, environ=values))
        self.assertEqual(values, {})
        with self.assertRaises(ValueError):
            load_receiver_env(self.path, environ=values, required=True)

    def test_invalid_syntax_never_echoes_secret_or_partially_applies_settings(self):
        for content in ('DOENAAI_TRAINING_TRANSFER_KEY="private-customer-value\n',
                        'DOENAAI_TRAINING_TRANSFER_KEY=private-customer-value\nnot-an-assignment\n',
                        'DOENAAI_TRAINING_SHARED_DIR=/shared/unquoted space\n'):
            self.path.write_text(content)
            values = {}
            with self.assertRaises(ValueError) as error:
                load_receiver_env(self.path, environ=values)
            self.assertNotIn("private-customer-value", str(error.exception))
            self.assertEqual(values, {})

    def test_does_not_execute_shell_or_expand_environment_references(self):
        marker = Path(self.temporary.name) / "must-not-exist"
        self.path.write_text(f"DOENAAI_TRAINING_TRANSFER_KEY='$(touch {marker})'\n"
                             "DOENAAI_TRAINING_SHARED_DIR='${UNRELATED_SETTING}/shared'\n")
        values = {}
        load_receiver_env(self.path, environ=values)
        self.assertFalse(marker.exists())
        self.assertEqual(values["DOENAAI_TRAINING_SHARED_DIR"], '${UNRELATED_SETTING}/shared')

    def test_rejects_large_or_non_utf8_file(self):
        for data in (b"x" * 65537, b"\xff\xfe"):
            self.path.write_bytes(data)
            with self.assertRaises(ValueError):
                load_receiver_env(self.path, environ={})

    def test_hash_inside_unquoted_value_is_not_silently_removed(self):
        self.path.write_text("DOENAAI_TRAINING_TRANSFER_KEY=synthetic#key # 주석\n")
        values = {}
        load_receiver_env(self.path, environ=values)
        self.assertEqual(values["DOENAAI_TRAINING_TRANSFER_KEY"], "synthetic#key")

    def test_main_automatically_loads_training_env_or_repository_fallback(self):
        root = Path(self.temporary.name) / "training"
        root.mkdir()
        for path in (root / ".env", root.parent / ".env"):
            path.write_text('DOENAAI_TRAINING_SHARED_DIR="/synthetic/shared folder"\n'
                            'DOENAAI_TRAINING_TRANSFER_KEY=synthetic-transfer-key-long-enough-for-test\n')
            with patch.object(receiver_service, "ROOT", root), \
                    patch.object(receiver_service.os, "environ", {}), \
                    patch.object(receiver_service, "ReceiverStore") as store, \
                    patch.object(receiver_service, "serve") as serve, \
                    patch.object(receiver_service.logging, "basicConfig"), \
                    patch.object(sys, "argv", ["receiver_service.py"]):
                receiver_service.main()
                store.assert_called_once_with("/synthetic/shared folder")
                self.assertEqual(serve.call_args.args[1], "synthetic-transfer-key-long-enough-for-test")
            path.unlink()


if __name__ == "__main__":
    unittest.main()
