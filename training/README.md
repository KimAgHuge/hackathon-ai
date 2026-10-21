# 맥북 학습 서버

맥 스튜디오(스튜디오)는 AI 분석/추론 서버이고, 맥북은 학습 서버다. 맥북 학습 서버의 MLX-LM·LoRA 환경에서 승인 자료로 추가 학습하고, 기반 모델과 학습 어댑터를 합쳐 스튜디오의 AI 분석/추론 서버가 실행할 새 GGUF 모델을 만든다. 스튜디오의 AI 분석/추론 서버와 별도의 `training/.venv`를 사용한다.

| 안내 | 내용 |
|---|---|
| [데이터 전달·평가](DATA_WORKFLOW.md) | 승인 사례 계약, 화면 전송, 수신 확인, 분할과 품질 비교 |
| [모델 대응과 지원 범위](MODEL_COMPATIBILITY.md) | 분석 모델별 학습 기반 모델 설정과 현재 제약 |
| [새 모델 생성·적용](MODEL_EXPORT.md) | 변환 도구 설치, 저장 공간, NAS 전달, 등록과 적용 |

## 학습 환경 준비

Apple Silicon·Python 3.12를 기준으로 저장소 루트에서 실행한다. 검증한 환경은 통합 메모리 64 GiB, Python 3.12.15, MLX 0.32.3, MLX-LM 0.32.0이며 전체 의존성은 `requirements-lock.txt`로 고정한다.

```bash
python3.12 -m venv training/.venv
training/.venv/bin/python -m pip install -r training/requirements-lock.txt
training/.venv/bin/python training/check_environment.py
```

GPU 학습은 Metal을 사용할 수 있는 일반 터미널에서 실행한다. 샌드박스의 GPU 접근 제한은 모델 오류와 구분한다.

현재 지원 Qwen 모델은 다음 명령으로 고정 버전을 준비하고 로딩·짧은 학습·저장·재로딩을 확인한다. 이미 준비된 기반 모델과 시험 어댑터를 덮어쓰지 않는다.

```bash
training/.venv/bin/python training/download_model.py
training/.venv/bin/python training/verify_qwen_compatibility.py load
training/.venv/bin/python training/verify_qwen_compatibility.py train
training/.venv/bin/python training/verify_qwen_compatibility.py reload
```

## 맥북 학습 서버 설정·실행

`training/.env.example`을 `training/.env`로 복사해 맥북에서 보이는 공유 폴더, 두 서버의 공통 전송 키, 학습 기반 모델을 설정한다. 모델별 대응 설정은 [모델 안내](MODEL_COMPATIBILITY.md)를 따른다. 실제 설정·비밀키는 Git에 넣지 않는다.

수신 서비스는 `training/.env`를 읽고, 없으면 저장소 루트 `.env`를 확인한다. 이미 설정한 환경변수가 우선한다. 공백이 있는 경로는 따옴표로 감싸며 `source .env`는 필요 없다.

```bash
training/.venv/bin/python training/receiver_service.py --host 0.0.0.0 --port 8011
```

다른 설정 파일은 `--env-file <경로>`로 지정한다. 코드·환경변수 변경은 활성 학습과 모델 생성이 없는지 확인한 뒤 수신 서비스를 재시작해 반영한다. 운영과 학습이 응답 계약·소스 변환 코드를 공유하므로 일부 파일만 복사하지 않고 저장소 전체를 업데이트한다.

## 화면에서 학습

AI 모델 관리의 **모델 작업**에서 모델을 선택하고 **선택 모델 학습**을 연다. 선택한 모델의 승인 자료와 맥북 학습 서버의 대응 기반 모델을 확인한 뒤 학습을 시작한다. 파일 수신은 학습을 자동 실행하지 않는다.

현재 웹 학습은 `qwen-mlp-trial-v1` 시험 학습이다. 마지막 층 MLP만 학습하며 DB 단계 정답은 학습하지 않는다. 서로 다른 원 요구사항 세 그룹과 18,000토큰 이내의 학습·검증 자료가 필요하다. 긴 자료는 근거나 정답을 자르지 않고 제외한다. 수신 검증이 끝난 최대 100개 파일·32 MiB를 다시 검사해 작업 사본으로 준비하고, 반복 횟수는 1부터 20까지 받는다. 분할·근거 제한은 [데이터 안내](DATA_WORKFLOW.md)를 참고한다.

학습 상태는 `PENDING → PREPARING → TRAINING → VERIFYING` 뒤 `SUCCEEDED`, `FAILED`, `CANCELLED`로 확정한다. API 응답은 접수이며 최종 결과는 화면의 실행 작업 이력에서 확인한다. 같은 작업 ID와 같은 조건은 재학습하지 않고 기존 결과를 반환한다.

| 맥북 학습 서버 API | 동작 |
|---|---|
| `GET /api/training/capabilities?model=<식별자>` | 대응 기반 모델·실행 준비·선택 가능 자료 확인 |
| `POST /api/training/jobs` | 백엔드가 확정한 작업과 모델 조건으로 학습 접수 |
| `GET /api/training/jobs` | 최근 작업 목록 확인 |
| `GET /api/training/jobs/{UUID}` | 상태·진행률·측정값 확인 |
| `POST /api/training/jobs/{UUID}/cancel` | 빈 객체 `{}`로 취소 요청 |

요청은 백엔드가 공통 키로 서명한다. 브라우저는 키·로컬 경로·실행 명령을 전달하지 않는다. 학습과 새 모델 생성은 공통 잠금으로 한 번에 하나만 실행한다. 직접 CLI 학습은 이 잠금을 공유하지 않으므로 웹 작업 중 함께 실행하지 않는다.

취소는 해당 작업이 만든 프로세스 묶음만 종료한다. 서버 재시작으로 끊긴 작업은 실패로 정리하며 자동으로 학습을 재개하지 않는다. 원본 자료·기반 모델·완료된 어댑터는 보존한다. 웹 결과는 `outputs/web-jobs/{UUID}/`, 어댑터는 `adapters/trial-web-{UUID}/`에 저장한다.

`SUCCEEDED`는 학습·저장·재로딩 성공이다. 독립 생성 평가나 업무 품질 개선·운영 적용 승인을 뜻하지 않는다. 완료 결과는 **학습 결과 적용**에서 새 모델을 생성하고, 스튜디오의 AI 분석/추론 서버 등록·응답 검증·적용을 각각 진행한다.

모델·데이터셋·캐시·어댑터·체크포인트·실행 보고서는 Git에서 제외한다. 기존 승인 자료에 누락값을 추정해 채우거나 원본을 삭제하지 않는다.
