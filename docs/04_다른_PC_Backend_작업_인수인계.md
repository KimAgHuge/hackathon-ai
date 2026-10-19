# 4. 다른 PC Backend 작업 인수인계

> 최신 Backend 분석 처리 및 실행 방법은
> `docs/06_2026-10-12_분석_Worker_구현_인수인계.md`를 우선 참고한다.
> 9월 실제 연결 기록은 `docs/05_2026-09-23_MAC_STUDIO_작업_인수인계.md`에 있다.

기준일: 2026-08-10

이 문서는 다른 PC에서 Spring Boot Backend 작업을 이어갈 담당자가 무엇을 어떤
순서로 구현해야 하는지 설명하는 실행용 인수인계 문서다. 전체 제품 설명은
`docs/01_프로젝트_개요.md`, 시스템 간 연결 구조는
`docs/02_시스템_아키텍처.md`, 전체 완료 상태는
`docs/03_현재_진행상황.md`를 함께 참고한다.

## 1. 다른 PC가 담당하는 역할

전체 연결은 다음과 같다.

```text
Frontend
  -> Spring Boot Backend (다른 PC, port 8080)
  -> FastAPI AI 분석/추론 서버 (Mac Studio, port 8000)
  -> Ollama + Devstral (Mac Studio localhost, port 11434)
```

다른 PC는 다음 작업을 담당한다.

- Spring Boot의 조직/tenant, 요구사항, 견적 API 구현
- 기존 프로젝트를 조직에 연결하고 모든 접근에 tenant 검증 적용
- Spring Boot에서 Mac Studio FastAPI를 호출하는 내부 client 구현
- `analysis_jobs`를 처리할 Worker 상태와 Backend orchestration 설계
- Backend 단위/통합 테스트와 Frontend가 사용할 API 계약 제공

Mac Studio는 다음 작업을 담당한다.

- Ollama와 `devstral:latest` 실행
- FastAPI와 범용 기술 스택 탐지기 실행
- AI 구조화 응답 검증
- 이후 FastAPI 내부의 상세 정적 분석과 AI 처리 기능 구현

다른 PC에는 Ollama나 Devstral을 다시 설치할 필요가 없다. Spring Boot는 같은
사설 LAN의 Mac Studio `:8000`만 호출한다. Ollama `:11434`는 Mac Studio의
`127.0.0.1`에만 두며 다른 PC나 인터넷에 공개하지 않는다.

## 2. 시작 기준

인수인계 시점 기준 상태는 다음과 같다.

```text
branch: main
이 문서 작성 직전 source baseline: 240e778
작업 시작 기준: 이 인수인계 문서가 포함된 최신 main commit
007 migration: Supabase 적용 완료(사용자 확인)
008 migration: 아직 작성하지 않음
```

다른 PC에서는 먼저 최신 `main`을 받는다.

```powershell
git checkout main
git pull origin main
git status -sb
git rev-parse --short HEAD
```

출력 commit이 `240e778`보다 이전이거나 이 인수인계 파일이 없으면 작업을
시작하지 말고 pull 상태를 다시 확인한다. 다른 사람이 수정 중인 파일을
초기화하거나 기존 migration을 수정하지 않는다.

## 3. Backend 실행 준비

Backend는 Java 25와 프로젝트의 Gradle Wrapper를 사용한다.

```powershell
cd backend
java -version
.\gradlew.bat test
.\gradlew.bat bootRun
```

IntelliJ에서는 저장소 전체가 아니라 `backend` 디렉터리의 `build.gradle`을 Gradle
프로젝트로 연결하고 Gradle JVM을 JDK 25로 설정한다.

로컬 설정 파일을 만든다.

```powershell
Copy-Item src/main/resources/application-local.yml.example `
  src/main/resources/application-local.yml
