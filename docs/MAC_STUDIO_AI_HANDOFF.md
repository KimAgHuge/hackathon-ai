# doenaai Mac Studio AI 작업 인수인계

최종 갱신: 2026-07-30

이 문서는 Mac Studio의 Codex가 `doenaai` 프로젝트에서 로컬 LLM 실행 환경과
FastAPI 연동 작업을 바로 이어서 진행할 수 있도록 현재 상태, 작업 범위, 보안
요구사항, 완료 기준을 정리한 문서다.

이 문서에는 토큰, 비밀번호, Supabase 키, 암호화 키, 내부 API 키 같은 실제
비밀값을 기록하지 않는다.

## Mac Studio Codex에 처음 전달할 요청

Mac Studio에서 저장소를 clone 또는 pull한 뒤 Codex에 아래와 같이 요청한다.

```text
docs/MAC_STUDIO_AI_HANDOFF.md를 처음부터 끝까지 읽고 현재 저장소 소스도 직접
확인해줘. 문서의 "이번 Mac 작업 범위"만 구현하고, 기존 Windows/Spring Boot
기능과 Supabase migration은 임의로 변경하지 마.

먼저 Mac의 칩, macOS 버전, 통합 메모리, 사용 가능한 디스크를 확인하고
Ollama에서 실행할 모델을 결정해. 그 다음 Ollama를 localhost에서 실행하고,
ai-server의 FastAPI가 구조화된 JSON으로 모델을 호출하도록 구현해.

비밀값은 코드, 문서, 로그, Git에 넣지 말고 환경변수로만 받아. 구현 후 실제
Ollama 호출, FastAPI API, 인증 실패, 모델 서버 장애 처리를 검증하고 변경 파일과
검증 결과를 설명해줘. 사용자가 요청하기 전에는 commit 또는 push하지 마.
```

## 프로젝트 목적

`doenaai`는 프로젝트의 소스코드와 데이터베이스 스키마를 함께 분석하여 다음
정보를 제공하는 사내용 AI 분석 플랫폼이다.

- Vue 화면부터 Spring API, Service, Repository/Mapper까지의 호출 관계
- SQL과 DB 테이블 및 컬럼의 관계
- 프로젝트 통합 아키텍처
- 변경 요청이 영향을 주는 소스, API, SQL, DB 객체
- 변경 위험도, 근거, 권장 수정 및 테스트 항목

예상 관계의 예시는 다음과 같다.

```text
Vue 화면
  -> HTTP API
  -> Spring Controller
  -> Service
  -> Repository 또는 MyBatis Mapper
  -> DB 테이블
```

AI 모델이 소스에서 모든 관계를 추측하도록 만들지 않는다. 정적 분석기와 DB
메타데이터 수집기가 사실을 먼저 추출하고, LLM은 구조화된 사실을 기반으로
요약, 위험도, 영향 범위, 권장사항을 생성하는 역할을 담당한다.

## 전체 시스템 구조

```text
일반 사용자 PC
  Vue 3 + Vite
       |
       | Supabase JWT
       v
  Java 25 + Spring Boot 4.1
       |  프로젝트, 권한, Git/DB 연결, 분석 작업 관리
       |
       | 사설 LAN의 인증된 내부 API
       v
Mac Studio
  FastAPI(ai-server)
       |  프롬프트 구성, JSON Schema 검증, 모델 오류 처리
       |
       | localhost 전용
       v
  Ollama
       |
       v
  사전 학습된 코드 이해용 LLM

Supabase
  Auth + PostgreSQL + 분석 작업/결과 + 모델/학습 이력

별도 학습 장비(M4 Max MacBook 예정)
  MLX-LM / LoRA 학습
```

중요한 네트워크 경계:

```text
Spring Boot -> FastAPI:8000    사설 LAN에서만 허용
FastAPI -> Ollama:11434        Mac Studio localhost에서만 허용
외부 PC -> Ollama:11434        허용하지 않음
인터넷 -> FastAPI/Ollama       포트포워딩하지 않음
```

