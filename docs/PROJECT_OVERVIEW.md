# doenaai 프로젝트 전체 개요

최종 갱신: 2026-07-30

이 문서는 `doenaai`가 어떤 문제를 해결하는 프로젝트인지, 전체 시스템이 어떻게
구성되는지, 각 모듈이 어떤 책임을 가지는지, 현재 어디까지 구현되었는지를
설명한다.

새로운 개발자나 Codex는 작업을 시작하기 전에 이 문서를 먼저 읽고 실제 저장소
소스를 확인해야 한다. Mac Studio의 로컬 LLM 및 FastAPI 작업은
`docs/MAC_STUDIO_AI_HANDOFF.md`도 함께 읽는다.

이 문서에는 실제 토큰, 비밀번호, Supabase 키, 암호화 키, 내부 API 키를 기록하지
않는다.

## 한 문장으로 설명

`doenaai`는 프로젝트의 프런트엔드, 백엔드, SQL, 데이터베이스 스키마를 함께
분석해 전체 연결 구조를 만들고, 변경 요청의 영향 범위와 위험도를 근거와 함께
제공하는 사내용 AI 소프트웨어 분석 플랫폼이다.

## 해결하려는 문제

규모가 큰 업무 시스템에서는 하나의 화면 변경이 여러 계층에 영향을 줄 수 있다.

```text
Vue 화면
  -> HTTP API
  -> Spring Controller
  -> Service
  -> Repository 또는 MyBatis Mapper
  -> SQL
  -> DB 테이블과 컬럼
```

하지만 기존 프로젝트는 다음과 같은 문제가 있다.

- 전체 호출 관계를 아는 사람이 일부 개발자에게 한정됨
- 소스코드와 DB 구조가 별도로 관리됨
- 변경 요청의 영향 범위를 사람이 파일 검색으로 판단함
- SQL 또는 컬럼 변경이 어떤 화면과 API에 영향을 주는지 찾기 어려움
- 분석 근거가 문서로 축적되지 않음
- 과거 분석 결과와 사용자 수정 결과가 AI 개선에 활용되지 않음

`doenaai`는 소스와 DB를 하나의 관계 그래프로 연결하여 이 문제를 해결하는 것을
목표로 한다.

## 핵심 사용자

### 시스템 관리자

- 사용자, 역할, 권한 관리
- Git 및 DB 연결 정책 관리
- AI 서버와 학습 서버 관리
- AI 모델 버전과 배포 상태 관리

### 프로젝트 관리자

- 프로젝트 등록
- 프로젝트 구성원 관리
- Git 저장소 및 분석 DB 설정
- 초기 프로젝트 분석 실행

### 분석가 또는 개발자

- 프로젝트 통합 아키텍처 조회
- 변경 요청 등록
- 영향 범위와 위험도 분석 실행
- 분석 근거와 권장사항 확인

### 검토자

- AI 원본 분석 결과 검토
- 잘못된 결과 수정
- 최종 결과 승인
- 검토 의견 기록

### 모델 운영자

- 승인된 분석 결과를 학습 데이터로 내보내기
- LoRA 학습 실행
- 모델 평가
- 검증된 모델 버전 배포

## 핵심 업무 흐름

### 1. 사용자 인증과 권한 확인

```text
사용자 로그인
  -> Supabase Auth
  -> access token 발급
  -> Vue가 Spring Boot API에 Bearer token 전달
  -> Spring Security가 JWT 검증
  -> 역할과 permission 확인
```

Supabase는 인증을 담당하고, 실제 API 접근 권한은 Spring Boot가 DB의 역할과
permission을 기준으로 검사한다.

### 2. 프로젝트 등록

사용자는 프로젝트 기본정보와 분석 대상을 등록한다.

```text
프로젝트명
설명
소스 유형
Git 저장소 URL
기본 branch
Git 인증방식
분석 대상 DB
DB 스키마
```

현재 실제 구현의 중심은 Git 저장소와 DB 직접 연결이다. ZIP 소스 업로드와 DDL
파일 업로드는 데이터 구조에 일부 예약되어 있지만 전체 기능은 아직 완성되지
않았다.

### 3. 연결 검증

#### Git

