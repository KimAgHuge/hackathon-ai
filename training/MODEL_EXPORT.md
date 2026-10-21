# 학습 결과로 새 분석 모델 생성·적용

맥 스튜디오(스튜디오)는 AI 분석/추론 서버이고, 맥북은 학습 서버다. 맥북 학습 서버에서 **기반 모델 + 완료한 학습 어댑터 → 병합 → GGUF 변환 → Q4_K_M 양자화**를 실행한다. 완성 파일은 공유 NAS 역할의 iCloud Drive에 게시하고, 스튜디오의 AI 분석/추론 서버가 검증·복사한 뒤 기존 Ollama에 별도 모델로 등록한다. 스튜디오의 AI 분석/추론 서버에는 MLX Python이나 MLX 기반 모델 설정이 필요 없다.

기존 기반 모델·승인 자료·어댑터는 보존한다. 이미 완료한 웹 학습을 선택하면 같은 자료를 다시 학습할 필요 없다. 화면에는 선택한 기반 모델과 출처가 일치하는 완료 작업만 표시하며, CLI 시험 어댑터를 웹 작업으로 자동 등록하지 않는다.

## 맥북 학습 서버 변환 도구 준비

저장소 루트의 일반 터미널에서 실행한다. Git·CMake·Xcode 명령줄 도구와 Python 3.10 이상이 필요하다. CMake가 없으면 먼저 설치한다. 기존 `training/.venv`는 학습용으로 보존한다.

```bash
training/.venv/bin/python training/setup_model_export.py \
  --python "$PWD/training/.venv/bin/python"
```

도구는 고정 공식 소스, 별도 변환용 가상환경, CPU 양자화 실행 파일을 `training/.tools/model-export/`에 준비한다. 모델 다운로드·학습·실제 변환·NAS 게시를 실행하거나 기존 `.env`를 수정하지 않는다. 설치 중인 폴더에 설치 명령을 겹쳐 실행하지 않는다.

완료한 설치는 다음 명령으로 검사한다. 다른 위치에 설치했으면 설치·검사에 같은 `--directory <절대 경로>`를 사용한다.

```bash
training/.venv/bin/python training/setup_model_export.py --check
```

| 항목 | 조건 |
|---|---|
| 학습 모델 | [현재 지원 Qwen 기반 모델·고정 버전](MODEL_COMPATIBILITY.md) |
| 변환 도구 | `llama.cpp` revision `620d2225cc436ab0b001e3200e590e3b286ef9ab` |
| 변환 의존성 | torch `2.11.0`, transformers `4.57.6`, numpy `2.2.6` 이상·`2.3` 미만 |
| 실행 파일 | `convert_hf_to_gguf.py`, `build/bin/llama-quantize` |
| 작업 볼륨 | `training/outputs/model-builds`가 있는 볼륨에 **180 GiB 이상** 여유 |

설치 또는 `--check`가 출력한 다음 값을 맥북 학습 서버의 `training/.env`에 반영한다. 실행 파일 해시는 해당 장비의 출력값을 사용한다.

| 환경변수 | 값 |
|---|---|
| `DOENAAI_TRAINING_LLAMA_CPP_DIR` | 고정 버전 `llama.cpp` 폴더의 절대 경로 |
| `DOENAAI_TRAINING_CONVERTER_PYTHON` | 변환 환경 `converter-venv/bin/python`의 절대 경로 |
| `DOENAAI_TRAINING_QUANTIZER_SHA256` | 준비한 `llama-quantize`의 SHA-256 |

기존 학습 모델·공유 폴더·전송 키 설정은 유지한다. 환경변수가 `.env`보다 우선하므로 이전 값도 확인한다. 활성 학습과 모델 생성이 없는지 확인한 뒤 맥북 학습 서버를 재시작하고, 화면의 **준비 상태 새로고침**으로 도구·저장 공간·작업 잠금을 확인한다. 서버 시작만으로 모델 생성이 실행되지는 않는다.

도구 소스·실행 파일 해시·기반 모델·학습 어댑터·보고서가 기록과 다르면 생성을 막는다. 학습과 생성은 공통 잠금을 사용해 동시에 실행하지 않는다. 실패·취소·서버 재시작 때 해당 시도의 변환 임시 폴더를 정리하며, 원본과 완료된 전달 패키지는 보존한다.

## 화면에서 생성·등록·적용

AI 모델 관리의 **모델 작업 → 모델 선택 → 학습 결과 적용**에서 완료한 학습을 고른다.