Ollama의 로컬 API에는 기본 인증이 없으므로 `OLLAMA_HOST=0.0.0.0:11434`로
노출하지 않는다. FastAPI와 Ollama가 같은 Mac에서 실행되므로 Ollama는 기본값인
`127.0.0.1:11434`를 유지한다.

## 저장소 구성

```text
doenaai/
  frontend/       Vue 3, Vite, Pinia, Vue Router, Supabase Auth
  backend/        Java 25, Spring Boot 4.1, Security, JPA/JDBC, JGit
  ai-server/      Python FastAPI 분석 서버
  training/       향후 MLX-LM/LoRA 학습 영역
  shared/         공통 JSON Schema
  supabase/       PostgreSQL migration
  docs/           아키텍처와 작업 인수인계 문서
```

현재 원격 저장소와 기준 branch:

```text
origin: https://github.com/KimAgHuge/hackathon-ai.git
branch: main
2026-07-30 확인 HEAD: f90b046
```

Mac에서는 반드시 실제 `git status`, `git log -5 --oneline`, 원격 branch 상태를
다시 확인한다. 이 문서의 commit 값보다 원격 저장소가 앞서 있으면 최신 원격
소스를 기준으로 한다.

## 현재까지 구현된 기능

### 인증과 권한

- Supabase Auth 로그인
- 프런트엔드가 Supabase access token을 Bearer token으로 백엔드에 전달
- Spring Security OAuth2 Resource Server가 JWT 검증
- 역할 및 permission 기반 화면/API 접근 제어

### 프로젝트

- 프로젝트 생성, 조회, 수정
- 프로젝트 구성원과 접근 권한 확인
- 프로젝트 소스 유형 `GIT`, 향후 `ZIP` 예약
- 저장소 URL과 기본 branch 관리

### 범용 Git 저장소 연결

- GitHub, GitLab, Bitbucket, Yona, Custom Git host 구조
- 공개 HTTPS
- HTTPS username + access token
- HTTPS basic
- SSH private key, 선택적 passphrase, `known_hosts`
- JGit을 이용한 원격 연결 확인 및 commit SHA 검증
- DB의 `git_host_policies`에 등록된 host/protocol/port만 허용
- Token, password, SSH private key를 AES-256-GCM으로 암호화 저장
- 연결정보 조회 API가 비밀값과 암호문을 반환하지 않음

Git API:

```text
POST /api/projects/{projectId}/repository-connection/test
PUT  /api/projects/{projectId}/repository-connection
GET  /api/projects/{projectId}/repository-connection
```

### 프로젝트 DB 연결

- 현재 실제 연결 테스트 지원: Oracle, PostgreSQL
- JDBC read-only 연결 테스트
- DB password AES-256-GCM 암호화 저장
- DB 종류 enum에는 향후 Tibero/MySQL/MariaDB/MSSQL 등이 예약되어 있으나
  현재 JDBC driver와 실제 연결 로직은 구현되지 않음

DB API:

```text
POST /api/projects/{projectId}/database-connection/test
PUT  /api/projects/{projectId}/database-connection
```

### 분석 작업 생성

- 저장된 Git 연결을 분석 시작 직전에 다시 검증
- 저장소 URL, branch, 검증된 commit SHA를 `analysis_jobs.input_snapshot`에 저장
- `analysis_jobs`에 `PROJECT_SCAN`, `PENDING` 작업 생성
- 분석 작업 단건 조회 API

분석 API:

```text
POST /api/projects/{projectId}/analysis-jobs
GET  /api/analysis-jobs/{analysisJobId}
```

현재는 `PENDING` 작업을 가져가 처리하는 Worker가 없다. 분석 상태는 자동으로
`RUNNING` 또는 `COMPLETED`로 변하지 않는다.

## Supabase 상태