- 저장소 URL과 branch 확인
- GitHub, GitLab, Bitbucket, Yona, Custom Git host 지원 구조
- 공개 HTTPS
- HTTPS username + access token
- HTTPS basic
- SSH private key와 선택적 passphrase
- SSH `known_hosts` 서버 키 검증
- 원격 branch의 commit SHA 확인

#### DB

- DB host, port, database/service name, schema, 계정 확인
- read-only 연결 시도
- DB 제품명과 버전 확인
- 현재 실제 연결 지원: Oracle, PostgreSQL

연결 확인 버튼은 입력값으로 실제 연결만 테스트한다. 민감한 인증정보를 DB에
저장하는 시점은 연결정보 저장 또는 분석 시작 과정이다.

### 4. 프로젝트 전체 스캔

분석 시작 시 다음 정보가 고정된다.

- 프로젝트 ID
- 저장소 URL
- 기본 branch
- 검증된 commit SHA
- 분석 대상 DB
- 분석 옵션
- 요청 사용자

`analysis_jobs`에 `PROJECT_SCAN`, `PENDING` 작업을 생성하고 분석 Worker가 이를
처리해야 한다.

목표 상태 흐름:

```text
PENDING
  -> RUNNING
  -> COMPLETED

또는

PENDING
  -> RUNNING
  -> FAILED
```

### 5. 소스와 DB 구조 추출

정적 분석기와 DB 메타데이터 수집기가 다음 정보를 추출한다.

#### 프런트엔드

- Vue 화면과 컴포넌트
- Router 경로
- Pinia store
- API client
- HTTP method와 URL

#### Java/Spring

- Controller
- Request mapping
- Service
- Repository
- Entity
- DTO
- 주요 메서드 호출 관계

#### MyBatis/SQL

- Mapper interface
- Mapper XML
- SQL statement
- 조회, 추가, 수정, 삭제 대상 테이블
- 사용 컬럼

#### DB

- 스키마
- 테이블
- 컬럼
- 데이터 타입
- Primary Key
- Foreign Key
- 인덱스
- View 및 기타 지원 객체

### 6. 통합 관계 그래프 생성

추출된 사실을 노드와 관계로 연결한다.

대표 노드:

```text
SCREEN
COMPONENT
ROUTE
API_CLIENT
API_ENDPOINT
CONTROLLER
SERVICE
REPOSITORY
MAPPER
SQL_STATEMENT
DB_SCHEMA
DB_TABLE
DB_COLUMN
```

대표 관계:

```text
ROUTES_TO
CALLS
USES
IMPLEMENTS
EXECUTES
READS
WRITES
REFERENCES
DEPENDS_ON
```

예시:

```text
UserRegisterView.vue
  --CALLS-->
POST /api/users
  --ROUTES_TO-->
UserController.createUser
  --CALLS-->
UserService.createUser
  --CALLS-->
UserMapper.insertUser
  --WRITES-->
USERS
```

### 7. AI 추론

LLM은 원본 저장소 전체를 무작정 입력받지 않는다. 정적 분석기가 생성한 구조,
관계, 근거 코드 위치, DB 메타데이터를 구조화된 입력으로 받는다.

LLM의 역할:

- 프로젝트 구조 요약
- 주요 업무 흐름 설명
- 구조적 위험 요소 식별
- 변경 영향 범위 판단
- 관련 파일, API, SQL, DB 객체를 근거로 제시
- 수정 및 테스트 권장사항 생성

LLM이 담당하지 않아야 하는 역할:

- 실제 Git 인증
- DB 비밀번호 관리
- 근거 없는 파일 또는 테이블 추측
- DB 쓰기 작업
- 사용자 승인 없이 코드 수정

AI 출력은 JSON Schema와 Pydantic 등의 구조 검증을 통과한 뒤 저장한다.

### 8. 통합 아키텍처 조회

초기 프로젝트 스캔이 완료되면 사용자는 화면부터 DB까지 연결된 시스템 구조를
조회할 수 있어야 한다.

주요 기능:

- 계층별 필터
- 파일 또는 객체 검색
- 특정 화면에서 DB까지 경로 탐색
- 특정 테이블을 사용하는 화면/API 역방향 탐색
- 근거 파일과 코드 위치 표시
- 분석에 사용한 commit SHA 표시

