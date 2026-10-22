# 맥북 학습 서버

맥 스튜디오(스튜디오)는 AI 분석/추론 서버이고, 맥북은 학습 서버다. 맥북 학습 서버의 MLX-LM·LoRA 환경에서 승인 자료로 추가 학습하고, 기반 모델과 학습 어댑터를 합쳐 스튜디오의 AI 분석/추론 서버가 실행할 새 GGUF 모델을 만든다. 스튜디오의 AI 분석/추론 서버와 별도의 `training/.venv`를 사용한다.

| 안내 | 내용 |
|---|---|
| [데이터 전달·평가](DATA_WORKFLOW.md) | 승인 사례 계약, 화면 전송, 수신 확인, 분할과 품질 비교 |
| [모델 대응과 지원 범위](MODEL_COMPATIBILITY.md) | 선택 모델별 기반 모델·학습 프로필·이어 학습과 현재 제약 |
| [새 모델 생성·적용](MODEL_EXPORT.md) | 변환 도구 설치, 저장 공간, NAS 전달, 등록과 적용 |

## 학습 환경 준비

Apple Silicon·Python 3.12를 기준으로 저장소 루트에서 실행한다. 검증한 맥북은 M4 Max·CPU 16코어·GPU 40코어·통합 메모리 64 GiB이며 Python 3.12.15, MLX 0.32.3, MLX-LM 0.32.0을 사용한다. 전체 의존성은 `requirements-lock.txt`로 고정한다.

```bash
python3.12 -m venv training/.venv
training/.venv/bin/python -m pip install -r training/requirements-lock.txt
training/.venv/bin/python training/check_environment.py
```

GPU 학습은 Metal을 사용할 수 있는 일반 터미널에서 실행한다. 샌드박스의 GPU 접근 제한은 모델 오류와 구분한다.

기존 Qwen 모델은 다음 명령으로 고정 버전을 준비하고 로딩·짧은 학습·저장·재로딩을 확인한다. 이 호환성 시험의 합성 대화와 어댑터는 업무 학습·배포용이 아니다. 이미 준비된 기반 모델과 시험 어댑터를 덮어쓰지 않는다.

```bash
training/.venv/bin/python training/download_model.py
training/.venv/bin/python training/verify_qwen_compatibility.py load
training/.venv/bin/python training/verify_qwen_compatibility.py train
training/.venv/bin/python training/verify_qwen_compatibility.py reload
```

## 맥북 학습 서버 설정·실행

`training/.env.example`을 `training/.env`로 복사해 맥북에서 보이는 공유 폴더, 두 서버의 공통 전송 키, 학습 기반 모델을 설정한다. Qwen 단일 경로는 `DOENAAI_TRAINING_MODEL_DIR`, 여러 모델의 등록 파일은 `DOENAAI_TRAINING_MODELS_FILE`을 사용한다. 모델별 대응과 프로필은 [모델 안내](MODEL_COMPATIBILITY.md)를 따른다. 실제 설정·비밀키는 Git에 넣지 않는다.

수신 서비스는 `training/.env`를 읽고, 없으면 저장소 루트 `.env`를 확인한다. 이미 설정한 환경변수가 우선한다. 공백이 있는 경로는 따옴표로 감싸며 `source .env`는 필요 없다.

```bash
training/.venv/bin/python training/receiver_service.py --host 0.0.0.0 --port 8011
```

다른 설정 파일은 `--env-file <경로>`로 지정한다. 코드·환경변수 변경은 활성 학습과 모델 생성이 없는지 확인한 뒤 수신 서비스를 재시작해 반영한다. 운영과 학습이 응답 계약·소스 변환 코드를 공유하므로 일부 파일만 복사하지 않고 저장소 전체를 업데이트한다.

## 화면에서 학습

AI 모델 관리의 **모델 작업**에서 모델을 선택하고 **선택 모델 학습**을 연다. 프론트에서 선택한 정확한 모델 ID를 백엔드와 맥북 학습 서버까지 유지한다. 대응하는 로컬 MLX 기반 모델·고정 버전·학습 프로필을 확인한 뒤 학습을 시작하며, 미등록 모델을 Qwen으로 대신 학습하거나 가중치를 자동으로 내려받지 않는다. 파일 수신은 학습을 자동 실행하지 않는다.

공통 실행 환경을 사용하되 기반 가중치·토크나이저·프로필·결과 어댑터는 모델별로 관리한다. 서로 다른 기반 모델에 같은 어댑터를 적용하지 않는다.

| 학습기 | 동작 |
|---|---|
| `mlx-lora-v1` | 명시한 모델별 프로필로 LoRA 학습 후 별도 프로세스에서 재로딩 |
| `qwen-mlp-trial-v1` | 기존 Qwen의 마지막 MLP 1층·rank 4 고정 시험 학습 |

현재 웹 시험은 일반 분석만 학습하며 DB 단계 정답은 학습하지 않는다. 서로 다른 원 요구사항 세 그룹과 프로필의 길이 상한 이내인 학습·검증 자료가 필요하다. 기존 Qwen 고정 시험의 상한은 18,000토큰이다. 한 작업에서 같은 원 요구사항의 수정본·후속 질문은 같은 분할에 묶는다. 길이 초과 자료는 근거나 정답을 자르지 않고 제외한다. 수신 검증이 끝난 최대 100개 파일·32 MiB를 다시 검사해 작업 사본으로 준비하고, 반복 횟수는 1부터 20까지 받는다. 분할·변환된 입력의 근거 제한은 [데이터 안내](DATA_WORKFLOW.md)를 참고한다.