```

`application-local.yml`에는 다음 값을 설정한다.

- Supabase PostgreSQL Session Pooler JDBC URL, username, password
- Supabase Auth issuer/JWKS URL과 현재 signing algorithm
- Supabase 프로젝트 URL
- Mac Studio FastAPI 주소

환경변수는 실행 중인 PowerShell에 다음과 같이 주입한다.

```powershell
$env:SPRING_PROFILES_ACTIVE='local'
$env:DOENAAI_CREDENTIAL_ENCRYPTION_KEY='기존 Backend와 동일한 실제 암호화 키'
$env:DOENAAI_AI_BASE_URL='http://MAC_STUDIO_LAN_IP:8000'
$env:DOENAAI_AI_INTERNAL_API_KEY='Mac Studio FastAPI와 동일한 실제 내부 키'
.\gradlew.bat bootRun
```

`DOENAAI_AI_INTERNAL_API_KEY`는 `doenaai_` 접두어를 포함한 전체 문자열이 하나의
키다. Mac Studio와 다른 PC에서 키 생성기를 각각 실행하지 말고, Mac Studio에
설정한 것과 완전히 같은 키를 사용한다.

실제 비밀값이 들어간 `application-local.yml`, `.env`, shell 기록을 commit하지
않는다. API key, DB password, Git token, 암호화 키를 문서나 로그에 남기지 않는다.

## 4. Mac Studio 연결 확인

### Mac Studio에서 실행할 것

Mac Studio 담당자는 Ollama 앱과 FastAPI를 실행한다.

```bash
open -a Ollama
curl http://127.0.0.1:11434/api/tags

cd ai-server
source .venv/bin/activate
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Mac Studio 방화벽에서는 사설 LAN의 다른 PC가 TCP `8000`에 접근할 수 있어야
한다. 공유기 port forwarding이나 인터넷 공개는 하지 않는다.

### 다른 PC에서 확인할 것

`MAC_STUDIO_LAN_IP`를 실제 사설 IP로 바꿔 실행한다.

```powershell
Test-NetConnection MAC_STUDIO_LAN_IP -Port 8000
curl.exe http://MAC_STUDIO_LAN_IP:8000/health
curl.exe http://MAC_STUDIO_LAN_IP:8000/api/models/health `
  -H "X-Internal-Api-Key: $env:DOENAAI_AI_INTERNAL_API_KEY"
```

완료 기준:

- `Test-NetConnection`의 `TcpTestSucceeded`가 `True`
- `/health`가 HTTP 200
- `/api/models/health`가 HTTP 200
- 응답에서 Ollama와 `devstral`을 사용할 수 있음
- 키를 빼거나 틀리게 보내면 HTTP 401

연결이 안 되면 코드 구현 전에 Mac Studio의 IP, FastAPI `--host 0.0.0.0`, 양쪽
방화벽, 같은 LAN 사용 여부부터 확인한다. 다른 PC에서 `:11434` 접속을 시도하지
않는다.

## 5. 작업 순서 요약

다른 PC에서는 다음 순서를 지킨다.

```text
0. 최신 main/007 적용 상태 확인
1. 조직 및 tenant API
2. 기존 Project에 organization 연결
3. 요구사항과 요구사항 버전 API
4. 견적, 작업 항목, 검토와 공개 API
5. Spring Boot -> Mac Studio FastAPI client
6. 008 분석 파이프라인 schema와 최소 Worker orchestration
7. Frontend 실제 API 연결 지원
```

조직/tenant 검증 없이 요구사항이나 견적 API부터 만들면 다른 회사의 데이터가
섞일 수 있다. 따라서 1~2단계를 먼저 완료한다. `008`은 조직과 요구사항 Backend
연결 및 AI client 계약이 끝난 뒤 작성한다.

## 6. 작업 0: 007 적용 상태 확인

`supabase/migrations/007_organization_requirement_estimate.sql`은 이미 Supabase SQL
Editor에 적용했다. 다시 실행하거나 내용을 수정하지 않는다. 다음 항목만 읽기
query로 확인한다.

```sql
select table_name
from information_schema.tables
where table_schema = 'public'
  and table_name in (
    'organizations',
    'organization_members',
    'requirements',
    'requirement_versions',
    'estimates',
    'estimate_items',
    'estimate_reviews'
  )