### 9. 변경 요청 영향도 분석

사용자는 변경 요청을 직접 입력하거나 향후 Git commit 또는 Pull Request에서
가져올 수 있다.

변경 요청 예시:

```text
USERS.EMAIL 컬럼 길이를 변경한다.
회원가입 검증 규칙을 변경한다.
기존 API 응답에 사용자 부서 정보를 추가한다.
```

분석 결과:

- 영향받는 화면
- 영향받는 API
- Controller, Service, Repository, Mapper
- SQL과 테이블 및 컬럼
- 예상 위험도
- 근거 경로
- 권장 수정 순서
- 필요한 단위/통합/회귀 테스트

### 10. 사람의 검토와 피드백

AI 결과는 자동으로 정답으로 확정하지 않는다.

```text
original_result
  -> 사용자 검토
  -> corrected_result
  -> 승인
  -> 학습 후보 데이터
```

`analysis_results`에는 AI 원본 결과와 사용자가 수정한 결과를 분리해 저장한다.
이를 통해 AI의 최초 판단과 사람이 확정한 정답을 비교할 수 있다.

### 11. 모델 학습과 배포

충분한 승인 결과가 쌓인 이후에만 자체 학습을 진행한다.

```text
승인된 분석 결과
  -> 학습 데이터셋 생성
  -> JSONL 내보내기
  -> MLX-LM / LoRA 학습
  -> 자동 및 수동 평가
  -> 모델 버전 등록
  -> 분석 서버에 배포
```

초기 단계에서는 사전 학습된 코드 이해용 LLM을 사용하고, 파인튜닝은 제품의
분석 파이프라인과 피드백 수집 기능이 완성된 뒤 진행한다.

## 전체 배포 구조

```text
┌─────────────────────────────────────────────────────────┐
│ 사용자 PC 또는 업무 서버                                │
│                                                         │
│  Vue 3 + Vite                                           │
│       │                                                 │
│       v                                                 │
│  Spring Boot                                            │
│  - Auth/RBAC                                            │
│  - Project                                              │
│  - Git/DB connection                                    │
│  - Analysis job                                         │
└──────────────────────┬──────────────────────────────────┘
                       │ 인증된 사설 LAN API
                       v
┌─────────────────────────────────────────────────────────┐
│ Mac Studio                                              │
│                                                         │
│  FastAPI                                                │
│  - Analysis Worker                                      │
│  - Static analyzer                                      │
│  - Prompt/response validation                           │
│       │                                                 │
│       v                                                 │
│  Ollama 또는 향후 MLX 기반 inference runtime            │
│  - 사전 학습 코드 이해용 LLM                            │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ Supabase                                                │
│  - Auth                                                 │
│  - PostgreSQL                                           │
│  - 분석 작업과 결과                                     │
│  - 모델/학습/배포 이력                                  │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ 별도 학습 장비 예정                                     │
│  - Dataset export                                       │
│  - MLX-LM / LoRA                                        │
│  - Model evaluation                                     │
└─────────────────────────────────────────────────────────┘
```

## 모노레포 구성과 책임

### `frontend`

기술:

- Vue 3
- Vite
- Vue Router
- Pinia
- Axios
- Supabase JavaScript SDK
- Tailwind CSS

책임:

- 로그인과 사용자 상태
- permission 기반 라우팅
- 프로젝트 등록/관리
- Git 및 DB 연결 입력
- 분석 시작
- 진행률과 결과 표시
- 변경 요청, 모델, 문서, 설정 화면

현재 `VITE_USE_MOCK=false`로 설정하면 실제 Spring Boot API를 호출한다. 일부
화면과 API는 아직 mock 또는 고정 데이터에 의존한다.

### `backend`

기술:

- Java 25
- Spring Boot 4.1
- Spring Security
- OAuth2 Resource Server
- Spring Data JPA
- Spring JDBC
- Eclipse JGit
- PostgreSQL JDBC
- Oracle JDBC

책임:

- Supabase JWT 검증
- 역할 및 permission 검사
- 사용자 프로필 조회
- 프로젝트 CRUD와 접근 제어
- Git 저장소 정책 및 연결 관리
- DB 연결 관리
- 인증정보 암호화
- 분석 작업 생성 및 조회
- 향후 분석 Worker와 AI 서버 오케스트레이션
- 분석 결과 저장 및 검토 API

