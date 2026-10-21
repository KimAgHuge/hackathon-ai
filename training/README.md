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
아래 `trial_dataset.py`의 일반 단계 시험 준비에는 실제 메시지의 인용 근거를 다시 검사하는
차단 규칙을 적용했습니다. 기존 전체 데이터 변환·전송 경로의 보완 여부와 구분합니다.
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
되돌리기 기준을 정리했습니다. 아래 `model_delivery.py`로 시험 어댑터의 패키지 준비·게시·수동 검증은
실행할 수 있으며, 자동 가져오기·Ollama 등록·운영 전환은 아직 구현하지 않았습니다.

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
웹에서 명시적으로 요청하는 제한된 시험 학습은 아래 작업 API와 별도 워커로 지원합니다.
확정 사례의 전송·수신 검증은 `transfer_cases.py`를 사용합니다.
일반적인 학습 실행은 공식 `mlx_lm.lora` 명령을 사용하며, 아래 승인 자료 시험은 별도 도구를 사용합니다.
모델, 데이터셋, 캐시, adapter, 체크포인트는 Git에서 제외합니다.
비밀키나 고객 원문은 소스·문서·학습 데이터에 넣지 않습니다.

## 웹 요청으로 제한된 시험 학습 실행

수신 서버의 `training/.env`에 `DOENAAI_TRAINING_MODEL_DIR`을 설정하면 검증된 로컬
`qwen3_5` MLX 모델과 학습 환경을 확인한 뒤 작업 API를 엽니다. 빈 설정이나 호환되지 않는
환경에서는 기존 자료 수신은 계속하고 `trainingExecution=false`와 한국어 이유를 반환합니다.
Mac Studio의 추론 모델 경로가 아니라 **맥북 학습 서버의 로컬 모델 절대 경로**입니다.
설정 반영에는 수신 서비스 재시작이 필요하며 이 문서의 변경만으로 실행 중 서버가 바뀌지는 않습니다.

모든 요청은 기존 전송 API와 같은 `X-Transfer-Timestamp`·`X-Transfer-Signature` 서명을
사용합니다. 브라우저에서 키를 보내지 않고 권한을 확인한 백엔드가 서명합니다.

| 요청 | 결과 |
|---|---|
| `GET /api/training/capabilities` | 기존 검증 기능과 `trainingExecution`, `trainingExecutionReason` |
| `POST /api/training/jobs` | `{jobId: UUID, iterations: 1부터 20까지의 정수}`만 받고 202로 접수 |
| `GET /api/training/jobs` | 최근 100개 작업과 `limit`, `truncated` |
| `GET /api/training/jobs/{UUID}` | 상태·진행률·시각·고정 안내·허용한 측정값만 반환 |
| `POST /api/training/jobs/{UUID}/cancel` | 빈 객체 `{}`로 취소 요청, 실제 종료는 상태 조회로 확인 |

같은 작업 ID와 같은 반복 횟수는 기존 결과를 반환하고 재학습하지 않습니다. 같은 ID의 다른
조건과 동시 작업은 409로 거부합니다. 상태는 `PENDING → PREPARING → TRAINING → VERIFYING`
뒤 `SUCCEEDED`, `FAILED`, `CANCELLED` 중 하나로 확정합니다. 진행률은 단계 기준이며 정밀한
완료 시간 예측이 아닙니다. 원문·키·경로·자식 출력·예외 원문은 API나 로그에 보내지 않습니다.

워커는 수신 기록이 `VERIFIED`인 파일만 해시·크기·승인 계약을 다시 검사해 작업별 사본으로
만듭니다. 사용자 경로·명령·셸을 받지 않습니다. 최대 100개 파일·32 MiB를 준비하며, 원본은
변경하지 않습니다. `training_job_prepare.py`가 `trial_dataset.py`의 규칙으로 원 요구사항을
세 분할에 묶습니다. 길이를 확인해 서로 다른 짧은 두 그룹을 학습·검증에 두고 나머지는
시험 분할로 보관합니다. 총 세 그룹과 18,000토큰 이내의 유효한 학습·검증 자료가 필요합니다.
긴 시험 자료는 근거나 정답을 자르지 않고 제외합니다. DB 단계 정답은 학습하지 않습니다.

