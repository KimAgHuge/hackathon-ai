# AI 분석/추론 서버 · ai-server

Ollama의 로컬 모델을 호출해 분석 요청을 처리하는 FastAPI 기반 AI 분석/추론 서버다.
현재 기준 장비는 Mac Studio이며, 장비를 바꿔도 서버 역할의 명칭은 동일하게 사용한다.

현재 범위는 다음 두 부분이다.

- 구조화된 프로젝트 요약 요청을 로컬 LLM에 전달하고 JSON Schema와 Pydantic으로
  응답을 검증하는 최소 수직 연결
- 로컬에 준비된 저장소를 실행하지 않고 manifest와 소스 파일 근거로 언어,
  프레임워크, 런타임, 빌드 도구 등 기술 스택을 탐지하는 정적 분석기

Git snapshot 수집, DB 메타데이터 수집과 SQL/ORM 테이블 참조 분석은 Backend Worker가
담당한다. FastAPI는 전달받은 정제된 사실만 모델에 전달한다. 실행 및 현재 한계는
`../docs/06_2026-10-12_분석_Worker_구현_인수인계.md`를 참고한다.

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
FastAPI -> Ollama:11434           AI 분석/추론 서버 localhost
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
export DOENAAI_AI_MODEL=qwen3.8:27b
export DOENAAI_AI_INTERNAL_API_KEY='위에서 한 번 생성한 실제 값'
export DOENAAI_AI_REQUEST_TIMEOUT_SECONDS=900
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

Backend가 진행 중인 AI 단계도 표시할 때는 같은 요청 본문을
`POST /api/analysis/project/stream`으로 보낸다. 이 내부 키 보호 경로는
`application/x-ndjson`으로 일반 영향 분석·DB 영향 분석의 시작/완료, 응답 형식
재시도, 최종 결과 또는 안전한 오류 코드를 순서대로 반환한다. 원본 프롬프트,
모델의 부분 답변, 자격증명, 원본 콘솔 로그는 중간 이벤트에 포함하지 않는다.
Ollama 호출은 여전히 `stream=False`이므로 생성 토큰별 진행률은 알 수 없다.
기존 `/api/analysis/project` JSON 계약은 유지한다.

### DB 분석의 추론 유지와 오류 진단

일반·DB 단계 모두 `think`와 `num_predict`를 따로 지정하지 않고 모델 기본값을 유지한다.
thinking을 끄거나 추론 토큰을 임의로 제한해 처리 시간을 줄이지 않는다.
테이블·컬럼·키·인덱스를 임의로 생략하거나, 시간 초과를 DB 변경 없음으로 대체하지 않는다.

모델 측 출력 한도에 도달하면 JSON처럼 보이더라도 `MODEL_OUTPUT_LIMIT`으로 거부하고,
같은 잘린 응답을 다시 요청하거나 정상 완료·학습 정답으로 저장하지 않는다.
출력 한도를 높이기 전에는 요구사항 범위와 응답 분량을 확인한다.
HTTP 대기 시간 초과는 별도 `MODEL_TIMEOUT`이다.
시작 로그에는 모델·단계·입력 글자 수·thinking 설정·출력 한도만 남기며 입력·응답 원문은 남기지 않는다.
백엔드는 허용한 모델 오류 코드와 고정 한국어 안내를 작업 실패 사유에 보존한다.

### DB 분할 검토와 완료 단계 재사용

DB 구조가 기본 6개 테이블 또는 묶음별 메타데이터 12,000자를 넘으면 나눠 검토한다.
모든 테이블·컬럼·키·인덱스는 어느 한 묶음에 빠짐없이 들어간다.
테이블 하나가 기준보다 크면 그 테이블은 자르지 않고 단독으로 보낸다.
각 묶음은 전체 테이블 이름 목록과 자기 범위의 상세 구조를 구분해서 받으며,
부분 검토가 끝나면 최대 네 결과씩 전체 PK·FK·고유 인덱스를 대조해 단계적으로 종합한다.
범위 밖의 구조를 확인했다고 주장하거나 남은 변경을 변경 없음으로 바꾸면 검증에서 거부한다.
부분 검토와 종합에도 모델 기본 thinking·생성 길이를 유지한다.

```dotenv
DOENAAI_AI_DB_BATCH_MAX_CHARS=12000
DOENAAI_AI_DB_BATCH_MAX_TABLES=6
DOENAAI_AI_CHECKPOINTS_ENABLED=true
DOENAAI_AI_CHECKPOINT_MAX_AGE_SECONDS=86400
```