### `ai-server`

기술:

- Python
- FastAPI
- Pydantic
- 향후 Ollama Python client

책임:

- 분석 Worker
- Git/ZIP source loader
- Java/Vue/MyBatis/SQL 정적 분석
- DB 메타데이터 분석
- 코드와 DB 관계 결합
- AI prompt 구성
- 구조화 응답 검증
- 로컬 LLM 추론 호출

현재는 `/health`만 구현된 뼈대 상태다.

### `training`

목표 기술:

- Python
- MLX-LM
- LoRA

책임:

- 승인된 분석 결과 내보내기
- 학습 데이터셋 생성
- LoRA 학습
- 평가
- artifact와 adapter 생성

현재 스크립트는 placeholder다.

### `shared`

프런트엔드, 백엔드, AI 서버가 공유하는 JSON Schema를 관리한다.

현재 `impact-analysis.schema.json`이 있으며 향후 다음 schema가 필요할 수 있다.

- 프로젝트 전체 스캔 결과
- 아키텍처 노드와 관계
- 변경 영향도 결과
- 분석 Worker 요청/응답

### `supabase`

DB migration을 순서대로 관리한다.

```text
001_auth_rbac.sql
002_projects_analysis.sql
003_ai_model_training.sql
004_project_connection.sql
005_project_repository_connection.sql
006_git_host_policy.sql
```

사용자는 2026-07-30 기준 `001`부터 `006`까지 적용했고 관련 테이블이 정상적으로
조회된다고 확인했다.

### `docs`

- 프로젝트 전체 구조
- 아키텍처
- 기술 버전
- Git 저장소 연결 방식
- PC 간 작업 인수인계
- Mac Studio AI 작업 인수인계

## 데이터 모델

### 인증과 권한

#### `profiles`

Supabase `auth.users`와 연결되는 업무 사용자 프로필이다.

#### `roles`

관리자, 프로젝트 관리자, 분석가 등 역할을 정의한다.

#### `permissions`

화면과 API에서 사용하는 세부 permission을 정의한다.

#### `user_roles`

사용자에게 역할을 할당한다.

#### `role_permissions`

역할과 permission을 연결한다.

### 프로젝트

#### `projects`

프로젝트 기본정보와 소스 유형, 저장소 URL, 기본 branch를 저장한다.

#### `project_members`

프로젝트별 구성원과 프로젝트 내부 역할을 저장한다.

#### `project_repository_connections`

프로젝트별 Git provider, 인증방식, 암호화된 credential, 연결상태, 마지막 검증
commit SHA를 저장한다.

하나의 프로젝트는 하나의 Git 저장소를 사용하지만 프로젝트마다 GitHub,
GitLab, Yona 등 서로 다른 provider를 사용할 수 있다.

#### `project_database_sources`

프로젝트 분석 대상 DB 연결 또는 DDL 업로드 메타데이터를 저장한다. DB password는
암호화된 값만 저장한다.

#### `git_host_policies`

서버가 원격 조회를 허용하는 Git host와 HTTPS/SSH port를 관리한다. 임의의 내부
주소나 예상하지 않은 port로 연결되는 SSRF 위험을 줄이기 위한 정책 테이블이다.

### 분석

#### `change_requests`

사용자의 변경 요청을 저장한다. 수동 입력, Git commit, Pull Request 등 다양한
입력 유형을 지원하도록 설계되어 있다.

#### `analysis_jobs`

프로젝트 스캔, 변경 요청 분석, 재분석 작업과 진행률을 저장한다.

주요 상태:

```text
PENDING
RUNNING
COMPLETED
FAILED
CANCELLED
```

#### `analysis_results`

AI 원본 결과, 사용자 수정 결과, 위험도, 요약, 승인 여부, 추론 메타데이터를
저장한다.

### AI 모델 수명주기

#### `ai_models`

논리적인 모델을 관리한다.

예:

```text
doenaai-code-analyzer
doenaai-embedding
doenaai-reranker
```

#### `model_versions`

실제 base model, LoRA adapter, artifact와 버전을 관리한다.

#### `model_deployments`

