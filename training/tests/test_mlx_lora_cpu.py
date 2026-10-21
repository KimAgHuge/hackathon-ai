"""명시적으로 요청할 때만 작은 임의 모델 두 종류의 CPU 학습·이어 학습을 확인한다."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TRAINING = Path(__file__).resolve().parents[1]
CHILD = r'''
import importlib
import json
import sys
from dataclasses import asdict
from pathlib import Path

import mlx.core as mx
mx.set_default_device(mx.cpu)
assert mx.default_device() == mx.cpu
sys.path.insert(0, sys.argv[1])
root, action = Path(sys.argv[2]), sys.argv[3]
if action == "prepare":
    import mlx.nn as nn
    from mlx.utils import tree_flatten
    from tokenizers import Tokenizer
    from tokenizers.models import WordLevel
    from tokenizers.pre_tokenizers import WhitespaceSplit
    from transformers import PreTrainedTokenizerFast
    from dataset_contract import prompt_contract
    from trial_dataset import tokenize_messages
    kind = sys.argv[4]
    directory = root / kind
    model_dir = directory / "model"
    model_dir.mkdir(parents=True)
    (directory / "adapters").mkdir()
    module = importlib.import_module("mlx_lm.models." + kind)
    arguments = {"model_type": kind, "hidden_size": 32, "num_hidden_layers": 1,
                 "intermediate_size": 64, "num_attention_heads": 4,
                 "num_key_value_heads": 2, "rms_norm_eps": 1e-5,
                 "vocab_size": 64, "head_dim": 8, "max_position_embeddings": 64,
                 "rope_theta": 10000.0, "tie_word_embeddings": kind == "llama"}
    if kind == "exaone4":
        arguments.update(rope_scaling=None, sliding_window=None, sliding_window_pattern=None)
    mx.random.seed(17)
    model = module.Model(module.ModelArgs(**arguments))
    nn.quantize(model, group_size=32, bits=4)
    mx.eval(model.parameters())
    mx.save_safetensors(str(model_dir / "model.safetensors"), dict(tree_flatten(model.parameters())))
    config = asdict(model.args)
    config.update(quantization={"group_size": 32, "bits": 4, "mode": "affine"}, eos_token_id=2,
                  layer_types=["full_attention"])
    (model_dir / "config.json").write_text(json.dumps(config))
    identity = {"modelId": "local/tiny-" + kind, "revision": "a" * 40}
    (model_dir / "download_manifest.json").write_text(json.dumps(identity))
    vocabulary = ["[unk]", "[pad]", "[eos]", "[bos]", "[system]", "[user]", "[assistant]",
                  "근거", "질문", "정답", "추가", "검증"]
    vocabulary += ["word" + str(i) for i in range(64 - len(vocabulary))]
    backend = Tokenizer(WordLevel({word: index for index, word in enumerate(vocabulary)}, unk_token="[unk]"))
    backend.pre_tokenizer = WhitespaceSplit()
    template = "{% for message in messages %}{{ '[' + message['role'] + '] ' + message['content'] + ' ' }}{% if message['role'] == 'assistant' %}{{ '[eos] ' }}{% endif %}{% endfor %}{% if add_generation_prompt %}{{ '[assistant] ' }}{% endif %}"
    tokenizer = PreTrainedTokenizerFast(tokenizer_object=backend, unk_token="[unk]", pad_token="[pad]",
                                       eos_token="[eos]", bos_token="[bos]", chat_template=template)
    tokenizer.save_pretrained(model_dir)
    profile = {"modelType": kind, "loraLayers": 1, "loraRank": 4, "loraScale": 8.0,
               "loraDropout": 0.0, "loraKeys": ["self_attn.q_proj", "self_attn.v_proj"],
               "maxSequenceLength": 64, "batchSize": 1, "learningRate": 0.001,
               "gradientCheckpointing": True}
    (directory / "profile.json").write_text(json.dumps(profile))
    for name in ("first", "resumed"):
        dataset = directory / name
        dataset.mkdir()
        for split in ("train", "valid", "test"):
            values = []
            if split != "test":
                messages = [{"role": "system", "content": "근거"},
                            {"role": "user", "content": "질문 근거" if split == "train" else "질문 검증"},
                            {"role": "assistant", "content": "정답 추가"}]
                tokens, offset = tokenize_messages(tokenizer, messages)
                values = [{"messages": messages, "tokens": tokens, "offset": offset}]
            (dataset / (split + ".jsonl")).write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in values))
        manifest = {"contractSha256": prompt_contract()[1], "model": identity, "maxTokens": 64,
                    "stageSampleCounts": {"train": 1, "valid": 1, "test": 0}}
        (dataset / "manifest.json").write_text(json.dumps(manifest))
    print(json.dumps({"prepared": kind, "device": "cpu", "modelParameters": sum(v.size for _, v in tree_flatten(model.parameters()))}))
else:
    import mlx_lora_train as worker
    kind = sys.argv[4]
    worker.ROOT = root / kind
    sys.argv = ["mlx_lora_train.py", *sys.argv[5:]]
    worker.main()
    assert mx.default_device() == mx.cpu
'''


@unittest.skipUnless(os.environ.get("DOENAAI_RUN_MLX_CPU_SMOKE") == "1", "작은 CPU 모델 실행은 명시적으로 선택해야 합니다.")
class CommonMLXCPUSmokeTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix="doenaai-generic-smoke-", dir="/private/tmp"))
        if os.environ.get("DOENAAI_KEEP_MLX_CPU_SMOKE") != "1":
            self.addCleanup(shutil.rmtree, self.root)

    def child(self, action, kind, *arguments):
        environment = {**os.environ, "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1",
                       "TOKENIZERS_PARALLELISM": "false", "WANDB_MODE": "disabled"}
        result = subprocess.run([sys.executable, "-c", CHILD, str(TRAINING), str(self.root), action, kind,
                                 *map(str, arguments)], env=environment, capture_output=True, text=True, timeout=90)
        self.assertEqual(result.returncode, 0, result.stderr[-6000:] + result.stdout[-2000:])
        return result.stdout

    def test_two_quantized_architectures_train_resume_and_reload_on_cpu(self):
        sys.path.insert(0, str(TRAINING))
        from model_delivery import execution_summary

        summary = []
        for kind in ("llama", "exaone4"):
            with self.subTest(model=kind):
                self.child("prepare", kind)
                directory = self.root / kind
                model, profile = directory / "model", directory / "profile.json"
                parent = directory / "adapters/trial-first"
                new = directory / "adapters/trial-resumed"
                def execute(phase, dataset, adapter, resume=None):
                    arguments = [phase, "--dataset", dataset, "--adapter", adapter, "--model", model,
                                 "--profile-file", profile, "--iters", "2"]
                    if resume is not None:
                        arguments += ["--resume-adapter", resume]
                    self.child("worker", kind, *arguments)
                execute("train", directory / "first", parent)
                execute("reload", directory / "first", parent)
                originals = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in parent.iterdir() if p.is_file()}
                execute("train", directory / "resumed", new, parent)
                execute("reload", directory / "resumed", new)
                self.assertEqual(originals, {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in parent.iterdir() if p.is_file()})
                first = json.loads((directory / "first/training-report.json").read_text())
                resumed = json.loads((directory / "resumed/training-report.json").read_text())
                first_reload = json.loads((directory / "first/reload-report.json").read_text())
                resumed_reload = json.loads((directory / "resumed/reload-report.json").read_text())
                self.assertEqual(resumed["parentAdapterSha256"], first["adapterSha256"])
                self.assertNotEqual(resumed["adapterSha256"], first["adapterSha256"])
                self.assertEqual(len(first["steps"]), 2)
                self.assertEqual(len(resumed["steps"]), 2)
                self.assertTrue(first["protectedFilesUnchanged"] and resumed["protectedFilesUnchanged"])
                for adapter, dataset in ((parent, directory / "first"), (new, directory / "resumed")):
                    _, identity, delivery = execution_summary(adapter, dataset)
                    self.assertEqual(identity["modelId"], "local/tiny-" + kind)
                    self.assertEqual(delivery["qualityEvaluation"], "NOT_EVALUATED")
                    self.assertFalse(delivery["productionApproved"])
                summary.append({"modelType": kind, "device": "cpu", "quantization": "affine-4bit-group32",
                    "firstLoss": first["steps"][0]["loss"], "lastLoss": first["steps"][-1]["loss"],
                    "resumedFirstLoss": resumed["steps"][0]["loss"], "resumedLastLoss": resumed["steps"][-1]["loss"],
                    "reloadDifference": first_reload["maxLogitDifference"],
                    "resumedReloadDifference": resumed_reload["maxLogitDifference"],
                    "adapterTensors": first["adapterTensors"], "parentPreserved": True})
        (self.root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"cpuSmokeSummary": summary, "directory": str(self.root)}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    unittest.main()
