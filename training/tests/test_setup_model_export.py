"""실제 다운로드·설치·빌드 없이 고정 도구 준비 명령의 경계를 확인한다."""

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from setup_model_export import LLAMA_CPP_REVISION, SetupError, configuration, install


class ModelExportSetupTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.directory = self.root / "separate-converter-tools"
        self.commands = []

    def execute(self, arguments, **options):
        self.commands.append(arguments)
        if arguments[:2] == ["cmake", "--build"]:
            quantizer = self.directory / "llama.cpp/build/bin/llama-quantize"
            quantizer.parent.mkdir(parents=True)
            quantizer.write_bytes(b"synthetic-pinned-quantizer")
        return LLAMA_CPP_REVISION if arguments[-2:] == ["rev-parse", "HEAD"] else None

    def test_setup_uses_fixed_official_revision_and_separate_venv_then_returns_three_values(self):
        old_environment = self.root / "training-venv"
        old_environment.mkdir()
        marker = old_environment / "unchanged"
        marker.write_text("기존 학습 환경")
        with patch("setup_model_export.sys.platform", "darwin"), \
             patch("setup_model_export.shutil.which", return_value="/synthetic/tool"), redirect_stdout(io.StringIO()):
            values = install(self.directory, Path(sys.executable), runner=self.execute)
        self.assertEqual(len(values), 3)
        self.assertEqual(values, configuration(self.directory, verify=False))
        self.assertTrue(any(command[-1] == LLAMA_CPP_REVISION for command in self.commands))
        self.assertTrue(any("venv" in command and str(self.directory / "converter-venv") in command for command in self.commands))
        self.assertTrue(any("-DGGML_METAL=OFF" in command for command in self.commands))
        self.assertEqual(marker.read_text(), "기존 학습 환경")
        self.assertFalse((self.root / ".env").exists())
        self.assertEqual(set(json.loads((self.directory / "setup.json").read_text())),
                         {"formatVersion", "llamaCppRevision", "quantizerSha256"})

    def test_existing_directory_is_preserved_without_running_commands(self):
        self.directory.mkdir()
        marker = self.directory / "keep"
        marker.write_text("기존 파일")
        with self.assertRaises(SetupError):
            install(self.directory, Path(sys.executable), runner=self.execute)
        self.assertEqual(marker.read_text(), "기존 파일")
        self.assertEqual(self.commands, [])

    def test_wrong_revision_never_installs_python_dependencies_or_marks_ready(self):
        def wrong(arguments, **options):
            self.commands.append(arguments)
            return "0" * 40 if options.get("capture") else None
        with patch("setup_model_export.sys.platform", "darwin"), \
             patch("setup_model_export.shutil.which", return_value="/synthetic/tool"), redirect_stdout(io.StringIO()):
            with self.assertRaises(SetupError):
                install(self.directory, Path(sys.executable), runner=wrong)
        self.assertFalse(any("pip" in command or "venv" in command for command in self.commands))
        self.assertFalse((self.directory / "setup.json").exists())