어떤 서버에서 어떤 모델 버전이 어떤 역할로 활성화되어 있는지 관리한다.

#### `training_datasets`

학습 데이터셋 메타데이터와 저장 위치를 관리한다.

#### `training_jobs`

학습 요청, 진행률, worker, hyper parameter, 결과 artifact를 관리한다.

#### `model_evaluations`

모델 버전의 자동/수동/benchmark 평가 결과를 관리한다.

## 주요 API 흐름

### 인증

```text
GET /api/auth/me
```

### 프로젝트

```text
GET  /api/projects
GET  /api/projects/{projectId}
POST /api/projects
PUT  /api/projects/{projectId}
```

### Git 연결

```text
POST /api/projects/{projectId}/repository-connection/test
PUT  /api/projects/{projectId}/repository-connection
GET  /api/projects/{projectId}/repository-connection
```

### DB 연결

```text
POST /api/projects/{projectId}/database-connection/test
PUT  /api/projects/{projectId}/database-connection
```

### 분석 작업

```text
POST /api/projects/{projectId}/analysis-jobs
GET  /api/analysis-jobs/{analysisJobId}
```

향후 필요한 API:

- 분석 작업 claim/heartbeat/complete/fail
- 분석 이벤트와 로그
- 분석 결과 조회
- 아키텍처 그래프 조회
- 변경 영향도 분석
- 결과 수정과 승인
- AI 모델 배포 조회

## 보안 원칙

### 인증정보 평문 저장 금지

다음 값은 평문으로 DB에 저장하지 않는다.

- Git access token
- Git password
- SSH private key
- SSH passphrase
- DB password
- 내부 AI API key

Spring Boot에서 AES-256-GCM으로 암호화한 값만 저장한다.

### 암호화 키 관리

`DOENAAI_CREDENTIAL_ENCRYPTION_KEY`는 Base64로 인코딩된 32바이트 키다.

- Git에 저장하지 않음
- 프런트엔드에 전달하지 않음
- 문서와 로그에 기록하지 않음
- 같은 Supabase DB의 암호문을 복호화해야 하는 backend는 동일한 키 사용

AI 서버가 DB 암호화 키를 직접 공유하는 구조는 피한다. 필요한 credential은
Spring Boot가 인증된 내부 통신으로 제한된 시간 동안 Worker에 전달하고,
Worker는 메모리에서만 사용한 뒤 제거하는 방향을 권장한다.

### Git host 제한

원격 Git URL은 `git_host_policies`의 allowlist를 통과해야 한다.

- 허용 host
- 허용 protocol
- 허용 port
- 활성화 여부

### DB read-only 원칙

분석 DB 연결은 가능한 경우 JDBC read-only로 설정한다.

- 데이터 수정 SQL 실행 금지
- 스키마 메타데이터와 제한된 조회만 허용
- 연결 timeout과 network timeout 적용

### AI 네트워크 경계

- Ollama는 Mac Studio의 localhost에만 binding
- FastAPI만 사설 LAN에 공개
- FastAPI 분석 API는 내부 API key 또는 향후 mTLS 사용
- 인터넷 port forwarding 금지
- 원본 credential과 비밀값을 LLM prompt에 포함하지 않음

### 재현 가능한 분석

모든 분석 결과에는 가능한 경우 다음 정보를 남긴다.

- 프로젝트 ID
- Git provider와 저장소 URL
- branch
- commit SHA
- DB schema
- 분석 옵션
- 사용한 모델 버전
- prompt/schema 버전
- 시작/완료 시간

같은 입력과 모델 버전으로 결과를 추적할 수 있어야 한다.

## 구현 원칙

### 근거 우선

AI의 자연어 설명보다 정적 분석 근거가 우선한다.

각 분석 항목은 가능한 경우 다음 정보를 포함한다.

- 파일 경로
- 클래스 또는 함수
- 코드 위치
- API method/path
- SQL statement
- 테이블/컬럼
- 관계를 판단한 규칙
- 신뢰도

### 비동기 작업

대규모 저장소 분석은 HTTP 요청 하나에서 동기 처리하지 않는다.

```text
API가 작업 생성
  -> Worker가 작업 선점
  -> 단계별 progress 갱신
  -> heartbeat
  -> 결과 저장
  -> 완료 상태
```

