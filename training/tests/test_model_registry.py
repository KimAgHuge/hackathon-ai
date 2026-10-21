"""GPU와 외부 연결 없이 모델 대응 설정과 대체 실행 차단을 확인한다."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_registry import QWEN_BASE, QWEN_SOURCE, QWEN_TRAINER, RegistryError, resolve


class ModelRegistryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.model = self.root / "qwen"
        self.model.mkdir()
        self.manifest(self.model, QWEN_BASE)
        self.registry = self.root / "models.json"

    def manifest(self, directory, base):
        (directory / "download_manifest.json").write_text(json.dumps({"modelId": base, "revision": "a" * 40}))

    def configure(self, values):
        self.registry.write_text(json.dumps(values))

    def entry(self, directory=None):
        return {"directory": str(directory or self.model), "baseModel": QWEN_BASE, "trainer": QWEN_TRAINER}

    def test_existing_qwen_directory_is_compatible_but_unknown_model_never_falls_back(self):
        binding = resolve(QWEN_SOURCE, self.model)
        self.assertEqual(binding.directory, self.model)
        self.assertEqual(binding.target["baseModel"], QWEN_BASE)
        for model in ("gemma4:26b", "qwen3.8:32b", "qwen", "../private", ""):
            with self.subTest(model=model), self.assertRaises(RegistryError):
                resolve(model, self.model)

    def test_explicit_registry_selects_exact_source_and_verified_base_manifest(self):
        other = self.root / "other"
        other.mkdir()
        self.manifest(other, "local/other-model")
        self.configure({QWEN_SOURCE: self.entry(), "devstral:latest": {
            "directory": str(other), "baseModel": "local/other-model", "trainer": "unavailable"}})
        first = resolve(QWEN_SOURCE, None, self.registry)
        second = resolve("devstral:latest", self.model, self.registry)
        self.assertEqual(first.directory, self.model)
        self.assertEqual(second.directory, other)
        self.assertEqual(second.target["sourceModel"], "devstral:latest")
        self.assertNotIn(str(self.root), json.dumps(second.target))

    def test_target_digest_changes_when_directory_revision_or_trainer_changes(self):
        self.configure({QWEN_SOURCE: self.entry()})
        original = resolve(QWEN_SOURCE, None, self.registry).target["digest"]
        other = self.root / "second"
        other.mkdir()
        self.manifest(other, QWEN_BASE)
        self.configure({QWEN_SOURCE: self.entry(other)})
        self.assertNotEqual(original, resolve(QWEN_SOURCE, None, self.registry).target["digest"])
        self.configure({QWEN_SOURCE: {**self.entry(), "trainer": "unavailable"}})
        self.assertNotEqual(original, resolve(QWEN_SOURCE, None, self.registry).target["digest"])
        self.configure({QWEN_SOURCE: self.entry()})
        (self.model / "download_manifest.json").write_text(json.dumps({"modelId": QWEN_BASE, "revision": "b" * 40}))
        self.assertNotEqual(original, resolve(QWEN_SOURCE, None, self.registry).target["digest"])

    def test_invalid_registry_manifest_and_symlinks_are_refused(self):
        for entry in ({**self.entry(), "command": "unsafe"}, {**self.entry(), "directory": "relative"},
                      {**self.entry(), "baseModel": "different/base"}, {**self.entry(), "trainer": "../unsafe"}):
            self.configure({QWEN_SOURCE: entry})
            with self.subTest(entry=entry), self.assertRaises(RegistryError):
                resolve(QWEN_SOURCE, self.model, self.registry)
        link = self.root / "linked"
        link.symlink_to(self.model)
        self.configure({QWEN_SOURCE: self.entry(link)})
        with self.assertRaises(RegistryError):
            resolve(QWEN_SOURCE, self.model, self.registry)
        self.registry.unlink()
        self.registry.symlink_to(self.model / "download_manifest.json")
        with self.assertRaises(RegistryError):
            resolve(QWEN_SOURCE, self.model, self.registry)
