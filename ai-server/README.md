# AI 분석/추론 서버

백엔드가 수집한 소스·DB 근거로 분석하고 구조화된 JSON을 검증하는 FastAPI 서버입니다.
현재 모델 실행 엔진은 Ollama이며 기본 모델은 `qwen3.8:27b`입니다.
Git 수집·DB 접속·분석 작업 처리는 백엔드 워커가 담당합니다.

## 로컬 실행

Ollama를 실행하고 사용할 모델을 설치합니다.

```bash
open -a Ollama
ollama pull qwen3.8:27b
ollama list
```

저장소 루트에서 Python 환경을 준비합니다.

```bash
cd ai-server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
python -m app.security.internal_api_key
```

생성한 내부 키 하나를 이 서버의 `DOENAAI_AI_INTERNAL_API_KEY`와 백엔드의 대응 설정에 동일하게 넣습니다.
키를 서버마다 따로 생성하거나 문서·Git·로그에 기록하지 않습니다.
필수 설정과 선택 설정은 [.env.example](.env.example)을 기준으로 준비합니다.

```dotenv
DOENAAI_OLLAMA_URL=http://127.0.0.1:11434
DOENAAI_AI_MODEL=qwen3.8:27b
DOENAAI_AI_INTERNAL_API_KEY=생성한_32자_이상의_동일한_키
```

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

분석 API는 백엔드가 접근하는 사설망에만 공개합니다. Ollama는 `127.0.0.1:11434`를 유지하며 인터넷에 공개하지 않습니다.
코드나 환경변수를 변경하면 AI 분석/추론 서버를 재시작합니다.

## 상태 확인과 API

```bash
curl http://127.0.0.1:8000/health
curl http://127.0.0.1:8000/api/models/health -H "X-Internal-Api-Key: ${DOENAAI_AI_INTERNAL_API_KEY}"
```

`/health`는 프로세스 상태만, `/api/models/health`는 모델 연결 상태까지 확인합니다.
아래 내부 API는 모두 `X-Internal-Api-Key`가 필요합니다. 브라우저는 백엔드를 통해 호출합니다.

| API | 역할 |
| --- | --- |
| `POST /api/analysis/project` | 수집된 근거로 분석하고 검증된 JSON 반환 |
| `POST /api/analysis/project/stream` | 같은 분석의 단계 이벤트와 최종 결과를 NDJSON으로 반환 |
| `GET /api/models/installed` | 연결된 AI 분석/추론 서버의 설치 모델과 현재 선택 조회 |
| `POST /api/models/probe` | 지정한 모델·파일 식별값으로 짧은 JSON 응답 검사 |
| `POST /api/models/activate` | 유효한 검사 증거와 현재 선택 버전을 확인한 뒤 전환 |
| `POST /api/models/rollback` | 같은 파일이 설치된 직전 모델로 한 번 복구 |
| `GET /api/models/operations/{operationId}` | 응답 유실 뒤 기존 전환 결과 조회 |
| `POST /api/models/trained/import` | 서명된 완성 모델의 비동기 등록 접수 |
| `GET /api/models/trained/imports/{jobId}` | 완성 모델 등록 상태 조회 |

모델 목록 조회는 추론하지 않습니다. 응답 검사는 업무 정확도나 학습 품질 평가를 대신하지 않습니다.
분석은 시작할 때 모델을 고정하며, 선택 전환은 새 분석부터 적용됩니다.
전환 응답을 놓치면 같은 처리 식별자의 결과를 먼저 확인합니다.

## 학습 결과 등록

학습 서버가 기반 모델과 어댑터를 병합하고 `Q4_K_M` GGUF로 변환해 공유 폴더에 게시합니다.
AI 분석/추론 서버는 서명·해시·크기를 검사한 뒤 기존 실행 엔진에 `doenaai-trained:web-{jobId}`로 등록합니다.
등록 후 화면에서 **응답 검증 → 적용**해야 사용 모델이 바뀝니다. AI 분석/추론 서버에 MLX나 학습용 기반 모델을 설치할 필요는 없습니다.

필요한 연동 설정은 다음과 같습니다.

- `DOENAAI_TRAINING_SHARED_DIR`: 두 서버가 공유하는 동기화 폴더의 절대 경로
- `DOENAAI_TRAINING_RECEIVER_URL`: 학습 서버 수신 API 주소
- `DOENAAI_TRAINING_TRANSFER_KEY`: 학습 서버와 동일한 별도 서명 키
- `DOENAAI_AI_TRAINED_MODEL_DIR`: 로컬 보관 위치, 비우면 `.runtime/trained-models`
- `DOENAAI_AI_TRAINED_MODEL_IMPORT_TIMEOUT_SECONDS`: 등록 제한 시간, 기본 7,200초

기반 분석 모델은 로컬 실행 엔진에 남겨 두어야 합니다. 등록 때 대화 형식을 가져옵니다.
첫 등록은 보관·엔진 저장을 위해 모델 파일 크기의 두 배와 128 MiB 이상의 여유 공간을 검사합니다.
`WAITING`이면 공유 폴더 동기화를 완료한 뒤 같은 작업의 등록을 다시 요청합니다.
파일 경로나 실행 명령을 브라우저 요청으로 받지 않습니다.

## 분석과 보관 범위

- 소스 저장소의 빌드 스크립트나 업무 SQL을 실행하지 않습니다. 기술 스택 탐지와 전달받은 정적 근거를 사용합니다.
- 파일 연결은 일부 정적 문법만 지원합니다. 잠정 연결·부분 DB 검토를 실제 실행 경로나 전체 영향의 증명으로 취급하지 않습니다.
- 일반 분석 뒤 필요한 DB 상세 검토와 최종 정리를 수행합니다. 큰 DB 입력은 구조를 잘라내지 않고 나눠 검토하며 검토 범위를 결과에 남깁니다.
- 모델의 thinking·출력 길이는 기본값을 유지합니다. 시간 초과·잘린 출력·잘못된 JSON을 정상 결과로 저장하지 않습니다.
- 검증된 중간 결과는 `.analysis-checkpoints/`에 서명 보관합니다. 같은 근거·모델·계약일 때만 재사용하며 만료 파일은 자동 삭제하지 않습니다.
- 선택값·응답 검사 증거·전환 결과는 `.runtime/model-selection.sqlite3`에 보관합니다. 상태를 읽지 못하면 새 요청을 차단합니다.

위 로컬 상태와 결과는 비공개 자료로 관리하고 Git에 넣지 않습니다. 로그에도 내부 키·입력·모델 응답 원문을 남기지 않습니다.
과거 분석 결과는 새 코드나 모델 적용만으로 덮어쓰지 않습니다.

## 검사

```bash
pytest
python -m app.analyzers.scan_repository /path/to/repository --source-summary
```

자동 검사에서는 모델 실행 응답을 대체합니다. 실제 모델의 업무 품질은 별도 자료로 비교합니다.
`MODEL_UNAVAILABLE`은 실행 엔진·설치 목록·선택 파일을, `MODEL_TIMEOUT`은 요청 범위와 제한 시간을 확인합니다.
`MODEL_OUTPUT_LIMIT`과 응답 형식 오류는 완료로 처리하지 않습니다.

상세 운영은 [모델 관리 안내](../docs/18_AI_모델_관리_사용_안내.md),
[워커 지정 운영](../docs/16_분석_워커_지정_운영.md),
[학습 서버](../training/README.md), [모델 변환](../training/MODEL_EXPORT.md)을 참고합니다.