사용자가 2026-07-30 기준으로 `001`부터 `006`까지 적용했고 관련 테이블 데이터가
정상적으로 조회된다고 확인했다. Mac 작업에서 migration을 다시 실행하지 않는다.

주요 테이블:

- 인증/RBAC
  - `profiles`
  - `roles`
  - `permissions`
  - `user_roles`
  - `role_permissions`
- 프로젝트/연결
  - `projects`
  - `project_members`
  - `project_repository_connections`
  - `project_database_sources`
  - `git_host_policies`
- 분석
  - `change_requests`
  - `analysis_jobs`
  - `analysis_results`
- AI 모델/학습/배포
  - `ai_models`
  - `training_datasets`
  - `training_jobs`
  - `model_versions`
  - `model_evaluations`
  - `model_deployments`

Mac에서 이번 작업을 진행하는 동안 `007` migration을 임의로 만들거나 기존
migration을 수정하지 않는다. 분석 Worker용 DB 구조는 Ollama/FastAPI 연동이
검증된 다음 별도 작업으로 진행한다.

## 현재 AI 영역의 구현 상태

`ai-server`는 다음과 같은 최소 뼈대만 있다.

```text
ai-server/
  requirements.txt
  README.md
  app/
    main.py
    analyzers/__init__.py
    routers/__init__.py
    services/__init__.py
```

현재 구현된 API:

```text
GET /health
```

`app/main.py`는 고정된 health JSON만 반환한다. 실제 LLM, Git loader, DB 분석기,
정적 분석기, Worker는 아직 없다.

현재 `requirements.txt`:

```text
fastapi
uvicorn[standard]
pydantic
```

`training/train_lora.py`, `training/export_dataset.py`는 메시지만 출력하는
placeholder다. 이번 Mac 작업에서 LoRA 학습 기능을 구현하지 않는다.

`shared/schemas/impact-analysis.schema.json`에는 영향도 분석의 초기 JSON Schema가
있다. 현재 필드는 다음과 같다.

- `riskLevel`
- `riskScore`
- `impactAreas`
- `evidences`
- `recommendations`

필요하면 이 파일을 훼손하지 않고 프로젝트 전체 스캔용
`shared/schemas/project-scan.schema.json`을 별도로 추가한다.

## 이번 Mac 작업 범위

이번 작업의 목적은 실제 프로젝트 전체 분석을 완성하는 것이 아니다. 아래
수직 연결을 실제로 검증하는 것이다.

```text
FastAPI 요청
  -> localhost Ollama 호출
  -> 로컬 코드 이해용 LLM 추론
  -> JSON Schema에 맞는 응답
  -> Pydantic 검증
  -> FastAPI 응답
```

구현 범위:

1. Mac 하드웨어와 운영체제 확인
2. Ollama 설치 및 localhost 실행
3. 메모리에 맞는 코드 이해용 모델 설치
4. `ai-server`에서 Ollama 비동기 호출
5. Pydantic 기반 구조화 응답 검증
6. FastAPI 내부 API key 인증
7. 모델/FastAPI health API
8. 정상, 인증 실패, Ollama 장애, 잘못된 모델 응답 테스트
9. 환경변수 예제와 Mac 실행 문서 작성

이번 범위에서 제외:

- Git clone/checkout Worker
- DB credential 전달과 DB 메타데이터 분석
- Java/Vue/MyBatis parser
- `analysis_jobs` 선점과 heartbeat
- `analysis_results` 저장
- Spring Boot의 FastAPI client
- 프런트엔드 진행률 polling
- RAG, embedding, reranker
- LoRA 학습
- Supabase migration 추가 또는 변경

## Mac 사전 확인

다음 명령의 결과를 작업 기록에 남긴다. 일련번호처럼 민감할 수 있는 값은 문서나
커밋에 포함하지 않는다.

```bash
sw_vers
uname -m
system_profiler SPHardwareDataType | grep -E "Chip|Memory"
df -h /
python3 --version
```

