# doenaai Mac Studio 작업 진행상황 및 PC 이동 인수인계

최종 갱신: 2026-08-05

이 문서는 Mac Studio에서 진행한 `doenaai` 프로젝트 작업 내용, 현재 검증 상태,
미완료 항목과 다른 PC에서 이어서 작업하는 방법을 정리한다.

실제 토큰, 비밀번호, Supabase 키, Git credential, DB credential, 내부 AI API
key는 이 문서에 기록하지 않는다.

## 한눈에 보는 현재 상태

```text
프런트엔드 실행 확인                  완료
Spring Boot Gradle build/실행 확인    완료
Ollama 애플리케이션 설치              완료
Devstral 모델 설치                    완료, devstral:latest 14GB
FastAPI/Ollama 호출 코드              구현 완료
FastAPI mock 단위 테스트              완료, 17개 통과
실제 Devstral 단독 추론               완료
FastAPI -> 실제 Devstral 통합         완료
실제 health/인증 실패/API 응답 검증    완료
실제 장애/미설치 모델/timeout 검증     완료
범용 기술 스택 정적 탐지              구현 완료
실제 저장소 기술 탐지                 검증 완료
analysis_jobs Worker                  미구현
Git checkout과 탐지기 자동 연결       미구현
Spring Boot -> FastAPI client         미구현
분석 결과 DB 저장                     미구현
```

현재는 다음 두 기능이 각각 구현된 상태다.

```text
1. 로컬 저장소 -> 기술 스택 정적 탐지 -> sourceSummary
2. sourceSummary -> FastAPI -> Ollama -> 구조화 AI 응답
```

두 기능을 `analysis_jobs`와 연결해 자동 실행하는 Worker는 아직 없다.

## Git 상태

문서 작성 직전 확인한 상태:

```text
branch: main
HEAD: cd95fb9
commit: Mac Studio AI 작업 진행상황 인수인계 문서 추가
working tree: clean
local origin/main 대비: ahead 0, behind 0
```

주요 최근 commit:

```text
cd95fb9 Mac Studio AI 작업 진행상황 인수인계 문서 추가
5e99ad5 AI 서버 Ollama 연동 및 범용 기술 스택 탐지 추가
c024751 ai서버 인수인계문서 추가
f90b046 Git 연결 설정 및 작업 인수인계 문서 추가
a117fc8 프로젝트 응답에 소스 정보 추가 누락된 사항 추가 커밋
2eacb21 프로젝트 응답에 소스 정보 추가
```

원격 저장소:

```text
https://github.com/KimAgHuge/hackathon-ai.git
```

로컬 remote-tracking ref는 문서 작성 시점의 HEAD와 동기화되어 있었다. 다만
실시간 `git ls-remote` 조회는 현재 Mac의 GitHub username/token 인증 실패로
확인하지 못했다. 다른 PC에서 반드시 다음을 확인한다.

```bash
git fetch origin
git log -5 --oneline
git status -sb
```

이 문서의 최초 버전은 `cd95fb9`에 포함되어 있다. 2026-08-05 실제
Devstral 통합 검증 결과는 현재 문서 수정분에 포함되므로 다른 PC에
전달하려면 별도 commit과 push가 필요하다.

## 이 Mac의 확인된 환경

```text
Chip: Apple M1 Max
Memory: 64 GB
Architecture: arm64
macOS: 26.5.2
Disk: 926 GiB, 약 429 GiB 사용 가능
System Python: 3.9.6
Ollama client: 0.32.5
```

Java와 IntelliJ 관련 확인:

- 시스템 Java는 OpenJDK 26을 사용 중이었다.
- backend의 Gradle 설정은 Java toolchain 25를 요구한다.
- `backend/gradlew`에는 macOS 실행 권한을 추가했다.
- `./gradlew build`가 성공했다.
- IntelliJ에서는 `backend` 디렉터리를 Gradle 프로젝트로 연결해야 한다.
- `build.gradle` 파일 자체를 실행 구성의 project path로 사용하면 안 된다.