order by table_name;

select column_name, is_nullable, data_type
from information_schema.columns
where table_schema = 'public'
  and table_name = 'projects'
  and column_name = 'organization_id';
```

확인할 내용:

- 신규 7개 table이 존재함
- `projects.organization_id`가 존재함
- 기존 프로젝트의 `organization_id`는 아직 `NULL`일 수 있음
- 신규 7개 table의 RLS가 활성화되어 있음

기존 프로젝트에 어떤 조직을 배정할지는 실제 조직 UUID를 확인한 뒤 결정한다.
임의 UUID나 추측한 조직으로 일괄 update하지 않는다.

## 7. 작업 1: 조직 및 tenant API 구현

권장 package 구조:

```text
backend/src/main/java/com/doenaai/organization/
  controller/
  dto/
  entity/
  repository/
  service/
```

최소 API:

```text
POST   /api/organizations
GET    /api/organizations
GET    /api/organizations/{organizationId}
GET    /api/organizations/{organizationId}/members
POST   /api/organizations/{organizationId}/members
PUT    /api/organizations/{organizationId}/members/{userId}
DELETE /api/organizations/{organizationId}/members/{userId}
```

반드시 지킬 규칙:

- 조직 생성자와 `OWNER` 구성원 생성은 한 transaction에서 처리
- 로그인 사용자가 `ACTIVE` 구성원인 조직만 조회
- 조직 구성원 추가/변경/삭제는 조직 `OWNER` 또는 `ADMIN`만 허용
- client가 보낸 `organizationId`를 신뢰하지 않고 DB membership을 항상 확인
- 서로 다른 조직의 ID를 조합한 요청은 `403 Forbidden` 또는 존재 은닉 정책에
  따른 `404 Not Found`로 일관되게 처리
- slug 중복은 `409 Conflict`로 변환
- 인증 없음은 `401 Unauthorized`

테스트 완료 기준:

- 조직 생성 시 생성자가 OWNER가 됨
- 조직 생성 중 구성원 저장이 실패하면 전체 rollback
- A 조직 사용자가 B 조직과 구성원을 조회/수정할 수 없음
- 비활성 구성원은 조직 데이터를 조회할 수 없음
- OWNER/ADMIN/MEMBER 권한 차이가 검증됨

## 8. 작업 2: Project에 organization 연결

현재 `Project` entity와 DTO에는 `007`로 추가된 `organization_id` 연결이 없다.
다음을 수정한다.

```text
backend/src/main/java/com/doenaai/project/entity/Project.java
backend/src/main/java/com/doenaai/project/dto/ProjectCreateRequest.java
backend/src/main/java/com/doenaai/project/dto/ProjectResponse.java
backend/src/main/java/com/doenaai/project/dto/ProjectUpdateRequest.java
backend/src/main/java/com/doenaai/project/repository/ProjectAccessRepository.java
backend/src/main/java/com/doenaai/project/service/ProjectService.java
```

구현 규칙:

- 신규 프로젝트 생성 시 `organizationId`를 필수로 받음
- 생성자는 해당 조직의 `ACTIVE` 구성원이어야 함
- 프로젝트 목록/단건/수정/연결/분석 API에 조직 범위 검증을 공통 적용
- 프로젝트 구성원의 조직 소속도 함께 확인
- Project 응답에 `organizationId`를 포함
- 기존의 프로젝트 permission 검사는 제거하지 않고 tenant 검사를 추가

기존 프로젝트 이관은 조직을 실제 생성한 후 명시적으로 수행한다.

```sql
-- 아래 두 UUID를 실제 확인한 값으로 교체한 뒤 대상 행을 먼저 조회한다.
select id, name, organization_id
from public.projects
where id = 'PROJECT_UUID';

update public.projects
set organization_id = 'ORGANIZATION_UUID'
where id = 'PROJECT_UUID'
  and organization_id is null;
