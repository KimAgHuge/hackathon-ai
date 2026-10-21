# 학습 결과로 분석용 GGUF 모델 만들기

작성일: 2026-10-21

## 실행 흐름과 장비 역할

완료한 웹 학습의 어댑터를 기반 모델과 합친 뒤, GGUF 변환과 `Q4_K_M` 양자화를 거쳐
별도 분석용 모델을 만든다. 새 모델 생성은 맥북 AI 학습 서버에서 실행하고, 완성 패키지를
공유 NAS 역할의 iCloud Drive `model-deliveries/`에 게시한다.
Mac Studio AI 분석/추론 서버는 서명·크기·해시·출처를 검사하고 로컬에 복사한 뒤
기존 Ollama에 새 이름으로 등록한다. 모델 학습이나 변환을 스튜디오에서 반복하지 않는다.
이 경로에서 스튜디오의 MLX 기반 모델·MLX Python 설정은 필수가 아니다.

```text
완료한 웹 학습 선택
  → 맥북: 기반 모델과 병합 → GGUF 변환 → Q4_K_M 양자화
  → 맥북: 서명된 완성 모델 패키지를 iCloud에 게시
  → 스튜디오: 파일 검증 → 로컬 보관 → Ollama 새 모델 등록
  → 화면: JSON 응답 검증 → 확인 후 새 분석에 적용
```

현재는 기존에 완료한 웹 학습 1건을 재사용해 다음 단계를 준비한다. 새 모델 생성을 위해
같은 자료를 다시 학습할 필요는 없다. 화면에는 선택한 분석 모델과 출처가 일치하는
`SUCCEEDED` 웹 학습만 표시한다. 기존 CLI 시험 어댑터가 웹 학습 목록에 자동으로 생기는 것은 아니다.
기존 기반 모델·학습 원본·어댑터를 덮어쓰지 않고 작업별 새 폴더와 새 Ollama 이름을 사용한다.

환경 준비 완료와 실제 모델 생성 성공은 별개다. 아래 절차를 마쳤더라도 GGUF 변환,
iCloud 동기화, 스튜디오 등록과 응답 검증 결과는 각 실행 단계에서 확인해야 한다.

## 맥북의 변환 환경 준비

모든 명령은 저장소 루트의 일반 터미널에서 실행한다. Git·CMake·Xcode 명령줄 도구와
Python 3.10 이상이 필요하며, 기존 `training/.venv`는 학습용으로 보존한다.

이번 환경 준비에서는 Homebrew를 먼저 업데이트해 macOS 26.6.2 호환 오류를 해결했다.
Homebrew 7.0.10과 CMake 4.4.4가 준비됐으며 모든 설치 패키지의 일괄 `upgrade`는 실행하지 않았다.
아래 명령은 다른 환경에서 도구가 필요한 경우의 준비 순서다.

```bash
brew update
brew install cmake
```

`setup_model_export.py`는 명시적으로 실행했을 때 공식 고정 소스를 내려받고 별도 변환용
Python 환경과 CPU 양자화 실행 파일을 만든다. 모델 가중치 다운로드·학습·실제 변환·iCloud 게시는
실행하지 않으며 기존 학습 환경과 `.env`를 수정하지 않는다.

```bash
training/.venv/bin/python training/setup_model_export.py \
  --python "$PWD/training/.venv/bin/python"
```

기본 설치 위치는 `training/.tools/model-export/`이며 그 아래에 `llama.cpp/`,
`converter-venv/`, 준비 완료 정보 `setup.json`을 만든다.
이미 설치 중이거나 폴더가 존재하면 같은 설치를 겹쳐 실행하지 않는다.
완료한 설치는 다음 명령으로 버전·의존성·실행 파일 해시를 다시 확인한다.

```bash
training/.venv/bin/python training/setup_model_export.py --check
```

다른 경로에 설치했으면 두 명령에 같은 `--directory` 절대 경로를 지정한다.
`--check`는 기존 도구를 검사하고 설정값을 출력하며 모델 생성이나 재설치를 실행하지 않는다.
준비 중인 폴더는 검사가 실패할 수 있으므로 설치 프로세스의 완료를 먼저 확인한다.

## 고정 버전과 저장 공간 조건