## Ollama와 Devstral 상태

Ollama 애플리케이션과 CLI 설치:

```text
/opt/homebrew/bin/ollama
client version: 0.32.5
```

최종 확인 상태:

```text
NAME               ID              SIZE
devstral:latest    9bd74193e939    14 GB
```

`ollama list`와 localhost API에서 모델이 확인됐고, 구조화 JSON 단독 추론이
성공했다.

다른 PC 또는 이 Mac에서 다시 진행할 때:

```bash
open -a Ollama
curl http://127.0.0.1:11434/api/tags
ollama pull devstral
ollama list
```

`ollama pull`은 다른 PC에서 모델이 없을 때만 실행한다. Ollama 모델 파일은
Git에 포함되지 않는다.

설치 완료 조건:

```text
ollama list에 devstral 표시
curl /api/tags에 devstral 표시
ollama run devstral로 단독 추론 성공
```

Ollama는 인증이 없는 로컬 runtime이므로 다음 설정을 사용하지 않는다.

```text
OLLAMA_HOST=0.0.0.0:11434
```

Ollama는 기본값인 `127.0.0.1:11434`에만 binding한다.

## Mac에서 구현한 AI 서버

### 환경설정

파일:

```text
ai-server/app/config.py
ai-server/.env.example
```

환경변수:

```text
DOENAAI_OLLAMA_URL=http://127.0.0.1:11434
DOENAAI_AI_MODEL=devstral
DOENAAI_AI_INTERNAL_API_KEY=
DOENAAI_AI_REQUEST_TIMEOUT_SECONDS=300
DOENAAI_AI_KEEP_ALIVE=30m
DOENAAI_AI_MAX_VALIDATION_RETRIES=1
```

규칙:

- 실제 내부 API key는 최소 32자
- 실제 값은 `.env.example`, 문서, Git에 기록하지 않음
- Ollama URL과 모델 이름을 코드에 하드코딩하지 않고 환경변수로 교체 가능
- 설정 객체는 process 안에서 cache해 재사용

### FastAPI 시작점과 요청 로그

파일:

```text
ai-server/app/main.py
```

구현 내용:

- health와 analysis router 등록
- `X-Request-Id` 검사 또는 생성
- 요청 처리시간과 HTTP 상태 기록
- 원문 payload, API key, 환경변수를 로그에 출력하지 않음
- Pydantic `422` 응답에서 원본 입력값 제거

### 내부 API key 인증

파일:

```text
ai-server/app/dependencies.py
```

header:

```text
X-Internal-Api-Key
```

비교:

```python
secrets.compare_digest()
```

인증 실패 시 `401`을 반환하며 Ollama를 호출하지 않는다.

### API 계약

FastAPI process health:

```text
GET /health
인증 불필요
Ollama 호출 없음
```

Ollama와 설정 모델 health:

```text
GET /api/models/health
내부 API key 필요
Ollama 모델 목록 실제 조회
```

구조화 프로젝트 분석:

```text
POST /api/analysis/project
내부 API key 필요
```

분석 요청 예:

```json
{
  "projectId": "00000000-0000-0000-0000-000000000000",
  "sourceSummary": {
    "frameworks": [],
    "technologies": [],
    "languages": [],
    "buildTools": [],
    "components": []
  },
  "databaseSummary": {
    "type": "UNKNOWN",
    "tables": []
  },
  "relations": []
}
```

분석 응답:

```json
{
  "summary": "프로젝트 구조에 대한 요약",
  "frameworks": [],
  "riskLevel": "UNKNOWN",
  "riskScore": 0,
  "impactAreas": [],
  "evidences": [],
  "recommendations": []
}
```

검증 규칙:

- API JSON은 camelCase
- `riskLevel`: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`, `UNKNOWN`
- `riskScore`: 0~100
- LLM 출력에 선언되지 않은 field 금지
- LLM 응답을 그대로 반환하지 않고 Pydantic으로 재검증

### 민감정보 차단

파일:

```text
ai-server/app/models/analysis.py
```

분석 요청의 모든 중첩 object와 array에서 다음과 같은 key를 검사한다.

```text
password
token
apiKey
privateKey
passphrase
credential
ciphertext
authorization
secret
```

발견되면 `422`를 반환하고 모델을 호출하지 않는다. 오류 응답에도 실제 입력값을
포함하지 않는다.

### Ollama 비동기 호출

파일:

```text
ai-server/app/services/llm_service.py
```

구현 내용:

- Ollama Python `AsyncClient`
- `format=ProjectAnalysisResponse.model_json_schema()`
- `temperature=0`
- `keep_alive` 환경설정
- 구조화 JSON Pydantic 검증
- 잘못된 모델 JSON 제한 재시도
- prompt 전체와 source 원문 로그 금지

오류 처리:

```text
내부 API key 실패       401
잘못된 요청             422
잘못된 모델 JSON        502
Ollama 연결 실패        503
설정 모델 미설치        503
모델 timeout            504
```

### 공유 JSON Schema

파일:

```text
shared/schemas/project-scan.schema.json
```

프로젝트 분석 응답의 초기 공유 계약이다. 기존
`shared/schemas/impact-analysis.schema.json`은 수정하지 않았다.

## Mac에서 구현한 범용 기술 스택 탐지

### 설계 원칙

기술 스택을 프런트 화면에서 사용자가 선택하지 않는다.

```text
고정 commit 저장소
  -> manifest와 source file 정적 검사
  -> 모듈 경계 탐지
  -> 언어, framework, runtime, build tool 분류
  -> file/dependency evidence 저장
  -> sourceSummary 생성
```

LLM이 파일 목록만 보고 기술을 추측하지 않는다. 탐지기가 dependency와 marker
file에서 사실을 먼저 추출한다.

### 주요 파일

```text
ai-server/app/analyzers/technology_detector.py
ai-server/app/analyzers/manifest_parsers.py
ai-server/app/analyzers/technology_catalog.py
ai-server/app/analyzers/scan_repository.py
ai-server/app/models/source_facts.py
```

### 지원하는 manifest와 생태계

```text
package.json                         Node.js / JavaScript / TypeScript
pom.xml                              Maven / JVM
build.gradle, build.gradle.kts       Gradle / JVM
pyproject.toml, requirements*.txt    Python
Pipfile, environment.yml             Python
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

### 기술 분류

```text
FRONTEND
BACKEND
FULLSTACK
MOBILE
DESKTOP
SERVER_VIEW
BUILD
STATE_MANAGEMENT
DATABASE
MESSAGING
SECURITY
OBSERVABILITY
DATA
AI_ML
ORM
CMS
TEST
UI
RUNTIME
LIBRARY
INFRASTRUCTURE
UNKNOWN
```

### 확장 방식

탐지 규칙은 `technology_catalog.py`에 데이터 형태로 등록한다.

```python
TechnologyRule(
    "새 기술 이름",
    TechnologyCategory.BACKEND,
    dependencies("node", "dependency-name"),
)
```

새 기술을 추가할 때 전체 탐색 코드를 수정할 필요가 없다.

카탈로그에 등록되지 않은 dependency도 버리지 않는다.

```json
{
  "unclassifiedDependencies": [
    "custom-company-framework"
  ]
}
```

### 모노레포 처리

manifest가 있는 디렉터리를 모듈 후보로 만들고, 각 파일은 가장 가까운 모듈에
할당한다.

```text
repository/
  frontend/package.json
  backend/pom.xml
  worker/pyproject.toml
```

위 구조는 `frontend`, `backend`, `worker` 세 모듈로 분석한다.

manifest가 없는 Vanilla JavaScript 또는 infrastructure directory도 source와
marker file을 이용해 보조 탐지한다.

### 정적 분석 보안

분석 대상 저장소의 다음 명령은 실행하지 않는다.

```text
npm install
npm scripts
./gradlew
mvn
Python setup.py
기타 repository 내부 실행 파일
```

제외 directory:

```text
.git
node_modules
.venv
vendor
target
build
dist
.gradle
.idea
```