### 멱등성과 재시도

- 동일 작업을 여러 Worker가 동시에 처리하지 않도록 claim 필요
- Worker가 중단되면 lease/heartbeat 기준 재처리 가능
- 결과 저장은 중복 실행에 안전해야 함
- 외부 모델 timeout은 제한된 횟수만 재시도

### Provider 중립성

프로젝트는 GitHub만을 위한 시스템이 아니다.

- GitHub
- GitLab
- Bitbucket
- Yona
- 사내 Git
- 향후 기타 provider

AI 모델도 특정 runtime에 고정하지 않고 interface를 통해 교체할 수 있어야 한다.

```text
AnalysisModelClient
  -> Ollama
  -> MLX server
  -> 필요 시 외부 AI API
```

### 사람 검토

AI 결과는 자동 승인하지 않는다. 원본 결과와 수정 결과를 별도로 보존하고 승인
이력을 남긴다.

## 현재 구현 상태

### 구현 완료 또는 실제 연결 확인

- Supabase 로그인과 JWT 인증
- RBAC permission 검사
- 프로젝트 CRUD와 접근 제어
- 프로젝트 Git 소스 정보
- 범용 Git 연결 테스트
- GitHub private repository access token 연결 확인
- Git credential 암호화 저장
- Git host policy
- Oracle/PostgreSQL DB 연결 테스트
- DB credential 암호화 저장
- 분석 작업 생성
- 분석 입력에 저장소 URL, branch, commit SHA snapshot 저장
- Supabase migration `001`~`006`

### 부분 구현

- 분석 진행 화면
  - 작업 조회는 가능
  - 현재 한 번만 조회하며 polling 미구현
  - 상세 로그는 고정 화면 데이터
- 통합 아키텍처 화면
  - 화면 구조가 있으나 실제 분석 결과 API 미구현
- 영향도 분석 화면
  - 화면 구조와 mock 데이터 중심
- AI 모델 관리 화면
  - DB 구조와 화면이 있으나 실제 모델 서버 운영 연결 미구현
- 설정 화면
  - AI/학습/Supabase 연결을 위한 UI와 API 형태가 있으나 backend 미구현 부분 존재

### 미구현

- 분석 작업 Worker
- `PENDING` 작업 claim과 heartbeat
- Git clone 및 고정 commit checkout
- ZIP source loader
- DDL 업로드 처리
- Java/Vue/MyBatis/SQL 정적 분석기
- DB 메타데이터 전체 수집기
- 코드와 DB 관계 그래프
- FastAPI와 로컬 LLM 실제 연결
- AI 분석 결과 생성과 `analysis_results` 저장
- 분석 이벤트와 실시간 로그
- 결과 수정과 승인 API
- 학습 데이터셋 내보내기
- LoRA 학습
- 모델 평가와 실제 배포 자동화
- RAG, embedding, reranker

## 현재 사용자가 도달한 상태

사용자는 프로젝트 등록 화면에서 다음 과정을 실제로 확인했다.

```text
프로젝트 생성
  -> GitHub private repository token 연결 성공
  -> DB 연결 성공
  -> 분석 시작
  -> 관련 연결정보가 DB에 정상 저장
  -> analysis_jobs가 생성됨
```

현재 분석 작업은 Worker가 없으므로 `PENDING`에 머무는 것이 정상이다. 다음 핵심
개발 목표는 이 작업을 실제 처리하는 분석 파이프라인이다.

## 개발 로드맵

### 단계 1: Mac Studio 로컬 LLM

- Mac 사양 확인
- Ollama 설치
- 사전 학습 코드 이해용 모델 설치
- FastAPI에서 모델 호출
- JSON Schema 기반 구조화 응답
- 내부 API key와 장애 처리

세부 인수인계:

```text
docs/MAC_STUDIO_AI_HANDOFF.md
```

### 단계 2: 분석 작업 실행 기반

- `007_analysis_pipeline.sql`
- Worker claim
- current step
- heartbeat
- retry
- 분석 이벤트/로그

### 단계 3: 최소 수직 분석