Ollama의 macOS 요구사항은 Apple M 시리즈 또는 Intel CPU와 macOS Sonoma 14
이상이다.

- 공식 설치 문서: https://docs.ollama.com/macos
- API 소개: https://docs.ollama.com/api/introduction
- 구조화 출력: https://docs.ollama.com/capabilities/structured-outputs
- Python client: https://github.com/ollama/ollama-python

## 모델 선택 원칙

모델 이름을 코드에 하드코딩하지 않고 `DOENAAI_AI_MODEL` 환경변수로 받는다.

초기 후보:

```text
통합 메모리 32GB 이상:
  devstral

통합 메모리가 32GB보다 작음:
  더 작은 quantized coder instruct 모델을 선택하고 정확한 tag를 작업 기록에 남김

통합 메모리 64GB 이상:
  devstral과 qwen3-coder:30b를 동일한 테스트 입력으로 비교 가능
```

현재 Ollama library 기준 참고값:

- `devstral`: 약 14GB, 24B, 코드/소프트웨어 엔지니어링용
- `qwen3-coder:30b`: 약 19GB, 코드 작업용

모델 파일 크기 외에도 KV cache와 긴 context를 위한 메모리가 필요하므로 모델
파일 크기가 통합 메모리에 간신히 들어가는 구성을 선택하지 않는다. 첫 검증은
짧은 입력과 제한된 context로 시작한다.

설치 예시:

```bash
ollama pull devstral
ollama run devstral
```

Ollama 확인:

```bash
curl http://127.0.0.1:11434/api/tags

curl http://127.0.0.1:11434/api/chat \
  -H "Content-Type: application/json" \
  -d '{
    "model": "devstral",
    "messages": [
      {
        "role": "user",
        "content": "Spring Controller와 Service의 관계를 JSON으로 설명해줘."
      }
    ],
    "stream": false,
    "format": "json"
  }'
```

## 권장 AI 서버 파일 구조

기존 뼈대를 다음과 같이 확장한다. 실제 이름은 현재 코드 스타일을 확인한 뒤
조정할 수 있지만 역할은 분리한다.

```text
ai-server/
  .env.example
  requirements.txt
  README.md
  app/
    main.py
    config.py
    dependencies.py
    models/
      __init__.py
      analysis.py
    routers/
      __init__.py
      analysis.py
      health.py
    services/
      __init__.py
      llm_service.py
    analyzers/
      __init__.py
  tests/
    test_health.py
    test_analysis.py
```

권장 dependency:

```text
fastapi
uvicorn[standard]
pydantic
pydantic-settings
ollama
pytest
httpx
```

버전 고정은 설치와 테스트가 성공한 실제 버전을 확인한 뒤 수행한다.

## 환경변수

실제 비밀값을 `.env.example`에 넣지 않는다.

```text
DOENAAI_OLLAMA_URL=http://127.0.0.1:11434
DOENAAI_AI_MODEL=devstral
DOENAAI_AI_INTERNAL_API_KEY=
DOENAAI_AI_REQUEST_TIMEOUT_SECONDS=300
DOENAAI_AI_KEEP_ALIVE=30m
```

`DOENAAI_AI_INTERNAL_API_KEY`는 충분히 긴 무작위 값이어야 하며 Spring Boot와
FastAPI에 동일하게 설정한다. Git에 commit하지 않는다.

FastAPI는 다음 header를 검사한다.

```text
X-Internal-Api-Key: {DOENAAI_AI_INTERNAL_API_KEY}
```

비교 시 일반 문자열 비교보다 `secrets.compare_digest`를 사용한다.

인증 예외:

- `/health`: 인증 없이 접근 가능
- 모델 추론 API: 내부 API key 필수
- 필요하면 `/health/model`: 사설 LAN에서만 접근시키거나 내부 API key 필수

## 1차 API 계약

권장 endpoint:

```text
GET  /health
GET  /api/models/health
POST /api/analysis/project
```

`POST /api/analysis/project` 요청 예시:

```json
{
  "projectId": "00000000-0000-0000-0000-000000000000",
  "sourceSummary": {
    "frameworks": ["Vue", "Spring Boot"],
    "components": []
  },
  "databaseSummary": {
    "type": "ORACLE",
    "tables": []
  },
  "relations": []
}
```

응답 예시:

```json
{
  "summary": "프로젝트 구조에 대한 요약",
  "frameworks": ["Vue", "Spring Boot", "Oracle"],
  "riskLevel": "UNKNOWN",
  "riskScore": 0,
  "impactAreas": [],
  "evidences": [],
  "recommendations": []
}
```

필수 규칙:

- API JSON은 기존 프런트엔드 스타일과 맞춰 camelCase를 사용한다.
- 내부 Python field는 snake_case를 사용해도 되지만 Pydantic alias로 변환한다.
- `riskLevel`은 `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`, `UNKNOWN`만 허용한다.
- `riskScore`는 0부터 100까지만 허용한다.
- LLM 응답을 그대로 반환하지 않고 Pydantic으로 검증한다.
- 모델 입력에는 Git token, DB password, 암호문, 개인키를 절대 포함하지 않는다.
- `temperature=0` 또는 낮은 값으로 구조화 응답의 일관성을 높인다.
- 모델이 schema를 위반하면 제한된 횟수만 재시도하고 명확한 오류를 반환한다.

Ollama Python client의 `AsyncClient`를 사용한다. `format`에 Pydantic의
`model_json_schema()`를 전달하고 응답의 `message.content`를
`model_validate_json()`으로 검증하는 방식이 권장된다.

## 실행 방식

```bash
cd ai-server

python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

export DOENAAI_OLLAMA_URL=http://127.0.0.1:11434
export DOENAAI_AI_MODEL=devstral
export DOENAAI_AI_INTERNAL_API_KEY='로컬에서만 설정할 실제 값'

uvicorn app.main:app --host 0.0.0.0 --port 8000
```

FastAPI는 Windows의 Spring Boot가 접근해야 하므로 `0.0.0.0:8000`으로 실행할
수 있지만 인터넷에는 노출하지 않는다. 가능하면 macOS firewall과 사설망
정책에서 Spring Boot가 실행되는 PC만 접근하게 제한한다.

Windows에서 향후 사용할 설정:

```text
DOENAAI_AI_BASE_URL=http://{MAC_STUDIO_LAN_IP}:8000
DOENAAI_AI_API_KEY={FastAPI와 동일한 내부 API key}
```

이번 Mac 작업에서는 Spring Boot 설정과 client 구현까지 수정하지 않는다.

## 오류 처리 요구사항

FastAPI에서 최소한 다음 상황을 구분한다.

- 내부 API key 누락 또는 불일치: `401` 또는 `403`
- Ollama 연결 불가: `503 Service Unavailable`
- 설정된 모델이 설치되지 않음: `503` 또는 명확한 모델 오류
- LLM timeout: `504 Gateway Timeout`
- LLM이 JSON Schema를 위반: `502 Bad Gateway`
- 잘못된 요청 payload: FastAPI/Pydantic `422`

오류 응답과 로그에 prompt 전체, 소스 원문, 인증 header, 환경변수, 비밀값을
남기지 않는다. 로그에는 요청 ID, 모델명, 처리시간, 오류 종류 정도만 남긴다.

## 검증 시나리오

### 1. Ollama

```bash
curl http://127.0.0.1:11434/api/tags
```

- 선택한 모델이 목록에 표시되어야 한다.

### 2. FastAPI health

```bash
curl http://127.0.0.1:8000/health
```

- `status=UP` 응답
- 모델 추론을 수행하지 않아야 함

### 3. 인증 실패

내부 API key 없이 분석 API를 호출한다.