적용 제한:

- 심볼릭 링크를 따라가지 않음
- 최대 파일 수 제한
- 최대 탐색 깊이 제한
- manifest 최대 크기 제한
- manifest 하나가 깨져도 전체 scan을 실패시키지 않고 warning으로 보존

### 현재 저장소 실제 탐지 결과

Mac에서 다음 명령으로 실제 repository를 검사했다.

```bash
cd ai-server
source .venv/bin/activate
python -m app.analyzers.scan_repository .. --source-summary
```

확인된 주요 결과:

```text
ai-server
  Python
  FastAPI
  Ollama Python Client
  Pydantic
  Uvicorn
  pytest
  pip

backend
  Java
  Spring Boot
  Spring Security
  Spring Data JPA
  PostgreSQL JDBC
  Oracle JDBC
  JGit
  Gradle

frontend
  JavaScript
  Vue
  HTML
  Vite
  Pinia
  Vue Router
  Tailwind CSS
  Axios
  Supabase JavaScript Client
  npm

supabase
  SQL
```

탐지 warning은 없었다.

## 테스트 상태

가상환경:

```text
ai-server/.venv
Python 3.9.6
```

`.venv`는 Git ignore 대상이며 다른 PC로 복사하지 않는다.

실행:

```bash
cd ai-server
source .venv/bin/activate
pytest
```

최종 결과:

```text
17 passed
No broken requirements found
git diff --check 통과
```

테스트 범위:

```text
FastAPI health
내부 API key 성공/실패
민감정보 차단
camelCase 응답
Ollama 장애와 timeout
잘못된 모델 JSON과 재시도
설정 모델 미설치
다중 생태계 모노레포 탐지
깨진 manifest warning 처리
모듈 경계와 evidence
```

위 `17 passed`는 Ollama를 mock한 자동화 테스트다. 이와 별도로 2026-08-05에
실제 Devstral을 사용한 HTTP 통합 테스트도 성공했다.

## 다른 PC에서 소스 준비

### 1. Git 확인

```bash
git clone https://github.com/KimAgHuge/hackathon-ai.git
cd hackathon-ai
git log -5 --oneline
git status -sb
```

기존 clone이 있으면:

```bash
git status --short
git pull --ff-only
```

사용자 변경사항이 있는 dirty worktree에서는 pull 전에 변경사항을 임의로
삭제하지 않는다.

### 2. Python 환경

권장 Python은 3.11 이상이다. 이 Mac에서는 시스템 Python 3.9.6으로도 테스트가
통과했지만, 새로운 PC에서는 OS 기본 Python 대신 프로젝트 전용 Python과
가상환경을 권장한다.

```bash
cd ai-server
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
pytest
```

Windows PowerShell:

```powershell
cd ai-server
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
pytest
```

### 3. 기술 탐지 확인

macOS/Linux:

```bash
python -m app.analyzers.scan_repository .. --source-summary
```

Windows PowerShell:

```powershell
python -m app.analyzers.scan_repository .. --source-summary
```

분석 대상 repository code를 실행하지 않고 JSON 결과가 출력되어야 한다.

### 4. Ollama를 실행할 PC

Ollama와 모델 파일은 Git으로 이동하지 않는다. 실제 AI 추론을 실행할 PC마다
별도 설치가 필요하다.

```bash
ollama --version
ollama pull devstral
ollama list
```

다른 PC가 AI runtime host가 아니라 Spring Boot 개발 PC라면 Ollama를 그 PC에
반드시 설치할 필요는 없다. 최종 구조에서는 Spring Boot가 Mac Studio의 인증된
FastAPI를 사설 LAN으로 호출한다.

### 5. FastAPI 실행

실제 비밀값은 terminal session 또는 별도 secret 관리 방식으로 설정한다.

```bash
export DOENAAI_OLLAMA_URL=http://127.0.0.1:11434
export DOENAAI_AI_MODEL=devstral
export DOENAAI_AI_INTERNAL_API_KEY='실제 로컬 키'
export DOENAAI_AI_REQUEST_TIMEOUT_SECONDS=300
export DOENAAI_AI_KEEP_ALIVE=30m

uvicorn app.main:app --host 0.0.0.0 --port 8000
```

