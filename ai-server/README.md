# ai-server

Mac Studio에서 Ollama의 로컬 모델을 호출하는 FastAPI 내부 분석 서버다.

현재 범위는 다음 두 부분이다.

- 구조화된 프로젝트 요약 요청을 로컬 LLM에 전달하고 JSON Schema와 Pydantic으로
  응답을 검증하는 최소 수직 연결
- 로컬에 준비된 저장소를 실행하지 않고 manifest와 소스 파일 근거로 언어,
  프레임워크, 런타임, 빌드 도구 등 기술 스택을 탐지하는 정적 분석기

Git clone/checkout Worker, DB 메타데이터 수집, 클래스·메서드·SQL 호출 관계 분석은
아직 포함하지 않는다.

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

Ollama 앱을 실행하고 모델을 설치한다.

```bash
ollama pull devstral
curl http://127.0.0.1:11434/api/tags
```

Python 가상환경을 준비한다.

```bash
cd ai-server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

`.env.example`을 참고해 로컬 환경변수를 설정한다. 내부 API key에는 충분히 긴
무작위 값을 사용한다.

```bash
export DOENAAI_OLLAMA_URL=http://127.0.0.1:11434
export DOENAAI_AI_MODEL=devstral
export DOENAAI_AI_INTERNAL_API_KEY='로컬에서만 관리하는 실제 값'
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

실제 모델 통합 검증은 Ollama와 `devstral` 설치가 끝난 뒤 위 API 요청으로 별도
수행한다.