- `401` 또는 `403`
- 모델이 호출되지 않아야 함

### 4. 실제 모델 분석

내부 API key와 작은 구조화 입력을 전달한다.

- 실제 Ollama가 호출되어야 함
- Pydantic 검증을 통과한 JSON이 반환되어야 함
- `riskLevel`, `riskScore`가 허용 범위여야 함

### 5. Ollama 장애

Ollama를 중지하거나 잘못된 URL로 실행한다.

- FastAPI가 무한 대기하지 않아야 함
- `503` 또는 `504`와 안전한 오류 메시지를 반환해야 함

### 6. 설치되지 않은 모델

존재하지 않는 모델명을 환경변수로 설정한다.

- stack trace나 내부 경로를 API에 노출하지 않아야 함
- 어떤 설정을 확인해야 하는지 알 수 있는 오류를 반환해야 함

### 7. 단위 테스트

단위 테스트에서는 Ollama를 mock하여 다음을 검증한다.

- 정상 구조화 응답
- 인증 실패
- Ollama 예외
- JSON 검증 실패

실제 Ollama를 사용하는 테스트는 별도의 integration test로 구분한다.

## 완료 기준

아래 조건을 모두 만족하면 이번 Mac 작업이 완료된 것이다.

- [ ] Mac 하드웨어와 메모리에 맞는 로컬 모델을 선택함
- [ ] Ollama가 `127.0.0.1:11434`에서 동작함
- [ ] 모델 단독 `curl` 추론이 성공함
- [ ] FastAPI가 환경변수로 Ollama URL과 모델명을 읽음
- [ ] FastAPI가 내부 API key를 검사함
- [ ] 분석 API가 Ollama를 실제 호출함
- [ ] JSON Schema 기반 응답이 Pydantic 검증을 통과함
- [ ] Ollama 장애와 timeout을 안전하게 처리함
- [ ] 단위 테스트와 실제 통합 테스트가 통과함
- [ ] README 또는 Mac 실행 문서가 갱신됨
- [ ] 실제 비밀값이 `git diff`, 문서, 로그에 포함되지 않음
- [ ] 변경 파일과 검증 결과를 사용자에게 설명함
- [ ] 사용자 요청 없이 commit/push하지 않음

## 이후 작업

이번 작업이 완료된 뒤 Windows/Spring Boot 쪽에서 별도로 진행할 순서다.

1. `007_analysis_pipeline.sql`
2. 분석 작업 claim, heartbeat, event/log 구조
3. Spring Boot의 인증된 FastAPI client
4. FastAPI Git clone/checkout Worker
5. DB 메타데이터 수집
6. Java/Vue/MyBatis/SQL 정적 분석
7. 코드와 DB 관계 그래프 생성
8. `analysis_results` 저장
9. 프런트엔드 진행률 polling과 결과 화면
10. 승인/수정 결과가 충분히 축적된 이후 LoRA 학습

## Git 작업 주의사항

2026-07-30 Windows 작업 디렉터리에는 다음 오래된 staging 항목이 남아 있었다.

```text
AD frontend/src/components/project/ProjectConnectionStep.vue
```

현재 실제 컴포넌트 위치는 다음이다.

```text
frontend/src/components/project/connection/ProjectConnectionStep.vue
```

위 `AD` 항목은 원격 commit에 포함된 상태가 아니라 Windows 로컬 index에 남은
항목이다. Mac에서 원격 저장소를 새로 clone하면 나타나지 않아야 한다. Windows에서
이 문서를 commit하기 전에는 다음 명령으로 오래된 staging 항목만 해제한 뒤
`git status --short`를 다시 확인한다.

```powershell
git restore --staged -- frontend/src/components/project/ProjectConnectionStep.vue
```

그 다음 이 문서와 의도한 파일만 명시적으로 stage한다. 비밀값 파일,
`frontend/.env.local`, backend local 설정, Python `.venv`, 모델 파일은
commit하지 않는다.