```

모든 기존 프로젝트 이관과 Backend 적용이 끝날 때까지 DB column은 nullable로
유지한다. `NOT NULL` 변경이 필요하면 기존 `007`을 수정하지 않고 후속 migration에
포함한다.

테스트 완료 기준:

- 조직 비구성원은 프로젝트를 생성할 수 없음
- A 조직 사용자는 B 조직의 프로젝트를 조회/수정할 수 없음
- Git/DB 연결과 분석 작업 생성에도 같은 tenant 차단이 적용됨
- 정상 조직 구성원의 기존 프로젝트 CRUD test가 계속 통과함

## 9. 작업 3: 요구사항과 버전 API 구현

권장 package:

```text
backend/src/main/java/com/doenaai/requirement/
  controller/
  dto/
  entity/
  repository/
  service/
```

최소 API:

```text
POST /api/projects/{projectId}/requirements
GET  /api/projects/{projectId}/requirements
GET  /api/requirements/{requirementId}
POST /api/requirements/{requirementId}/versions
PUT  /api/requirements/{requirementId}/status
```

구현 규칙:

- 생성 시 URL의 `projectId`에서 `organizationId`를 결정하고 client 값과 조합하지
  않음
- 요구사항 원문과 정제본은 `requirement_versions` snapshot으로 보존
- 새 버전 생성 시 이전 `is_current=false`, 새 버전 `is_current=true`를 한
  transaction에서 처리
- 과거 버전의 본문은 수정하지 않음
- 분석 작업 생성 시 사용한 `requirement_version_id`를 반드시 저장
- CUSTOMER는 허용된 프로젝트의 고객 공개 데이터만 조회
- PM/PL은 구체화와 상태 변경, DEVELOPER는 기술 검토 등 역할별 권한을 분리
- 상태 전이는 service에서 허용 목록으로 검증

테스트 완료 기준:

- 요구사항별 현재 버전이 항상 하나임
- 동시 버전 생성 시 중복 version/current 상태가 생기지 않음
- A 조직 요구사항을 B 조직 프로젝트나 분석 작업에 연결할 수 없음
- 분석이 사용한 요구사항 버전이 이후 수정과 무관하게 재현 가능함
- 역할별 생성/조회/상태 변경 허용 범위가 검증됨

## 10. 작업 4: 견적, 작업 항목, 검토와 공개 API 구현

권장 package:

```text
backend/src/main/java/com/doenaai/estimate/
  controller/
  dto/
  entity/
  repository/
  service/
