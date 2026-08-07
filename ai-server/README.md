# ai-server

Mac Studio에서 Ollama의 로컬 모델을 호출하는 FastAPI 내부 분석 서버다.

현재 범위는 다음 두 부분이다.

- 구조화된 프로젝트 요약 요청을 로컬 LLM에 전달하고 JSON Schema와 Pydantic으로
  응답을 검증하는 최소 수직 연결
- 로컬에 준비된 저장소를 실행하지 않고 manifest와 소스 파일 근거로 언어,
  프레임워크, 런타임, 빌드 도구 등 기술 스택을 탐지하는 정적 분석기

Git clone/checkout Worker, DB 메타데이터 수집, 클래스·메서드·SQL 호출 관계 분석은
아직 포함하지 않는다.

기준 Mac Studio에서는 `devstral:latest` 설치, 구조화 JSON 단독 추론,
FastAPI 실제 분석 요청과 주요 장애 경로까지 검증을 완료했다.

## 범용 기술 스택 탐지

탐지기는 `app/analyzers/technology_detector.py`에 있으며 특정 frontend/backend
조합을 가정하지 않는다. 먼저 저장소의 모듈 경계를 찾고 생태계별 manifest를
파싱한 다음 데이터 기반 기술 카탈로그를 적용한다.

주요 manifest와 생태계:

```text
package.json                         Node.js / JavaScript / TypeScript
pom.xml                              Maven / JVM
build.gradle, build.gradle.kts       Gradle / JVM
pyproject.toml, requirements*.txt    Python
*.csproj, *.fsproj, *.vbproj         .NET
go.mod                               Go
Cargo.toml                           Rust
composer.json                        PHP
Gemfile, *.gemspec                   Ruby
mix.exs                              Elixir
pubspec.yaml                         Dart / Flutter
Package.swift                        Swift
CMakeLists.txt                       C / C++
build.sbt                            Scala
deps.edn, project.clj                Clojure
DESCRIPTION                          R
```

분류 범위는 frontend/backend/full-stack뿐 아니라 다음을 포함한다.

```text
mobile, desktop, server view
build tool, runtime, UI, state management
ORM, database client, messaging
security, observability, infrastructure
data, AI/ML, test, CMS, general library
```

알려진 프레임워크가 아니어도 해당 모듈과 생태계는 유지하며 dependency 이름은
`unclassifiedDependencies`에 보존한다. 새 기술은 분석 로직을 변경하지 않고
`app/analyzers/technology_catalog.py`에 규칙을 추가할 수 있다.

로컬 저장소 전체 보고서:

```bash
python -m app.analyzers.scan_repository /path/to/repository
```

LLM API에 전달할 축약 `sourceSummary`:

```bash
python -m app.analyzers.scan_repository \
  /path/to/repository \
  --source-summary
```

탐지기는 저장소의 `npm`, Gradle, Maven, Python 또는 기타 build script를 실행하지
않는다. `node_modules`, `.git`, `.venv`, `vendor`, `target`, `build` 등 생성물과
dependency 디렉터리를 제외하고 파일 수, 탐색 깊이, manifest 크기 제한을 적용한다.

## 보안 경계

```text
Spring Boot -> FastAPI:8000       사설 LAN
FastAPI -> Ollama:11434           Mac Studio localhost
외부 PC -> Ollama:11434           금지
```

- Ollama는 기본값인 `127.0.0.1:11434`를 유지한다.
- FastAPI 분석 API는 `X-Internal-Api-Key` header가 필요하다.
- 실제 API key는 `.env`, 문서, 로그, Git에 기록하지 않는다.
- 분석 요청에 password, token, private key 등 민감 필드가 있으면 `422`로 거부한다.

## 준비

Ollama 앱을 실행하고 모델을 설치한다. 기준 Mac Studio에는
`devstral:latest` 14GB 모델이 설치되어 있다.

```bash
open -a Ollama
ollama pull devstral
curl http://127.0.0.1:11434/api/tags
ollama list
```

Python 가상환경을 준비한다.

