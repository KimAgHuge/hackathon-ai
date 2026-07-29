# doenaai Codex 인수인계

최종 갱신: 2026-07-29

이 문서는 다른 PC에서 저장소를 받은 뒤 새로운 Codex 작업을 시작할 때
현재 구현 상태, 로컬 설정, 남은 작업을 복원하기 위한 인수인계 문서다.
인증정보와 암호화 키 같은 비밀값은 이 문서에 기록하지 않는다.

## 다른 PC에서 Codex를 시작할 때

저장소를 받은 뒤 새 Codex 작업에 다음과 같이 요청한다.

```text
docs/CODEX_HANDOFF.md와 docs/GIT_REPOSITORY_CONNECTION.md를 먼저 읽고,
git status와 현재 migration 적용 상태를 확인한 다음 미완료 작업부터 이어서 진행해줘.
기존 변경사항과 비밀정보는 보존하고 임의로 migration을 실행하거나 commit하지 마.
```

Codex의 로컬 대화 기록과 로컬 파일은 PC 사이에 자동 동기화되지 않는다.
소스와 이 문서를 Git으로 push한 뒤 다른 PC에서 pull해야 한다.

## 프로젝트 개요

`doenaai`는 프로젝트 소스코드와 데이터베이스 스키마를 함께 분석하여
변경 영향도와 위험도를 제공하는 시스템이다.

- 프런트엔드: Vue 3, Vite, Pinia, Vue Router, Axios, Supabase Auth
- 백엔드: Java 25, Spring Boot 4.1, Spring Security, JPA/JDBC
- DB: Supabase PostgreSQL
- 분석 서버 예정: Python FastAPI
- Git 원격 조회: Eclipse JGit

현재 저장소:

```text
origin: https://github.com/KimAgHuge/hackathon-ai.git
현재 branch: main
```

## Supabase migration 상태

현재까지 확인된 기준은 다음과 같다.

| Migration | 내용 | 상태 |
|---|---|---|
| `001_auth_rbac.sql` | 프로필, 역할, 권한, RLS | 적용됨 |
| `002_core_domain.sql` | 프로젝트, 멤버, 변경요청, 분석 작업·결과 | 적용됨 |
| `003_ai_training.sql` | AI 모델, 데이터셋, 학습·평가·배포 | 적용됨 |
| `004_project_connection.sql` | 프로젝트 소스 유형과 DB 연결정보 | 적용됨 |
| `005_project_repository_connection.sql` | 프로젝트별 Git 인증정보 | 파일 작성 완료, 아직 적용하지 않음 |
| `006_git_host_policy.sql` | Git 호스트 허용정책과 프로젝트 소스 제약 | 파일 작성 완료, 아직 적용하지 않음 |

다른 PC에서 작업을 재개해도 `005`와 `006`을 자동 실행하지 않는다.
사용자가 Supabase SQL Editor에서 다음 순서로 직접 실행해야 한다.

1. `supabase/migrations/005_project_repository_connection.sql`
2. `supabase/migrations/006_git_host_policy.sql`

## 이번 작업에서 구현한 내용

### 프로젝트 소스 및 DB 연결

- `projects`에 `source_type`, 소스 파일 관련 필드 추가
- 프로젝트 생성·수정 DTO와 Entity에 소스 유형 반영
- Oracle/PostgreSQL 읽기 전용 연결 테스트
- 프로젝트별 DB 연결정보 저장
- DB 비밀번호 AES-256-GCM 암호화

### 범용 Git 저장소 연결

프로젝트 하나에는 저장소 하나를 연결하며 저장소 공급자는 달라질 수 있다.

- GitHub
- GitLab
- Bitbucket
- Yona
- 사내 또는 기타 Git 서버

지원 인증:

- 공개 HTTPS
- HTTPS 사용자명 + Access Token
- HTTPS 사용자명 + 비밀번호
- SSH 개인키 + 선택적 Passphrase
- SSH `known_hosts` 호스트키 검증

`PROVIDER_APP` 값은 향후 GitHub App/GitLab OAuth 확장을 위해 예약했지만
현재 화면에서는 비활성화되어 있다.

보안 처리:

- Token, 비밀번호, SSH 개인키와 Passphrase는 평문으로 DB에 저장하지 않는다.
- 인증정보를 JSON으로 직렬화한 뒤 AES-256-GCM으로 암호화한다.
- API 조회 응답에는 인증정보 원문이나 암호문을 반환하지 않는다.
- Git URL에 사용자명·Token을 포함하는 방식을 거부한다.
- `git_host_policies`에 등록된 호스트·프로토콜·포트만 허용한다.
- 요청 URL/브랜치와 프로젝트에 저장된 URL/브랜치가 일치해야 한다.

Git API:

```text
POST /api/projects/{projectId}/repository-connection/test
PUT  /api/projects/{projectId}/repository-connection
GET  /api/projects/{projectId}/repository-connection
```