FastAPI를 `0.0.0.0:8000`으로 실행하더라도 사설 LAN에서만 접근시키고 인터넷
port forwarding을 설정하지 않는다.

## 2026-08-05 실제 Devstral 통합 검증 결과

실제 로컬 모델과 FastAPI를 사용해 다음을 확인했다.

```text
ollama list                         devstral:latest 14GB 확인
Ollama /api/chat                   구조화 한국어 JSON 응답 성공
GET /health                        HTTP 200
GET /api/models/health             HTTP 200, model=devstral, available=true
POST /api/analysis/project, key 없음  HTTP 401
POST /api/analysis/project, key 있음  HTTP 200
Pydantic/JSON Schema 검증           통과
```

실제 모델 단독 추론:

```text
입력: Spring Controller와 Service의 관계를 한국어 JSON으로 설명
결과: relationship, explanation key를 가진 JSON 반환
총 처리시간: 약 27초
초기 모델 load: 약 7.3초
```

FastAPI 실제 분석:

```text
입력 framework: Vue, Spring Boot, FastAPI
입력 language: JavaScript, Java, Python
입력 database: PostgreSQL
응답 riskLevel: UNKNOWN
응답 riskScore: 0
HTTP status: 200
FastAPI 측정 처리시간: 약 23.25초
```

로그에는 request ID, HTTP method/path/status, 처리시간, Ollama 호출 상태만
나타났고 API key와 prompt 전체는 출력되지 않았다.

임시 통합 테스트 key는 process 환경변수로만 사용했고 파일에 저장하지
않았다. 검증 후 FastAPI 임시 process는 정상 종료했다.

### 재검증 명령

### Devstral 단독 호출

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

### FastAPI health

```bash
curl http://127.0.0.1:8000/health
```

### 인증 실패

API key 없이 호출:

```bash
curl -X POST http://127.0.0.1:8000/api/analysis/project \
  -H "Content-Type: application/json" \
  -d '{
    "projectId": "00000000-0000-0000-0000-000000000000",
    "sourceSummary": {},
    "databaseSummary": {},
    "relations": []
  }'
```

예상:

```text
HTTP 401
Ollama 호출 없음
```

### 실제 FastAPI 분석

내부 API key는 command에 직접 적지 않고 환경변수를 사용한다.

```bash
curl -X POST http://127.0.0.1:8000/api/analysis/project \
  -H "Content-Type: application/json" \
  -H "X-Internal-Api-Key: ${DOENAAI_AI_INTERNAL_API_KEY}" \
  -d '{
    "projectId": "00000000-0000-0000-0000-000000000000",
    "sourceSummary": {
      "frameworks": [],
      "technologies": [],
      "languages": ["Java"],
      "buildTools": ["Gradle"],
      "components": []
    },
    "databaseSummary": {
      "type": "UNKNOWN",
      "tables": []
    },
    "relations": []
  }'
```

확인 항목:

```text
riskLevel 허용값
riskScore 0~100
Pydantic 검증 통과
응답 camelCase
로그에 prompt/source/API key 미노출
```

### 실제 장애 주입 검증 결과

임시 FastAPI process를 서로 다른 port에서 잘못된 설정으로 실행해
다음을 실제 HTTP로 확인했다.

```text
잘못된 Ollama URL       -> HTTP 503, MODEL_UNAVAILABLE
설치되지 않은 모델     -> HTTP 503, MODEL_UNAVAILABLE
0.001초 모델 timeout     -> HTTP 504, MODEL_TIMEOUT
```

모델 JSON schema 위반 후 제한 재시도와 `502` 변환은 mock 자동화
테스트로 검증했다. Ollama에 JSON Schema를 직접 전달하는 정상 호출에서는
스키마 위반 응답을 의도적으로 유발하지 않았다.

## 아직 구현되지 않은 자동 분석 파이프라인