| 항목 | 현재 지원 조건 |
|---|---|
| 학습 기반 모델 | `mlx-community/Qwen3.8-27B-4bit` |
| 기반 모델 revision | `10c35caafbb80f7dc6a7a432cdd11af10a6d4818` |
| 학습 구현 | MLX-LM `0.32.0` |
| 변환 도구 | `llama.cpp` revision `620d2225cc436ab0b001e3200e590e3b286ef9ab` |
| 변환용 Python | `3.10` 이상, 기존 학습 환경과 별도 가상환경 |
| 변환용 의존성 | torch `2.11.0`, transformers `4.57.6`, numpy `2.2.6` 이상·`2.3` 미만 |
| 변환 실행 파일 | `convert_hf_to_gguf.py`, `build/bin/llama-quantize` |
| 맥북 작업 폴더 여유 | `training/outputs/model-builds`가 있는 볼륨에 **180GiB 이상** |

변환 도구의 추적 파일이 고정 버전과 다르거나 양자화 실행 파일의 해시가 설정과 다르면
새 모델 생성을 막는다. 학습·모델 생성은 GPU 잠금을 공유하므로 동시에 실행하지 않는다.
현재 확인한 Qwen 구조와 학습 기록에 맞춘 경로이며 다른 모델에 그대로 적용하지 않는다.

## 세 설정값 적용과 학습 서버 재시작

설치 또는 `--check`가 출력한 다음 값을 맥북의 `training/.env`에 반영한다.
출력값을 그대로 사용하고 해시를 임의로 채우지 않는다.

| 환경변수 | 값 |
|---|---|
| `DOENAAI_TRAINING_LLAMA_CPP_DIR` | 준비한 고정 버전 `llama.cpp` 폴더의 절대 경로 |
| `DOENAAI_TRAINING_CONVERTER_PYTHON` | `converter-venv/bin/python`의 절대 경로 |
| `DOENAAI_TRAINING_QUANTIZER_SHA256` | 실제 `llama-quantize` 실행 파일의 SHA-256 |

기존 `DOENAAI_TRAINING_MODEL_DIR` 또는 모델 등록 파일, iCloud 공유 폴더와 전송 키는 유지한다.
기존 셸 환경변수가 `.env`보다 우선하므로 이전 값이 남아 있다면 함께 확인한다.
설정 파일·비밀키는 Git에 넣지 않는다. 설정 반영은 학습 서버를 재시작해야 끝난다.
활성 학습·모델 생성이 없는지 먼저 확인하고, 기존 서버 터미널에서 `Ctrl+C`로 종료한 뒤 다시 실행한다.

```bash
training/.venv/bin/python training/receiver_service.py --host 0.0.0.0 --port 8011
```

서버 시작은 새 모델 생성을 자동 실행하지 않는다. 화면의 **준비 상태 새로고침**에서
고정 도구·저장 공간·작업 잠금 검사 결과를 확인한 뒤 다음 단계로 진행한다.

## 화면에서 생성·등록·적용

AI 모델 관리의 해당 모델 작업 화면에서 완료한 학습 결과를 선택한다.

1. **새 학습 모델 생성**의 실행 범위와 자원 사용을 확인하고 요청한다.
   상태는 `PENDING → FUSING → CONVERTING → QUANTIZING → PUBLISHING`을 거쳐 완료된다.
   이 요청에는 완성 패키지의 iCloud 게시까지 포함된다. 환경 준비 명령과 구분한다.
2. 생성이 `SUCCEEDED`가 되면 **분석 서버에 새 모델 등록**을 요청한다.
   파일 동기화가 덜 끝났으면 `WAITING`으로 표시하며, 동기화 후 같은 작업에서 등록을 다시 요청한다.
3. 등록이 `READY`이면 설치 목록의 모델·파일 식별값을 대조하고 **JSON 응답 검증**을 실행한다.
4. 유효한 검증 결과와 적용 범위를 확인한 뒤 새 분석에 적용한다.
   새 모델 생성과 등록만으로 운영 모델은 전환되지 않는다.

프론트는 백엔드 API를 호출하며, 백엔드는 권한과 원래 학습 서버·기반 모델·작업 이력을
확인한 뒤 서명된 학습 서버 요청 또는 내부 인증된 분석 서버 요청을 보낸다.
화면에서 경로·명령·비밀키를 전달하지 않는다.