DB API:

```text
POST /api/projects/{projectId}/database-connection/test
PUT  /api/projects/{projectId}/database-connection
```

### 분석 작업

- 분석 작업 생성·조회 API 추가
- 분석 시작 직전에 저장된 Git 인증정보로 원격 브랜치를 다시 검증
- 확인된 저장소 URL, 브랜치와 Commit SHA를
  `analysis_jobs.input_snapshot`에 저장
- 분석 작업 생성 후 진행 화면으로 이동

분석 API:

```text
POST /api/projects/{projectId}/analysis-jobs
GET  /api/analysis-jobs/{analysisJobId}
```

## 아직 구현하지 않은 부분

- 분석 Worker가 저장소를 clone/checkout하는 처리
- DB 메타데이터 수집 Worker
- 소스코드와 DB 관계 분석
- `analysis_jobs` 진행률 갱신
- 실제 분석 로그와 분석 결과 화면 연결
- GitHub App, GitLab OAuth 같은 공급자 App 인증
- ZIP 소스 업로드와 DDL 파일 업로드

현재 분석 작업은 `PENDING`으로 생성되지만 이를 소비하는 Worker는 없다.
진행 화면의 상세 항목과 로그 일부는 아직 화면 검토용 고정 데이터다.

## 다른 PC의 로컬 설정

### 프런트엔드

`frontend/.env.local`은 Git에서 제외되므로 다른 PC에서 직접 생성해야 한다.

```powershell
Copy-Item frontend\.env.example frontend\.env.local
```

실제 API 사용 시 필요한 주요 값:

```text
VITE_AUTH_MODE=supabase
VITE_AUTH_PROFILE_SOURCE=backend
VITE_USE_MOCK=false
VITE_SUPABASE_URL={Supabase URL}
VITE_SUPABASE_PUBLISHABLE_KEY={Supabase publishable key}
VITE_API_BASE_URL=/api
```

`VITE_` 변수는 브라우저 코드에 포함되므로 DB 비밀번호,
Supabase service role key, Git Token, 암호화 키를 넣지 않는다.

### 백엔드

`backend/src/main/resources/application-local.yml`도 Git에서 제외된다.
다른 PC에서 example을 복사하고 Supabase DB/JWT 정보를 입력한다.

```powershell
Copy-Item `
  backend\src\main\resources\application-local.yml.example `
  backend\src\main\resources\application-local.yml
```

다음 환경변수가 필요하다.

```text
DOENAAI_CREDENTIAL_ENCRYPTION_KEY={Base64로 인코딩한 32바이트 키}
DOENAAI_GIT_CONNECTION_TIMEOUT_SECONDS=10
```

동일한 Supabase DB를 사용하는 백엔드는 반드시 동일한
`DOENAAI_CREDENTIAL_ENCRYPTION_KEY`를 사용해야 한다. PC마다 새 키를
생성하면 다른 PC에서 저장한 DB/Git 인증정보를 복호화할 수 없다.
키는 Git이나 이 문서에 넣지 말고 별도의 안전한 경로로 공유한다.

### 실행

프런트:

```powershell
cd frontend
npm install
npm run dev
```

백엔드:

```powershell
cd backend
.\gradlew.bat bootRun
```

## 검증 결과와 재검증 항목

완료된 검증:

- 백엔드 전체 Java 소스를 직접 `javac`로 컴파일: 소스 오류 없음
- 프런트 Vue/JavaScript 56개 파일 파싱·컴파일 검사: 오류 없음
- 변경된 프런트 파일 ESLint: 오류 0, 경고 0
- Prettier 및 `git diff --check`: 문제 없음

Codex Windows sandbox에서는 Gradle이 의존성 JAR를 닫는 과정에서
`AccessDeniedException`을 반환했다. Java 소스 컴파일 오류는 없었지만
일반 사용자 환경에서 다음 명령을 다시 실행해야 한다.

```powershell
cd backend
.\gradlew.bat clean test

cd ..\frontend
npm run lint
npm run build
```

아직 수행하지 않은 통합 검증:

- Supabase에 `005`, `006` 적용 후 Spring Boot JPA schema validation
- 실제 공개 Git 저장소 연결
- 실제 Private GitHub/GitLab Token 연결
- 실제 SSH 개인키 및 `known_hosts` 연결
- Oracle/PostgreSQL 실제 계정 연결

## 다음 구현 우선순위

1. `005`, `006`을 Supabase에 적용
2. 일반 PC 환경에서 백엔드 테스트와 프런트 프로덕션 빌드
3. 공개 GitHub 저장소로 연결 테스트
4. Private GitHub PAT 연결 테스트
5. 사내 Yona/GitLab 호스트 정책 추가 및 연결 테스트
6. 분석 Worker가 고정된 Commit SHA를 clone/checkout하도록 구현
7. DB 메타데이터 수집과 분석 진행률 갱신 구현