완료한 파생 모델을 선택하면 해당 맥북의 부모 작업·기반 모델·프로필·어댑터·설정 해시를 확인해 이어 학습한다. `selectedModel`은 실제 선택 모델, `sourceModel`은 승인 자료의 원래 출처 모델로 구분한다. 기반 모델에 부모 어댑터를 한 번만 읽고 새 작업의 어댑터에 결과를 저장한다. 임의의 Ollama 파일에서 학습 상태를 복원하지 않는다.

학습 상태는 `PENDING → PREPARING → TRAINING → VERIFYING` 뒤 `SUCCEEDED`, `FAILED`, `CANCELLED`로 확정한다. API 응답은 접수이며 최종 결과는 화면의 실행 작업 이력에서 확인한다. 같은 작업 ID와 같은 조건은 재학습하지 않고 기존 결과를 반환한다.

| 맥북 학습 서버 API | 동작 |
|---|---|
| `GET /api/training/capabilities?model=<식별자>` | 대응 기반 모델·준비 상태·공개 학습 프로필·GGUF 변환 지원 확인 |
| `POST /api/training/jobs` | 백엔드가 확정한 작업과 모델 조건으로 학습 접수 |
| `GET /api/training/jobs` | 최근 작업 목록 확인 |
| `GET /api/training/jobs/{UUID}` | 상태·진행률·측정값 확인 |
| `POST /api/training/jobs/{UUID}/cancel` | 빈 객체 `{}`로 취소 요청 |

요청은 백엔드가 공통 키로 서명한다. 브라우저는 키·로컬 경로·실행 명령을 전달하지 않는다. 학습과 새 모델 생성은 공통 잠금으로 한 번에 하나만 실행한다. 직접 CLI 학습은 이 잠금을 공유하지 않으므로 웹 작업 중 함께 실행하지 않는다.

취소는 해당 작업이 만든 프로세스 묶음만 종료한다. 서버 재시작으로 끊긴 작업은 실패로 정리하며 자동으로 학습을 재개하지 않는다. 원본 자료·기반 모델·완료된 어댑터는 보존한다. 웹 결과는 `training/outputs/web-jobs/{UUID}/`, 어댑터는 `training/adapters/trial-web-{UUID}/`에 저장한다.

`SUCCEEDED`는 학습·저장·재로딩 성공이다. `independentTestExecuted=false`·`qualityEvaluated=false`이며 독립 생성 평가나 업무 품질 개선·운영 적용 승인을 뜻하지 않는다. 학습 지원과 GGUF 변환 지원은 별개이므로 변환 미지원이어도 학습 결과를 보존한다. 완료 결과는 **학습 결과 적용**에서 새 모델을 생성하고, 스튜디오의 AI 분석/추론 서버 등록·응답 검증·적용을 각각 진행한다.

모델·데이터셋·캐시·어댑터·체크포인트·실행 보고서는 Git에서 제외한다. 기존 승인 자료에 누락값을 추정해 채우거나 원본을 삭제하지 않는다.

## 실제 검증 범위와 남은 과제

| 범위 | 확인한 내용 |
|---|---|
| 실제 Qwen | 승인 자료로 짧은 10스텝 학습·저장·별도 재로딩, 기존 완료 작업의 GGUF 생성 확인 |
| 작은 임의 Llama·EXAONE4 | 4비트 CPU 새 학습 2스텝·재로딩·이어 학습 2스텝·재로딩 통과, 부모 파일 해시 유지 |
| 공통 변환·등록 경로 | 합성 자료와 대역을 이용한 회귀 검사. 실제 대형 모델별 전체 변환·스튜디오 등록 성공과 구분 |

짧은 실행 성공은 실제 대형 모델의 긴 입력·메모리·속도·업무 품질을 보장하지 않는다. 최신 코드를 두 서버에 반영한 뒤 선택 모델 조회 → 승인 자료 전달 → 학습·재로딩 → 완성 모델 수신·등록 → JSON 응답 검증을 실제 장비에서 확인해야 한다. 현재 진행상황은 [진행상황 안내](../docs/03_현재_진행상황.md)를 따른다.

남은 보완은 다음과 같다.

- 반복 이어 학습에서 부모와 이전 작업의 학습·검증·시험 배정을 유지한다. 현재 한 작업 안의 그룹 분리와 구분한다.
- 시작 전 원 요구사항 그룹 수·유효 자료를 화면에 안내하고, 요청 응답 유실·새로고침 뒤 같은 작업 ID로 복구하는 경로를 보완한다.
- 웹 시험 자료에 독립 답변 평가를 연결하고 DB 단계별 실제 입력·승인 정답과 여러 학습 그룹의 배분 정책을 보완한다.
- 모델별 변환 임시 공간과 여유분을 계산한다. 현재 코드는 180 GiB 고정 조건을 유지하며 Qwen의 약 150~155 GiB는 계산 추정이다. 자세한 조건은 [새 모델 생성 안내](MODEL_EXPORT.md)를 따른다.
