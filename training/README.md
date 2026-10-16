# training

M4 Max MacBook에서 사용하는 MLX-LM / LoRA 학습 환경입니다.
AI 서버의 Python 환경과 별도로 `training/.venv`를 사용합니다.

## 준비된 환경

2026-10-14 확인: Apple M4 Max, 통합 메모리 64 GiB, macOS 26.6.2.
Python 3.12.15 (arm64), MLX 0.32.3, MLX-LM 0.32.0.
Metal GPU 연산과 자동 미분 검증을 통과했습니다.
Qwen 모델별 검증 결과는 `QWEN_COMPATIBILITY.md`에 별도로 기록합니다.

저장소 루트에서 실행합니다.

```bash
source training/.venv/bin/activate
python training/check_environment.py
mlx_lm.lora --help
```

Codex 샌드박스에서는 Metal GPU 접근이 차단될 수 있습니다.
그 경우 GPU 검증과 학습은 승인된 샌드박스 외부 명령 또는 일반 터미널에서 실행합니다.

## 다른 맥에서 같은 환경 만들기

Apple Silicon과 Python 3.12가 설치된 경우:

```bash
python3.12 -m venv training/.venv
training/.venv/bin/python -m pip install -r training/requirements-lock.txt
training/.venv/bin/python training/check_environment.py
```

이 맥에서는 기존 Python/Homebrew를 변경하지 않고 로컬 uv와 Python을 설치했습니다.
같은 방식으로 준비하려면 저장소 루트에서:

```bash
python3 -m pip install --no-cache-dir --target training/.tools uv==0.13.0
training/.tools/bin/uv python install cpython-3.12.15-macos-aarch64-none --install-dir training/.python --no-bin --cache-dir training/.cache/uv
training/.tools/bin/uv venv training/.venv --python training/.python/cpython-3.12.15-macos-aarch64-none/bin/python3.12 --cache-dir training/.cache/uv
training/.tools/bin/uv pip install --python training/.venv/bin/python --cache-dir training/.cache/uv -r training/requirements-lock.txt
```

`requirements.txt`는 최초 설치용이고, `requirements-lock.txt`는 검증한 전체 패키지 버전입니다.
로컬 Python을 삭제하면 해당 Python으로 만든 가상환경도 다시 만들어야 합니다.

## 모델과 데이터 준비 후 학습

1. 운영 모델과의 호환성, 라이선스, 메모리 사용량을 확인해 기반 모델을 선택합니다.
   Ollama의 GGUF 파일을 그대로 LoRA 학습 입력으로 쓰지 않고 MLX-LM 호환 모델을 준비합니다.
2. 사람이 승인한 질문·근거·정답을 비식별화하고 프로젝트 단위로 학습/검증/시험 세트를 분리합니다.
3. `training/datasets/doenaai/`에 `train.jsonl`, `valid.jsonl`, `test.jsonl`을 준비합니다.
   각 줄은 `messages` 배열에 user/assistant 대화를 담는 JSON 객체입니다.
4. 아래는 소량 데이터로 실행 여부를 확인하는 시작 명령입니다. 실제 품질 평가 설정은 별도로 정합니다.

```bash
source training/.venv/bin/activate
export HF_HOME="$PWD/training/.cache/huggingface"
mlx_lm.lora \
  --model ./training/models/base-model \
  --train \
  --data ./training/datasets/doenaai \
  --adapter-path ./training/adapters/smoke \
  --batch-size 1 \
  --num-layers 4 \
  --max-seq-length 1024 \
  --iters 10 \
  --mask-prompt
```

`base-model` 경로에는 선택한 모델이 있어야 합니다.
양자화된 호환 모델을 사용하면 QLoRA 방식으로 학습합니다.
실제 학습 후 고정 시험 세트로 DB 판단 정확도, 잘못된 변경 제안, 한국어 품질과 지연시간을 비교합니다.
Mac Studio/Ollama 배포에는 모델별 변환·호환성 검증이 추가로 필요합니다.

`export_dataset.py`는 승인된 로컬 사례를 검사해 JSONL로 변환합니다.
승인 화면의 자동 전달을 사용할 경우 맥북에서 `receiver_service.py`를 실행하면
공유 폴더에 사례가 도착할 때까지 대기하고 검증 후 `datasets/incoming`에 확정합니다.
필요한 환경변수·수신 상태·보안 조건은 [데이터·평가 안내](DATA_WORKFLOW.md)의
자동 전달 절을 먼저 확인하세요. 파일 수신은 학습 실행이 아닙니다.
`evaluate_model.py`는 같은 시험 세트의 원본 모델·학습 어댑터 답변을 생성하고 비교합니다.
전달 양식과 명령은 [데이터·평가 안내](DATA_WORKFLOW.md)를 참고합니다.
결과 승인 API와 승인 결과의 학습 사례 초안 다운로드는 구현됐습니다.
초안은 사람이 보완·비식별화한 뒤 `finalize_draft.py`로 확정합니다.
원격 DB의 승인 사례 일괄 자동 추출과 `train_lora.py` 자동화는 아직 구현하지 않았습니다.
확정 사례의 전송·수신 검증은 `transfer_cases.py`를 사용합니다.
실제 학습은 공식 `mlx_lm.lora` 명령을 사용합니다.
모델, 데이터셋, 캐시, adapter, 체크포인트는 Git에서 제외합니다.
비밀키나 고객 원문은 소스·문서·학습 데이터에 넣지 않습니다.

공식 안내: https://github.com/ml-explore/mlx-lm/blob/main/mlx_lm/LORA.md

## Qwen3.8 호환성 시험

저장소 루트의 일반 터미널에서 실행합니다. 모델 다운로드 시 revision을
`training/models/Qwen3.8-27B-4bit/download_manifest.json`에 고정합니다.
이미 받은 파일은 다시 사용하므로 중단된 다운로드도 같은 명령으로 이어갑니다.

```bash
training/.venv/bin/python training/download_model.py
training/.venv/bin/python training/verify_qwen_compatibility.py load
training/.venv/bin/python training/verify_qwen_compatibility.py train
training/.venv/bin/python training/verify_qwen_compatibility.py reload
```

각 GPU 시험은 별도 프로세스에서 실행해 모델이 동시에 두 번 로드되지 않도록 합니다.
`load`는 thinking을 끈 짧은 텍스트 추론, `train`은 공식 ChatDataset의 기본
chat template과 prompt masking을 이용한 마지막 4개 층·rank 4의 QLoRA 2회 업데이트,
`reload`는 새 프로세스에서 저장된 adapter와 logits를 대조합니다.
원본 양자화 가중치는 수정하지 않습니다.

이 시험의 합성 대화는 실행 확인 전용이며 업무 학습 데이터가 아닙니다.
시험 adapter는 `training/adapters/compatibility-only/`에 저장하고 운영에 배포하지 않습니다.
상세 측정값은 `training/outputs/qwen-compatibility/`에 저장합니다.
짧은 입력의 성공은 대규모 입력의 학습·모델 정확도·Ollama 배포 성공을 의미하지 않습니다.