이후 고정된 `trial_train.py train`과 별도 `reload` 명령을 순서대로 실행합니다. 마지막 층
MLP만 제한적으로 학습하며 모델을 다운로드하지 않습니다. 각 단계는 1시간 후 중단하고
취소 시 이 작업이 만든 프로세스 묶음만 종료합니다. 새 산출물은
`training/outputs/web-jobs/{UUID}/`와 `training/adapters/trial-web-{UUID}/`에 보존합니다.

영속 기록과 운영체제 파일 잠금으로 한 작업만 실행합니다. 자식도 잠금을 보유하므로 수신
서비스가 강제 종료돼도 기존 자식이 끝나기 전에 새 GPU 작업을 시작하지 않습니다. 재시작 후
이전 작업은 잠금을 다시 확보한 시점에 `JOB_INTERRUPTED`로 실패 처리하며 자동 재개하지 않습니다.
기존 CLI 학습 명령은 이 웹 작업 잠금을 공유하지 않으므로 웹 작업 중 별도 GPU 학습을 실행하지 마세요.

`SUCCEEDED`는 학습·저장·재로딩 성공만 뜻합니다. `metrics.independentTestExecuted=false`와
`qualityEvaluated=false`를 반환하며 `excludedTestSamples`로 제외한 시험 자료 수를 알립니다.
독립 생성 시험, 업무 정답 품질 평가, 운영 승인과 Mac Studio의 모델 전환은 이 워커가 수행하지 않습니다.

## 단일 프로젝트의 짧은 승인 자료 시험

`trial_dataset.py`는 같은 프로젝트에서도 `changeRequirement.requirementId`를 기준으로
원 요구사항별 학습·검증·시험 배정을 지원합니다. 같은 원 요구사항의 수정 버전과
후속 질문은 대표 사례와 같은 분할에 들어가며, 다른 분할로 중복 배정하면 거부합니다.
원 요구사항 ID가 없거나 사례·분석 입력이 중복돼도 거부합니다. 배정 파일의 각 목록에는
실제 수신한 `caseId`를 넣습니다.

```json
{
  "train": ["analysis-학습용-사례-ID"],
  "valid": ["analysis-검증용-사례-ID"],
  "test": ["analysis-시험용-사례-ID"]
}
```

수신 자료 원본은 변경하지 않습니다. 현재 로컬 일반 분석과 같은 테이블 개요 변환을
적용하고 JSON 정답 전체를 보존합니다. 이번 시험 도구는 `thinking=false`를 명시하고,
전체 대화에서 프롬프트와 정답의 토큰 경계가 같은지 확인합니다. 길이 상한을 넘으면
근거나 정답을 자르는 대신 해당 자료를 보관합니다. DB 단계는 운영 분할 호출에 대응하는
단계별 정답이 없어 시험 학습에서 제외합니다. 이는 두 단계 전체의 업무 학습 완료가 아닙니다.

```bash
training/.venv/bin/python training/trial_dataset.py \
  --splits training/outputs/새시험/splits.json \
  --output training/outputs/새시험/dataset --max-tokens 18000

training/.venv/bin/python training/trial_train.py train \
  --dataset training/outputs/새시험/dataset \
  --adapter training/adapters/trial-새시험 --iters 10 --layers 1

# 학습이 끝난 뒤 별도 프로세스에서 새 어댑터를 확인합니다.
training/.venv/bin/python training/trial_train.py reload \
  --dataset training/outputs/새시험/dataset \
  --adapter training/adapters/trial-새시험
```