1. **새 학습 모델 생성**을 요청한다. `PENDING → FUSING → CONVERTING → QUANTIZING → PUBLISHING → SUCCEEDED` 순서로 진행하며, NAS 게시까지 포함한다. API 응답은 접수이며 진행 상태에서 완료를 확인한다.
2. **분석 서버에 새 모델 등록**을 요청해 스튜디오의 AI 분석/추론 서버에 등록한다. NAS 파일이 아직 내려오지 않았으면 `WAITING`으로 표시한다. 두 장비의 업로드·다운로드 상태를 확인하고 동기화 후 같은 결과에서 등록을 다시 요청한다.
3. 등록 상태가 `READY`이면 **새 모델 응답 검증**으로 JSON 형식을 확인한다.
4. 결과와 적용 범위를 확인하고 **새 학습 모델 적용**을 요청한다. 생성·등록만으로 사용 모델이 바뀌지는 않는다. 진행 중인 분석은 시작 당시 모델을 유지하고 새 분석부터 선택한 모델을 사용한다.

실패·취소 후 생성 재시도는 화면에서 명시적으로 요청한다. 완료한 같은 결과의 생성·등록은 파일과 출처를 확인해 재사용하며, 같은 이름의 다른 내용은 덮어쓰지 않는다. 이전 모델은 보존하므로 모델 작업에서 응답 검증·선택 절차를 거쳐 다시 사용할 수 있다.

| 백엔드 API | 권한 |
|---|---|
| `GET /api/models/trained/{jobId}/build` | `MODEL_READ` |
| `POST /api/models/trained/{jobId}/build` | `MODEL_TRAIN` |
| `POST /api/models/trained/{jobId}/build/cancel` | `MODEL_TRAIN` |
| `POST /api/models/trained/{jobId}/import` | `MODEL_DEPLOY` |
| `GET /api/models/trained/{jobId}/import` | `MODEL_READ` |

생성·취소·등록 본문은 빈 객체 `{}`다. 백엔드가 권한·원래 맥북 학습 서버·기반 모델·작업 이력을 확인하고 서명된 맥북 학습 서버 요청 또는 내부 인증된 스튜디오의 AI 분석/추론 서버 요청을 보낸다. 브라우저는 경로·명령·비밀키를 전달하지 않는다.

## NAS 패키지와 스튜디오의 AI 분석/추론 서버 조건

공유 폴더의 `model-deliveries/trained-web-<jobId>/`에는 다음 네 파일을 게시한다.

| 파일 | 내용 |
|---|---|
| `model.gguf` | 병합·Q4_K_M 양자화를 마친 완성 모델 |
| `evaluation-summary.json` | 허용한 실행 측정값과 품질 미평가 표시 |
| `manifest.json` | 기반 모델·고정 버전·변환 도구·파일 크기·SHA-256 |
| `READY.json` | 명세 해시와 HMAC 서명 |

사례 원문·학습 대화·전체 보고서·비밀키·로컬 경로는 보내지 않는다. 서명은 완성 모델 전용 구분값 `doenaai-model-gguf-delivery-v1`을 사용한다. 구형 `model_delivery.py`의 시험 어댑터 패키지는 GGUF 완성 모델과 다르며 이 등록 경로의 입력이 아니다.

NAS는 전달 공간이며 모델을 직접 실행하는 폴더가 아니다. 로컬 게시 성공도 iCloud 업로드·원격 다운로드 완료를 뜻하지 않는다. 동기화 순서가 일정하지 않으므로 완료 표시뿐 아니라 모든 파일의 실제 로컬 존재·크기·해시·서명·출처를 확인한다. 누락·동기화 대기 상태에서는 현재 모델을 유지한다. 공유 파일의 명령이나 스크립트를 실행하지 않는다.

스튜디오의 AI 분석/추론 서버에는 기존 Ollama·원래 기반 분석 모델·동기화된 공유 폴더·같은 전송 키가 필요하다. 새 모델은 `doenaai-trained:web-<jobId>` 이름으로 등록하며, 기반 분석 모델의 대화 형식·실행 구조를 확인해 사용한다.

`DOENAAI_AI_TRAINED_MODEL_DIR`가 비어 있으면 `ai-server/.runtime/trained-models`에 보관한다. 가져오기 제한시간의 기본값은 `DOENAAI_AI_TRAINED_MODEL_IMPORT_TIMEOUT_SECONDS=7200`이다. 로컬 보관 사본과 엔진 가중치에 필요한 여유 공간도 검사한다. 등록 상태와 출처를 보존하며 실제 엔진 해시는 전달 명세·GGUF 파일 해시와 구분한다.

학습·생성·등록·JSON 검증 성공은 업무 정확도나 한국어 품질 개선을 증명하지 않는다. [동일 조건의 독립 평가](DATA_WORKFLOW.md)와 사람 검토를 거쳐 적용을 판단한다. 화면 권한과 전체 사용 흐름은 [모델 관리 안내](../docs/18_AI_모델_관리_사용_안내.md)를 참고한다. 모델·중간 파일·패키지·로컬 상태·설정은 Git에 올리지 않는다.