```

최소 API:

```text
POST /api/requirements/{requirementId}/estimates
GET  /api/requirements/{requirementId}/estimates
GET  /api/estimates/{estimateId}
POST /api/estimates/{estimateId}/items
PUT  /api/estimates/{estimateId}/items/{itemId}
POST /api/estimates/{estimateId}/reviews
PUT  /api/estimates/{estimateId}/status
POST /api/estimates/{estimateId}/publish
```

구현 규칙:

- 견적의 조직과 프로젝트는 요구사항에서 결정
- 견적 version은 증가시키고 과거 version을 덮어쓰지 않음
- `DRAFT`와 내부 검토 결과는 CUSTOMER에게 노출하지 않음
- 승인된 견적만 `PUBLISHED`로 전환
- 고객 DTO에서 `internal_note`, credential, 원본 소스, 내부 분석 근거를 제외
- 작업량 범위는 `minimum <= expected <= maximum`을 application에서도 검증
- 비용은 item 합계, risk buffer, total 계산 규칙을 한 곳에서 관리
- 검토/승인/공개 actor와 시간을 기록
- 같은 요구사항의 고객 공개 version 정책을 명시적으로 적용

테스트 완료 기준:

- 다른 조직의 요구사항으로 견적을 만들 수 없음
- DRAFT 견적은 CUSTOMER에게 보이지 않음
- 승인되지 않은 견적은 공개할 수 없음
- CUSTOMER 응답에 내부 field가 직렬화되지 않음
- 잘못된 작업량/기간/비용 범위를 거부함
- 역할별 작성, 검토, 승인, 공개 권한이 검증됨

## 11. 작업 5: Spring Boot에서 AI 분석/추론 서버 호출

현재 Backend 설정에는 다음 값만 선언되어 있고 실제 HTTP client는 없다.

```yaml
doenaai:
  ai-server:
    base-url: ${DOENAAI_AI_BASE_URL:http://localhost:8000}
    internal-api-key: ${DOENAAI_AI_INTERNAL_API_KEY:}
```

권장 구조:

```text
backend/src/main/java/com/doenaai/ai/
  client/AiServerClient.java
  config/AiServerProperties.java
  dto/
```

구현 범위:

```text
GET  {baseUrl}/api/models/health
POST {baseUrl}/api/analysis/project
```

구현 규칙:

- 모든 보호 API에 `X-Internal-Api-Key` header 추가
- URL과 key를 코드에 하드코딩하지 않음
- 실제 추론이 약 23초 걸렸으므로 read timeout은 개발 기준 300초 사용
- API key, prompt, 원본 source payload를 로그에 출력하지 않음
- HTTP 401, 422, 502, 503, 504와 network timeout을 서로 구분
- Backend DTO와 FastAPI camelCase JSON 계약을 명시적으로 일치시킴
- 응답을 신뢰하지 않고 필수 field와 범위를 Backend에서도 검증

테스트 완료 기준:

- mock server로 정상 응답, 인증 실패, 모델 장애, timeout을 검증
- 내부 key가 로그와 예외 응답에 노출되지 않음
- 다른 PC에서 실제 Mac Studio `/api/models/health` 호출 성공
- 최소 `POST /api/analysis/project` 실제 호출 성공

관리자 전용 Backend health endpoint를 추가할 수 있지만 실제 key나 Mac 내부
주소를 응답에 포함하면 안 된다.

## 12. 작업 6: 008 분석 파이프라인과 최소 Worker

작업 1~5가 완료된 뒤 새 파일
`supabase/migrations/008_analysis_pipeline.sql`을 작성한다. 기존 `001`~`007`을
수정하지 않는다.

최소 설계 대상:

- `worker_name` 또는 `worker_id`
- `current_step`
- `claimed_at`
- `lease_expires_at`
- `heartbeat_at`
- `retry_count`, `max_retries`, `next_retry_at`
- `failure_code`, 안전하게 정제한 `failure_message`
- 분석 상태 변경 이력을 저장할 event table
- PENDING 조회, lease 만료 조회에 필요한 index
- 동시에 한 Worker만 가져가는 atomic claim
- 중단된 RUNNING 작업의 lease 기반 복구

책임 범위는 다음처럼 나눈다.

```text
다른 PC Backend
  작업 생성/상태 조회
  tenant와 권한 검증
  claim/retry/status orchestration
  FastAPI client 호출
  결과 DB 저장

Mac Studio
  저장소 정적 분석
  TechnologyDetector
  Ollama/Devstral 추론
  구조화 AI 응답 생성
```

최소 수직 흐름:

```text
PENDING
  -> 한 Worker가 claim
  -> RUNNING과 lease/heartbeat 기록
  -> 생성 시 고정한 commit SHA 사용
  -> source/database 요약 생성
  -> Mac Studio FastAPI 호출
  -> analysis_results 저장
  -> COMPLETED
```

실패 처리:

- Mac FastAPI `503/504`와 일시 network 오류는 제한된 횟수로 재시도
- 인증 실패 `401`은 설정 오류로 분류하고 무한 재시도하지 않음
- 잘못된 계약 `422/502`는 payload/응답 문제로 기록
- lease 만료 작업만 다른 Worker가 재선점
- 모든 오류 메시지에서 key, token, source 원문을 제거

### Git credential 보안 결정

Git token, SSH private key와 `DOENAAI_CREDENTIAL_ENCRYPTION_KEY`를 Mac Studio로
복사하지 않는다. 권장 경계는 Backend가 credential을 소유하고 고정 SHA를
checkout한 뒤, 실행 파일과 비밀값을 제외한 크기 제한 분석 자료만 Mac Studio에
전달하는 방식이다.

실제 source 전달 API를 만들기 전 다음 계약을 먼저 확정한다.

- Backend와 Mac 중 어디에서 clone/checkout할지
- source archive 허용 확장자, 전체 크기와 파일 수 제한
- `.env`, credential, build 산출물 제외 규칙
- source 원문 대신 정적 분석 결과만 전달할 수 있는지
- request ID, commit SHA, schema version

분석 대상 저장소의 build/install/test script는 자동 실행하지 않는다.

## 13. 작업 7: Frontend 연결 시 Backend가 제공할 것

Backend API가 완료되면 Frontend 담당자에게 다음 내용을 전달한다.

- endpoint와 HTTP method
- 요청/응답 JSON 예시
- 역할별 허용/거부 규칙
- 상태 enum과 허용 전이
- pagination/sort/filter 계약
- 401/403/404/409/422/5xx 오류 형태
- 분석 작업 polling 주기와 종료 상태

Frontend에서 우선 연결할 화면:

- 프로젝트 생성 시 조직 선택
- 조직과 구성원 관리
- 고객 요구사항 등록/버전/상태
- 견적 작업 항목, 검토, 승인과 공개
- 분석 작업 polling과 실제 event log
- CUSTOMER 전용 공개 견적 조회

## 14. 하지 말아야 할 작업

- 기존 `001`~`007` migration 수정 또는 무단 재실행
- 실제 비밀값을 Git, 문서, 채팅, 로그에 기록
- 다른 PC나 인터넷에 Mac Studio Ollama `11434` 공개
- FastAPI `8000`을 인터넷에 port forwarding
- Mac Studio에 Backend credential 암호화 master key 복사
- 분석 대상 저장소의 Gradle, Maven, npm, pip script 자동 실행
- 기존 사용자의 dirty worktree 초기화
- 충분한 데이터와 평가 기준 없이 LoRA 학습부터 시작
- 한 commit에 migration, 조직 API, 요구사항 API, 견적 API 전체를 혼합

## 15. 권장 commit 순서

기능과 테스트가 함께 통과하는 작은 commit으로 나눈다.

```text
feat: 조직 및 tenant API 구현
feat: 프로젝트 조직 소유 관계 연결
feat: 요구사항 버전 관리 API 구현
feat: 견적 검토 및 공개 API 구현
feat: AI 분석/추론 서버 클라이언트 연동
feat: 분석 Worker 상태 모델 추가
```

각 commit 전 최소 확인:

```powershell
cd backend
.\gradlew.bat test
git diff --check
git status -sb
```

사용자가 요청하기 전 자동으로 push하거나 기존 commit history를 변경하지 않는다.

## 16. 다른 PC 작업 완료 보고 형식

작업을 넘길 때 다음 항목을 남긴다.

```text
작업 branch/commit:
구현한 API:
추가한 migration과 Supabase 적용 여부:
Backend test 결과:
다른 조직 접근 차단 test 결과:
Mac Studio /api/models/health 실제 호출 결과:
Mac Studio /api/analysis/project 실제 호출 결과:
남은 문제:
다음 담당자가 실행할 명령:
```

최종 완료 체크리스트:

- [ ] 최신 `main`과 Java 25 확인
- [ ] 기존 Backend test 통과
- [ ] 007 Supabase 적용 상태 확인
- [ ] 조직/구성원 API와 tenant 차단 완료
- [ ] Project `organizationId` 연결과 기존 data 이관 완료
- [ ] 요구사항/버전 API 완료
- [ ] 견적/작업 항목/검토/공개 API 완료
- [ ] Spring Boot에서 Mac Studio FastAPI 실제 호출 성공
- [ ] 008 migration 로컬 PostgreSQL 검증 후 Supabase 적용
- [ ] 최소 Worker가 `PENDING -> COMPLETED` 수행
- [ ] 비밀값과 고객 내부 정보가 로그/고객 DTO에 없는지 확인
- [ ] Frontend 담당자에게 API 계약 전달