일반 분석·DB 묶음·종합에서 검증을 마친 결과만 `ai-server/.analysis-checkpoints/`에 보관한다.
폴더는 현재 사용자 전용 0700, 파일은 0600이며 내부 키로 서명해 변조된 값을 재사용하지 않는다.
원본 요청이나 연결 설정은 보관하지 않지만 결과 자체는 비공개 자료일 수 있으므로 공유하지 않는다.
이 폴더는 Git과 저장소 수집에서 제외한다. 파일은 최대 1,000개, 개별 최대 1MB로 제한하며
저장 실패는 원문 없는 경고로 남긴다. 만료된 파일을 자동 삭제하지는 않으며 재사용만 중지한다.

다시 요청했을 때 프로젝트·요구사항·Git/DB 근거·관계·모델 파일 digest·프롬프트·JSON Schema가
같은 검증 결과만 재사용한다. DB의 `capturedAt`만 비교에서 제외한다.
모델 digest를 확인하지 못하거나 서명·만료·형식·현재 근거 검사가 실패하면 새로 분석한다.
미완료 응답은 보관하지 않으며 실패 후에는 마지막으로 완료한 묶음 다음부터 다시 처리할 수 있다.
서버 재시작 후에도 같은 로컬 폴더와 내부 키가 있으면 이 동작을 유지한다.

웹의 작업 내역에는 `AI DB 분할 검토`, `AI DB 분할 결과 종합`, `저장 결과 재사용`을 표시한다.
토큰 생성률을 완료율로 바꾸지는 않는다. 기존 실패 작업은 완료로 고치지 않으며 새 작업을 요청해야 한다.
이 변경 이전에 실패한 작업은 중간 결과가 없으므로 첫 새 분석에서 일반 단계부터 실행해야 한다.
전체 소요 시간은 늘 수 있다. 각 모델 호출은 기존 900초 제한을 유지하므로 단독 테이블이나
종합 입력이 여전히 너무 큰 경우 추가 설계가 필요하며 모든 규모에서 성공을 보장하지 않는다.

### DB 변경안의 유형·필수 여부와 구형 결과

새 응답의 `proposedChanges`는 인덱스 추가를 `ADD_INDEX`로 구분한다.
각 항목의 `necessity`는 요구사항 충족에 꼭 필요한 `REQUIRED`와 선택적인 `RECOMMENDED` 중 하나다.
권장 개선만으로 `CHANGE_REQUIRED`를 확정하지 못하며, `NO_CHANGE`의 권장 사항은 설명에 남긴다.
같은 컬럼·테이블 추가의 중복은 추론·수정본 승인·학습 계약 검사에서 거부한다.
인덱스 대상 컬럼은 관찰된 구조 또는 같은 계획의 새 컬럼 추가안에 있어야 한다.

구형 결과의 `OTHER`와 없는 `necessity`는 조회 시 그대로 유지하고 임의로 필수라고 바꾸지 않는다.
새 추론 결과에는 구분을 요구한다. 저장 결과 재사용도 현재 검사를 다시 통과해야 하며,
프롬프트·스키마가 달라지면 이전 DB 단계 결과를 그대로 재사용하지 않는다.
중복 원본은 조회할 수 있지만 새로 원본 승인할 수는 없다. 이미 저장된 승인·학습 자료를 수정하지 않는다.

공통 DB 지침에는 SQL `NULL`·JSON 내부 `null`·키 생략의 구분, 관찰되지 않은 제약의 단정 금지,
기존 저장 방식의 활용 가능성, 성능 측정 없이 인덱스를 필수라고 확정하지 않는 원칙을 추가했다.
특정 프로젝트·업무 용어·테이블을 고정하지 않는다. 이 지침은 모델 판단의 개선 수단이지
모든 문장의 의미를 프로그램이 증명하는 검사는 아니다. 최종 승인자의 근거 검토는 계속 필요하다.

새 분류가 있는 학습 사례는 수신 서버의 `DATABASE_CHANGE_SEMANTICS_V2` 지원을 먼저 확인한다.
지원하지 않으면 공유 폴더에 게시하기 전에 중단하므로 AI 학습 서버도 업데이트·재시작해야 한다.
프롬프트·응답 스키마가 바뀌어 학습·평가 계약 해시도 달라지며, 비교 평가는 같은 계약으로 다시 준비한다.

## 테스트

Ollama를 mock하는 단위 테스트:

```bash
pytest
```

Ollama 응답은 자동화 테스트에서 mock하며,
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