| 백엔드 API | 동작 | 권한 |
|---|---|---|
| `GET /api/models/trained/{jobId}/build` | 생성 준비·진행 상태 확인 | `MODEL_READ` |
| `POST /api/models/trained/{jobId}/build` | 새 모델 생성과 iCloud 게시 요청 | `MODEL_TRAIN` |
| `POST /api/models/trained/{jobId}/build/cancel` | 생성 작업 취소 요청 | `MODEL_TRAIN` |
| `POST /api/models/trained/{jobId}/import` | 분석 서버 검증·Ollama 등록 요청 | `MODEL_DEPLOY` |
| `GET /api/models/trained/{jobId}/import` | 등록 상태 확인 | `MODEL_READ` |

생성·취소·등록 요청 본문은 빈 객체 `{}`이며 작업 식별자에서 원본 정보를 찾는다.
새 Ollama 이름은 `doenaai-trained:web-<jobId>`, 공유 패키지 버전은 `trained-web-<jobId>`다.
같은 이름의 다른 파일이 있으면 기존 내용을 덮어쓰지 않고 충돌로 처리한다.

## 스튜디오의 등록 조건과 남은 확인

스튜디오에는 기존 Ollama와 원래 분석 모델 `qwen3.8:27b`, 동기화된 iCloud 공유 폴더,
맥북과 같은 전송 키가 필요하다. `DOENAAI_AI_TRAINED_MODEL_DIR`를 지정하면 완성 모델을
그 로컬 폴더에 보관하며, 빈 값이면 `ai-server/.runtime/trained-models`를 사용한다.
기본 가져오기 제한시간은 `DOENAAI_AI_TRAINED_MODEL_IMPORT_TIMEOUT_SECONDS=7200`이다.
분석 서버는 로컬 보관 사본과 Ollama 가중치에 필요한 디스크 공간도 검사한다.

등록 시 기반 분석 모델의 대화 형식과 실행 구조를 확인하고 같은 형식을 새 모델에 사용한다.
등록·JSON 시험의 성공은 업무 정확도나 한국어 품질 개선을 증명하지 않는다.
독립 평가·사람 검토와 운영 적용 판단은 별도로 진행한다.
기존 분석은 시작 당시 모델을 유지하며 새 선택은 새 분석부터 적용한다.

학습 산출물·대형 변환 중간 파일·공유 패키지·로컬 상태·설정은 Git에 올리지 않는다.
전달 무결성과 기존 시험 어댑터 패키지의 차이는 [모델 전달 안내](MODEL_DELIVERY.md),
화면 사용과 권한은 [AI 모델 관리 사용 안내](../docs/18_AI_모델_관리_사용_안내.md)를 참고한다.

## 2026-10-21 맥북 준비 결과

`f87d15b` 기준으로 Homebrew 7.0.10·CMake 4.4.4와 고정 변환 도구를 준비했다.
기본 경로 `training/.tools/model-export/`에 설치했고 `setup_model_export.py --check`가
고정 소스·실행 파일 해시·변환용 의존성 검사를 통과했다. 검사로 확인한 세 값을 로컬
`training/.env`에 반영했으며 기존 키·학습 모델 경로·공유 폴더 설정과 파일 권한을 보존했다.

이 장비에서 빌드한 `llama-quantize`의 SHA-256은 다음과 같다. 다른 장비에서 다시
빌드할 때는 이 값을 복사하지 않고 해당 장비의 설치·검사 출력값을 사용한다.

```text
a3d3184ed0d2a06529023ed2ab8b517492f5e3d40a7e24730c934fa8da3e24a2
```

활성 학습이 없는 것을 확인한 뒤 20:06경 IntelliJ 터미널의 수신 서버를 정상 종료·재시작했다.
완료한 웹 학습의 모델 생성 상태를 로컬 서명 요청으로 조회해 HTTP 200,
`status=NOT_BUILT`, `available=true`, `reasonCode=READY`를 확인했다.
학습 이력은 1건·진행 중 0건이고 현재 모델의 선택 가능 자료는 3건·이전 자료 제외는 3건이다.
설치 후 작업 볼륨 여유는 약 210.85 GiB로 180 GiB 기준을 충족했다.

학습 Python과 패키지 54개, 기반 모델 46개 파일의 상태, 수신 원본 6개·수신 기록 6개·
기존 어댑터 8개 파일의 해시와 상태가 설치 전후 모두 같았다.
이 확인은 환경과 API의 준비 완료이며 실제 모델 병합·GGUF 생성·iCloud 게시·
스튜디오 등록·운영 모델 전환은 아직 실행하지 않았다.