현재 프런트에서 분석 시작:

```text
POST /api/projects/{projectId}/analysis-jobs
  -> Spring Boot 권한 확인
  -> 저장 Git 연결 재검증
  -> repositoryUrl, branch, commitSha snapshot
  -> analysis_jobs.PROJECT_SCAN/PENDING 생성
```

현재는 여기서 멈춘다.

목표:

```text
analysis_jobs.PENDING
  -> Worker claim
  -> RUNNING
  -> 고정 commit checkout
  -> TechnologyDetector.scan()
  -> DB metadata 준비
  -> sourceSummary/databaseSummary/relations 생성
  -> LlmService 또는 FastAPI 분석 API 호출
  -> analysis_results 저장
  -> COMPLETED
```

필요한 후속 구현:

1. `007_analysis_pipeline.sql`
2. Worker claim, lease, heartbeat, retry
3. 분석 event/log 구조
4. Git clone과 고정 commit checkout
5. `TechnologyDetector`를 Worker에 연결
6. DB metadata collector
7. Java/Vue/MyBatis/SQL의 클래스·메서드·API·테이블 관계 분석
8. 기술 탐지 결과와 관계 facts를 FastAPI 분석 요청으로 변환
9. Spring Boot의 인증된 FastAPI client 또는 Worker 내부 service 호출 방식 결정
10. `analysis_results` 저장
11. 프런트 진행률 polling과 결과 화면 연결

실제 Devstral/FastAPI 정상 통합 검증이 완료됐으므로 다음 단계에서
`007_analysis_pipeline.sql`과 Worker 구조 설계를 진행할 수 있다. 기존
`001`~`006` migration은 임의로 수정하거나 재실행하지 않는다.

## 현재 구현의 의도적인 제한

- 기술 탐지 카탈로그에 세상의 모든 framework 이름이 들어 있는 것은 아님
- 알 수 없는 dependency는 `unclassifiedDependencies`에 보존
- 선언 버전과 실제 lockfile 확정 버전을 분리할 field는 있으나 모든 생태계의
  resolved version parser는 아직 구현되지 않음
- framework 탐지와 언어 탐지는 구현됐지만 class/method/API/SQL 호출 관계는
  아직 분석하지 않음
- DB driver dependency 탐지는 실제 DB metadata 수집을 의미하지 않음
- sourceSummary 생성과 LLM 호출은 구현됐지만 Worker가 두 기능을 자동 연결하지 않음
- 실제 Devstral inference와 FastAPI 정상 경로는 검증됐지만 Worker 연결은 없음

## 다음 PC에서 권장하는 작업 순서

1. Git에서 `5e99ad5`, `cd95fb9`와 이 문서의 최종 갱신 commit 확인
2. `ai-server` 가상환경 생성과 `17 passed` 재현
3. 범용 기술 탐지 CLI로 현재 저장소 결과 재현
4. AI runtime PC에서 Ollama/Devstral health 재확인
5. `007_analysis_pipeline.sql`과 Worker claim/heartbeat 구조 설계
6. Git checkout -> TechnologyDetector -> LLM -> analysis_results 최소 수직 흐름 구현

## 변경 금지 및 보안 주의

- 실제 비밀값을 코드, 문서, 로그, Git에 넣지 않기
- `.env`, `.venv`, 모델 파일을 commit하지 않기
- Git token, DB password, private key를 LLM prompt에 넣지 않기
- Ollama를 외부 network에 직접 공개하지 않기
- 기존 Supabase migration을 임의로 수정하거나 재실행하지 않기
- 분석 대상 repository의 build/install script를 자동 실행하지 않기
- 사용자 변경사항이 있는 dirty worktree를 임의로 초기화하지 않기

## 관련 문서

- `docs/PROJECT_OVERVIEW.md`
- `docs/MAC_STUDIO_AI_HANDOFF.md`
- `docs/CODEX_HANDOFF.md`
- `docs/ARCHITECTURE.md`
- `docs/GIT_REPOSITORY_CONNECTION.md`
- `ai-server/README.md`
