"""GPU와 외부 연결 없이 모델 대응 설정과 대체 실행 차단을 확인한다."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from model_registry import QWEN_BASE, QWEN_SOURCE, QWEN_TRAINER, RegistryError, resolve
from dataset_contract import digest
from training_profiles import GENERIC_TRAINER, QWEN_PROFILE


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
        self.assertEqual(binding.profile, QWEN_PROFILE)
        self.assertEqual(binding.target["digest"], digest({"sourceModel": QWEN_SOURCE, "baseModel": QWEN_BASE,
            "revision": "a" * 40, "trainer": QWEN_TRAINER, "directory": str(self.model)}))
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

    def test_generic_profile_pins_model_structure_and_resume_parent(self):
        profile = {**QWEN_PROFILE, "modelType": "exaone4", "loraKeys": ["self_attn.q_proj", "self_attn.v_proj"]}
        (self.model / "config.json").write_text(json.dumps({"model_type": "exaone4"}))
        entry = {**self.entry(), "trainer": GENERIC_TRAINER, "profile": profile}
        self.configure({"exaone4:latest": entry})
        binding = resolve("exaone4:latest", None, self.registry)
        self.assertEqual(binding.profile, profile)
        self.assertIsNone(binding.resume_job_id)
        previous = binding.target["digest"]
        parent = "11111111-1111-4111-8111-111111111111"
        self.configure({"exaone4:latest": {**entry, "resumeJobId": parent}})
        resumed = resolve("exaone4:latest", None, self.registry)
        self.assertEqual(resumed.resume_job_id, parent)
        self.assertNotEqual(previous, resumed.target["digest"])
        self.configure({"exaone4:latest": {**entry, "profile": {**profile, "loraRank": 8}}})
        self.assertNotEqual(previous, resolve("exaone4:latest", None, self.registry).target["digest"])

    def test_generic_profile_cannot_guess_structure_or_accept_invalid_resume(self):
        (self.model / "config.json").write_text(json.dumps({"model_type": "qwen3_5"}))
        for change in ({}, {"profile": {**QWEN_PROFILE, "modelType": "llama"}},
                       {"profile": {**QWEN_PROFILE, "loraRank": float("nan")}},
                       {"profile": QWEN_PROFILE, "resumeJobId": "../private"},
                       {"profile": QWEN_PROFILE, "resumeJobId": None}):
            self.configure({QWEN_SOURCE: {**self.entry(), "trainer": GENERIC_TRAINER, **change}})
            with self.subTest(change=change), self.assertRaises(RegistryError):
                resolve(QWEN_SOURCE, None, self.registry)

    def test_legacy_qwen_profile_cannot_claim_settings_the_fixed_worker_does_not_use(self):
        (self.model / "config.json").write_text(json.dumps({"model_type": "qwen3_5"}))
        self.configure({QWEN_SOURCE: {**self.entry(), "profile": QWEN_PROFILE}})
        self.assertEqual(resolve(QWEN_SOURCE, None, self.registry).profile, QWEN_PROFILE)
        for change in ({"loraLayers": 2}, {"loraRank": 8}, {"maxSequenceLength": 4096}):
            self.configure({QWEN_SOURCE: {**self.entry(), "profile": {**QWEN_PROFILE, **change}}})
            with self.subTest(change=change), self.assertRaises(RegistryError):
                resolve(QWEN_SOURCE, None, self.registry)
