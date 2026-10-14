"""공개 MLX 모델의 버전을 고정하고 Git 제외 대상인 모델 폴더에 다운로드한다."""

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
# 라이브러리를 불러오기 전에 캐시 경로를 정해 프로젝트 밖에 학습 캐시가 쌓이지 않도록 한다.
os.environ.setdefault("HF_HOME", str(ROOT / ".cache" / "huggingface"))

from huggingface_hub import HfApi, snapshot_download

MODEL_ID = "mlx-community/Qwen3.8-27B-4bit"
MODEL_DIR = ROOT / "models" / "Qwen3.8-27B-4bit"


def main():
    """처음 선택한 모델 revision을 기록하고 재실행 시 같은 버전의 다운로드를 이어간다."""
    manifest_path = MODEL_DIR / "download_manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        revision = manifest["revision"]
    else:
        revision = HfApi().model_info(MODEL_ID).sha
        MODEL_DIR.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps({
            "modelId": MODEL_ID,
            "revision": revision,
            "purpose": "text-only loading and QLoRA compatibility verification",
        }, indent=2) + "\n")
    print(f"모델 다운로드: {MODEL_ID}, 고정 버전: {revision}", flush=True)
    snapshot_download(
        MODEL_ID,
        revision=revision,
        local_dir=MODEL_DIR,
        allow_patterns=["*.json", "*.jinja", "*.safetensors", "*.model", "*.txt", "README.md"],
        max_workers=4,
    )
    print(f"모델 다운로드 완료: {MODEL_DIR}", flush=True)


if __name__ == "__main__":
    main()