```text
PENDING
  -> RUNNING
  -> Git clone
  -> 파일 개수와 framework 탐지
  -> DB 테이블 목록 수집
  -> 간단한 AI 요약
  -> analysis_results 저장
  -> COMPLETED
```

### 단계 4: 정적 분석 고도화

- Java/Spring parser
- Vue/Router/API parser
- MyBatis/SQL parser
- DB metadata parser
- 근거와 신뢰도

### 단계 5: 통합 아키텍처

- 노드/관계 데이터 구조
- 화면부터 DB까지 경로
- 테이블에서 화면까지 역방향 경로
- 검색과 필터
- 결과 화면 API 연동

### 단계 6: 변경 영향도

- 변경 요청 등록
- Git commit/PR diff 수집
- 영향 경로 탐색
- AI 위험도와 권장사항
- 결과 수정과 승인

### 단계 7: 피드백과 학습

- 승인 데이터 내보내기
- 품질 필터링
- Train/validation/test 분리
- LoRA 학습
- 자동/수동 평가
- 모델 버전 배포

### 단계 8: RAG와 확장

- 사내 문서
- 설계서
- API 명세
- 운영 장애 이력
- embedding과 검색
- reranking

## 다음 작업의 가장 중요한 완료 기준

첫 번째 목표는 정교한 AI 모델이 아니라 다음 흐름을 실제로 끝까지 성공시키는
것이다.

```text
사용자가 분석 시작
  -> analysis_jobs.PENDING
  -> Worker가 작업 선점
  -> RUNNING
  -> 고정 commit 소스 준비
  -> DB 구조 준비
  -> 최소 구조화 분석
  -> 로컬 LLM 호출
  -> analysis_results 저장
  -> COMPLETED
  -> Vue에서 결과 확인
```

이 흐름이 성공한 뒤 분석 정확도, 지원 언어, 모델 학습을 단계적으로 확장한다.

## 새로운 Codex 또는 개발자를 위한 작업 규칙

1. 이 문서와 관련 인수인계 문서를 먼저 전체 읽기
2. 문서 내용만 믿지 말고 실제 소스와 migration 확인
3. `git status`와 최근 commit 확인
4. 기존 사용자 변경사항 보존
5. 비밀값을 코드, 문서, 로그, commit에 넣지 않기
6. migration을 임의로 재실행하거나 기존 파일 수정하지 않기
7. 모델이 원본 credential을 받지 않게 하기
8. 정적 분석 근거 없이 AI 추측을 결과로 확정하지 않기
9. 실제 실행과 실패 경로를 함께 검증하기
10. 사용자 요청 없이 commit 또는 push하지 않기

## 용어

### 프로젝트 스캔

특정 commit과 DB schema를 기준으로 프로젝트 전체 구조를 분석하는 작업이다.

### 변경 요청

사용자가 수행하려는 기능, 소스, DB 변경의 설명 또는 Git commit/PR 참조다.

### 영향도 분석

변경 요청이 영향을 주는 화면, API, 소스, SQL, DB 객체와 위험도를 찾는 작업이다.

### 원본 결과

AI가 최초 생성한 `analysis_results.original_result`다.

### 수정 결과

사용자가 검토하고 수정한 `analysis_results.corrected_result`다.

### 모델

`ai_models`에 등록되는 논리적인 AI 기능 단위다.

### 모델 버전

base model, LoRA adapter, artifact 등 실제 추론에 사용되는 버전이다.

### 모델 배포

특정 서버에서 특정 모델 버전을 분석, embedding, reranking 역할로 활성화한
상태다.

### Worker

`PENDING` 분석 또는 학습 작업을 선점하여 실제 처리를 수행하는 프로세스다.

## 관련 문서

- `docs/MAC_STUDIO_AI_HANDOFF.md`
  - Mac Studio Ollama/FastAPI 작업의 구체적인 인수인계
- `docs/CODEX_HANDOFF.md`
  - 2026-07-29까지의 Windows 작업 인수인계
- `docs/GIT_REPOSITORY_CONNECTION.md`
  - 범용 Git 저장소 인증과 연결
- `docs/ARCHITECTURE.md`
  - 초기 배포 아키텍처
- `docs/VERSIONS.md`
  - 주요 기술 버전
- `docs/데이터베이스 테이블 구조.md`
  - Supabase 테이블 구조