새 데이터셋·어댑터·보고서 경로만 허용합니다. 기존 `compatibility-only` 어댑터와
기반 모델은 덮어쓰지 않습니다. 마지막 1개 층의 MLP 세 모듈만 학습하고 어텐션은 고정합니다.
입력 전체로 고정 문맥을 한 번 계산한 뒤, 위치별 MLP·정규화·출력층을 정답 위치에만
반복 적용해 같은 손실을 구합니다. 실행 전에 공식 마스킹 손실·정답 위치 투영·고정 문맥
재사용의 손실값·gradient가 같은지 짧은 입력으로 검사합니다. 이 최적화는 확인한
`qwen3_5`의 분리된 출력층 구조에서만 실행하며 임의 모델에 적용하지 않습니다.
고정된 앞부분 층은 추론 모드로 처리하고 실제 LoRA 학습 대상인 마지막 층만 학습 모드로
유지합니다. 고정된 embedding·앞부분에 미분 경로가 없다는 전제이므로 학습 범위를
확장할 때는 다시 확인해야 합니다.

학습 전후 검증 손실은 서로 다른 원 요구사항에서 측정합니다. 소량 자료의 손실 변화는
제품 정확도나 DB 판단 개선을 증명하지 않으며, 독립 시험·사람 검토·Ollama 배포를 대체하지 않습니다.
세 분할을 지원하는 기존 `export_dataset.py`의 프로젝트 분리 정책은 유지합니다.

공식 안내: https://github.com/ml-explore/mlx-lm/blob/main/mlx_lm/LORA.md

## 시험 어댑터를 iCloud로 전달

`model_delivery.py`는 학습·재로딩·원본 보존 검사를 마친 시험 어댑터를 버전별로 묶습니다.
기존 `training/.env`의 공유 폴더 `DOENAAI_TRAINING_SHARED_DIR`와 공통 키
`DOENAAI_TRAINING_TRANSFER_KEY`를 사용합니다. 전역 옵션 `--env-file`은 하위 명령 앞에 적습니다.

```bash
training/.venv/bin/python training/model_delivery.py --env-file training/.env stage \
  --adapter training/adapters/trial-20261021-7srnohr-v3 \
  --reports training/outputs/trial-20261021-7srnohr-v3/dataset \
  --output training/outputs/model-packages/qwen-trial-20261021-v3 \
  --version qwen-trial-20261021-v3

training/.venv/bin/python training/model_delivery.py --env-file training/.env publish \
  --package training/outputs/model-packages/qwen-trial-20261021-v3

training/.venv/bin/python training/model_delivery.py --env-file training/.env verify \
  --package training/outputs/model-packages/qwen-trial-20261021-v3
```

공유 폴더의 `model-deliveries/qwen-trial-20261021-v3/`에는 `adapters.safetensors`,
`adapter_config.json`, `evaluation-summary.json`, `manifest.json`, `READY.json`만 게시합니다.
사례 원문·대화·전체 보고서·비밀값은 제외합니다. 전용 구분값이 있는 HMAC 서명과 명세 해시,
전달 파일 전체의 크기·해시를 검사하며 같은 버전·같은 명세만 중복 게시를 허용합니다.
버전이 충돌하면 기존 파일을 보존합니다. 로컬 게시 기록은 `.publish-state.json`에 저장합니다.

게시 후 상태는 `WAITING_FOR_REMOTE_VERIFICATION`이며 `cloudUploadVerified=false`입니다.
맥북의 로컬 복사 성공만으로 iCloud 업로드나 스튜디오 도착을 확정하지 않습니다.
스튜디오에서도 같은 키와 코드로 동기화된 패키지 경로에 `verify`를 실행해야 합니다.
이 명령의 성공 상태 `VERIFIED_TRIAL_ONLY`는 파일 검증 완료만 의미합니다.
이 패키지는 시험용이며 답변 품질은 미평가, 운영은 미승인, Ollama 호환성은 미검증 상태입니다.
이번 확인에서는 실제 어댑터의 로컬 패키지 준비·검증까지 완료했습니다.
실제 iCloud 게시는 명시적 승인 대기 상태로 실행하지 않았습니다.
장비별 검증 명령과 다음 배포 단계는 [모델 전달 안내](MODEL_DELIVERY.md)를 참고합니다.

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
