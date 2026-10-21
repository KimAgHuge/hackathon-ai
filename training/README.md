# AI 학습 서버 · training

검토한 분석 사례로 모델을 학습·평가하는 MLX-LM / LoRA 기반 AI 학습 서버 환경입니다.
현재 기준 장비는 MacBook Pro M4 Max이며, 서버 역할의 명칭은 장비와 구분합니다.
2026-10-19 사용자 전달 사양은 CPU 16코어·GPU 40코어·통합 메모리 64GB입니다.
AI 분석/추론 서버의 Python 환경과 별도로 `training/.venv`를 사용합니다.

2026-10-20 소스 연결·검토 범위 입력 보완으로 학습 메시지 형식은 `messageFormatVersion=3`입니다.
일반 단계는 테이블 개요와 소스 연결, DB 단계는 상세 구조와 같은 소스 연결을 사용합니다.
운영과 학습이 `ai-server/app/services/source_context.py`의 변환을 공유하므로 저장소 전체를 함께 업데이트합니다.
기존 승인 원본을 고치거나 삭제하지 말고, 새 계약으로 학습·평가 자료를 다시 준비합니다.
운영의 최종 정리는 추가 추론 단계이며 현재 학습 사례는 기존 일반·DB 두 단계 정답을 유지합니다.
자동 초안의 일반 정답 후보가 최종 승인 수정본에서 만들어졌다면 컬럼·인덱스 상세 사실이 섞였는지 확인합니다.
일반 단계 입력에는 테이블 개요만 있으므로 그 입력으로 답할 수 있는 내용으로 검토하고, 상세 구조 판단은 DB 정답에 둡니다.
형식·파일·테이블 참조 검사만으로 문장 전체의 단계별 근거 일치가 보장되지는 않습니다.
현재 파일·줄 근거 검사는 원본 입력을 기준으로 하므로, 실제 학습 입력의 참조 수·글자 수 제한으로
빠진 파일을 인용한 정답도 검사에 통과할 수 있습니다. 학습 전에 변환된 단계별 메시지에도 인용 근거가
포함돼 있는지 확인합니다. 이 범위 차이의 자동 차단은 추가 보완 대상이며 검사 통과만으로 학습을 확정하지 않습니다.
일반 정답의 선택 항목 `databaseReviewPlan`이 있으면 운영과 같은 대상·파일 근거 검사를 적용하고,
DB 학습 입력도 해당 범위와 직접 연결된 외래키 이웃을 사용합니다. 없는 기존 사례는 전체 범위를 사용합니다.
범위 선택과 구조 점검은 `ai-server/app/services/database_scope.py`를 함께 사용합니다.
새 계획이 있는 사례를 전송하려면 수신 서버가 `DATABASE_REVIEW_PLAN_V1` 기능을 제공해야 합니다.
학습 서버도 저장소 전체를 받은 뒤 수신 서비스를 재시작하고 기능 확인을 마친 후 새 사례를 보냅니다.
기존 파일에 계획을 임의로 채우지 않으며 형식이 바뀐 학습·평가 자료는 새 계약으로 다시 준비합니다.
현재 두 단계 학습 형식은 운영의 부분 검토·재시도·전체 확대 순서 전체를 학습하는 실행 기록은 아닙니다.
전체 확대 결과가 원래 우선 계획의 범위 밖 테이블을 포함하면 근거 검사가 전송을 막을 수 있으므로,
검토자가 실제 사용할 일반 정답의 계획과 DB 정답 범위를 함께 확인해야 합니다. 검사 통과를 위해 근거를 지우지 않습니다.

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

평가된 모델도 SSH 직접 전송 대신 공유 NAS 역할의 iCloud Drive에 게시하고,
AI 분석/추론 서버가 가져와 검증한 뒤 적용하는 방향을 채택했습니다.
[모델 배포 방침](MODEL_DELIVERY.md)에 중단 시 재시도, 배포 승인, 기존 모델 유지와
되돌리기 기준을 정리했습니다. 자동 모델 배포 기능이 구현됐다는 뜻은 아닙니다.

`export_dataset.py`는 승인된 로컬 사례를 검사해 JSONL로 변환합니다.
승인 화면의 자동 전달을 사용할 경우 AI 학습 서버에서 `receiver_service.py`를 실행하면
공유 폴더에 사례가 도착할 때까지 대기하고 검증 후 `datasets/incoming`에 확정합니다.
필요한 환경변수·수신 상태·보안 조건은 [데이터·평가 안내](DATA_WORKFLOW.md)의
자동 전달 절을 먼저 확인하세요. 파일 수신은 학습 실행이 아닙니다.

수신 서버는 실행 위치와 관계없이 `training/.env`를 자동으로 읽습니다. 없으면 저장소 루트의 `.env`를
확인하며 이미 설정한 환경변수가 우선합니다. 처음에는 `training/.env.example`을 `training/.env`로
복사해 AI 학습 서버에서 보이는 공유 폴더의 절대 경로와 두 서버가 공유하는 전송 키를 입력하세요.
공백이 있는 경로는 따옴표로 감싸고 `.env`는 커밋하지 않습니다. 추가 패키지 설치나 `source .env`는 필요 없습니다.

```bash
training/.venv/bin/python training/receiver_service.py --host 0.0.0.0 --port 8011
```

다른 파일을 쓰려면 `--env-file <설정 파일 경로>`를 지정합니다. `.env` 변경은 수신 서버 재시작 후 적용됩니다.
터미널에는 시작·기존 수신함 상태·알림 접수·파일 대기·도착·검증 완료·안전한 실패 코드가 표시됩니다.
비밀키·서명·데이터 원문·고객 정보·전체 로컬 경로는 출력하지 않습니다. 같은 파일의 같은 대기 상태는 반복 출력하지 않습니다.
`VERIFIED`는 자료 수신·검증 완료이며 학습 시작이나 모델 적용 완료가 아닙니다.

새 DB 변경안은 인덱스 추가 `ADD_INDEX`와 필수 여부 `necessity`를 사용합니다.
`REQUIRED`는 필수 변경, `RECOMMENDED`는 선택적인 개선이며 권장 사항만으로 변경 필수를 확정하지 않습니다.
같은 컬럼·테이블의 중복 추가는 학습 정답으로 받지 않습니다. 구형 사례에 없는 구분값은 임의로 채우지 않습니다.
이 형식의 전송에는 `/api/training/capabilities`의 `DATABASE_CHANGE_SEMANTICS_V2` 지원이 필요합니다.
저장소를 업데이트하고 수신 서비스를 재시작해야 하며, 지원이 확인되지 않으면 게시 전에 차단합니다.
이전 승인 파일은 삭제하지 않습니다. 새 프롬프트·스키마로 계약 해시가 바뀌므로 학습·비교 평가 자료는
같은 계약으로 다시 검증·내보내기한 뒤 사용합니다. 수신만으로 학습이 실행되지는 않습니다.

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