```bash
cd ai-server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

`.env.example`을 참고해 로컬 환경변수를 설정한다. 내부 API key는 다음 로컬
명령으로 한 번 생성한다. Python 표준 `secrets`로 384-bit 난수를 만들며 화면에만
출력하고 파일에는 자동 저장하지 않는다.

```bash
python -m app.security.internal_api_key
```

한 번 실행하면 동일한 키를 사용하는 `.env`, macOS/Linux shell, Windows
PowerShell 설정문이 함께 출력된다. 출력 예시의 실제 값은 실행할 때마다 달라진다.

```text
# .env file
DOENAAI_AI_INTERNAL_API_KEY=doenaai_생성된_실제_난수값
# macOS/Linux shell
export DOENAAI_AI_INTERNAL_API_KEY='doenaai_생성된_동일한_난수값'
# Windows PowerShell
$env:DOENAAI_AI_INTERNAL_API_KEY='doenaai_생성된_동일한_난수값'
```

생성된 키 하나를 Mac Studio의 FastAPI와 다른 PC의 Spring Boot에 동일하게
설정한다. 생성기를 PC마다 따로 실행하면 서로 다른 키가 만들어지므로 한 번만
실행한다. 키를 채팅, 문서, Git 또는 로그에 기록하지 않는다.

```bash
export DOENAAI_OLLAMA_URL=http://127.0.0.1:11434
export DOENAAI_AI_MODEL=devstral
export DOENAAI_AI_INTERNAL_API_KEY='위에서 한 번 생성한 실제 값'
export DOENAAI_AI_REQUEST_TIMEOUT_SECONDS=300
export DOENAAI_AI_KEEP_ALIVE=30m
```

## 실행

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

FastAPI는 Windows Spring Boot가 접근할 수 있도록 사설 LAN에만 공개한다. 인터넷
port forwarding은 설정하지 않는다.

## API

인증 없이 FastAPI 프로세스 상태만 확인한다. 모델 추론은 수행하지 않는다.

```bash
curl http://127.0.0.1:8000/health
```

설치 모델과 Ollama 연결 상태를 확인한다.

```bash
curl http://127.0.0.1:8000/api/models/health \
  -H "X-Internal-Api-Key: ${DOENAAI_AI_INTERNAL_API_KEY}"
```

구조화된 프로젝트 요약을 분석한다.

```bash
curl http://127.0.0.1:8000/api/analysis/project \
  -H "Content-Type: application/json" \
  -H "X-Internal-Api-Key: ${DOENAAI_AI_INTERNAL_API_KEY}" \
  -d '{
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
  }'
```

## 테스트

Ollama를 mock하는 단위 테스트:

```bash
pytest
```

현재 자동화 테스트는 25개가 통과한다. Ollama 응답은 자동화 테스트에서 mock하며,
실제 모델 통합은 아래 절차로 별도 확인한다.

## 실제 Devstral 통합 검증

2026-08-05 기준 Mac Studio에서 다음을 실제로 확인했다.

```text
ollama list                            devstral:latest 14GB
Ollama /api/chat                      구조화 한국어 JSON 성공
GET /health                           HTTP 200
GET /api/models/health                HTTP 200, available=true
POST /api/analysis/project, key 없음  HTTP 401, Ollama 호출 없음
POST /api/analysis/project, key 있음  HTTP 200
Pydantic/JSON Schema 검증              통과
```

측정 결과:

```text
Devstral 단독 추론 총 처리시간     약 27초
초기 모델 load                    약 7.3초
FastAPI 실제 분석 처리시간         약 23.25초
```

FastAPI 응답은 camelCase 계약, `riskLevel` 허용값, `riskScore` 0~100 범위와
Pydantic schema를 통과했다. 로그에는 request ID, HTTP method/path/status,
처리시간과 Ollama 호출 상태만 남았고 API key와 prompt 원문은 노출되지 않았다.

Devstral을 직접 재검증한다.

```bash
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

실제 FastAPI 분석은 위 `POST /api/analysis/project` 예제를 사용한다. 내부 API
key는 command에 직접 적지 않고 `DOENAAI_AI_INTERNAL_API_KEY` 환경변수로 전달한다.

## 장애 대응과 검증 결과

실제 오류 설정으로 다음 HTTP 계약을 확인했다.

```text
잘못된 Ollama URL       HTTP 503, MODEL_UNAVAILABLE
설치되지 않은 모델      HTTP 503, MODEL_UNAVAILABLE
0.001초 모델 timeout    HTTP 504, MODEL_TIMEOUT
```

모델 JSON Schema 위반 후 제한 재시도와 `502` 변환은 mock 자동화 테스트로
검증했다.

`127.0.0.1:11434` 연결이 거부되면 Ollama 앱이 실행 중인지 먼저 확인한다.

```bash
open -a Ollama
curl http://127.0.0.1:11434/api/tags
```

Ollama는 실행되지만 모델이 보이지 않으면 다음 명령으로 설치 상태를 확인한다.

```bash
ollama list
ollama pull devstral
```

FastAPI의 `/health`는 모델을 호출하지 않는다. Ollama와 모델까지 함께 확인하려면
내부 API key를 포함해 `/api/models/health`를 호출한다.

## 다음 구현 범위

현재 `TechnologyDetector`와 FastAPI/Ollama 분석 API는 각각 동작하지만,
`analysis_jobs.PENDING` 작업을 자동 처리하는 Worker는 없다. 다음 구현 대상은
다음과 같다.

1. Worker claim, lease, heartbeat와 retry
2. 고정 commit clone/checkout
3. `TechnologyDetector` 실행과 DB metadata 수집
4. 분석 facts를 FastAPI 요청으로 변환
5. `analysis_results` 저장과 작업 `COMPLETED` 처리
6. 프런트 진행률 polling과 결과 화면 연결

관련 DB 변경은 공식 진행상황 문서의 순서에 따라 신규
`008_analysis_pipeline.sql`에서 설계한다. 기존 `001`~`006` migration은
수정하거나 재실행하지 않는다.
