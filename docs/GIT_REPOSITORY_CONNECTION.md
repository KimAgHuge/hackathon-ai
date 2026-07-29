# Git 저장소 연결 및 범용 인증

## 지원 범위

프로젝트 하나에는 Git 저장소 하나를 연결한다. 저장소가 위치한 서비스는
GitHub, GitLab, Bitbucket, Yona 또는 사내 Git 서버 중 하나일 수 있다.

지원하는 연결 방식은 다음과 같다.

| 프로토콜 | 인증 방식 | 용도 |
|---|---|---|
| HTTPS | 인증 없음 | 공개 저장소 |
| HTTPS | 사용자명 + Access Token | GitHub/GitLab/Bitbucket/Yona 및 사내 서버의 Private 저장소 |
| HTTPS | 사용자명 + 비밀번호 | 비밀번호 Git 인증을 허용하는 사내 서버 |
| SSH | SSH 개인키 + 선택적 Passphrase | SSH 접근을 허용하는 모든 Git 서버 |

`PROVIDER_APP`은 DB/API 확장을 고려해 값만 예약했다. GitHub App이나
GitLab OAuth는 공급자별 App 등록, 콜백, 설치 토큰 발급 과정이 필요하므로
현재 화면에서는 선택할 수 없다.

## DB migration

기존 `001`~`004` 다음에 아래 순서로 실행한다.

1. `005_project_repository_connection.sql`
2. `006_git_host_policy.sql`

### 005: `project_repository_connections`

프로젝트별 인증 방식과 마지막 연결 검증 결과를 보관한다.

- `project_id`가 `unique`이므로 프로젝트 하나당 저장소 인증 설정은 하나다.
- Token, 비밀번호, SSH 개인키, Passphrase는 JSON으로 묶은 뒤
  Spring Boot에서 AES-256-GCM으로 암호화하여
  `credential_ciphertext`에만 저장한다.
- `known_hosts_entry`는 SSH 서버 공개 호스트키이며 비밀키가 아니다.
- API 응답에는 암호문이나 원문 인증정보를 반환하지 않고 설정 여부만 반환한다.

### 006: `git_host_policies`

Git 서버의 호스트, 프로토콜과 포트를 허용 목록으로 관리한다. 임의 URL을
통한 내부망 접근을 막기 위한 정책이다.

기본값으로 다음 호스트가 등록된다.

- `github.com`: HTTPS 443, SSH 22
- `gitlab.com`: HTTPS 443, SSH 22
- `bitbucket.org`: HTTPS 443, SSH 22

Yona나 사내 GitLab은 관리자가 직접 추가한다.

```sql
insert into public.git_host_policies (
    host,
    display_name,
    provider_type,
    https_enabled,
    https_port,
    ssh_enabled,
    ssh_port
)
values (
    'git.company.local',
    '사내 Yona',
    'YONA',
    true,
    443,
    true,
    22
);
```

HTTP 평문 Git URL은 지원하지 않는다. 사내 개발환경이라도 TLS가 적용된
HTTPS 또는 SSH를 사용해야 한다.

006은 새로 생성하거나 수정하는 프로젝트의 소스 필드를 검사하는
`projects_source_fields_check`도 추가한다. 기존 데이터 때문에 제약은
`NOT VALID`로 추가되며, 기존 데이터 정리 후 아래 명령으로 전체 검증할 수 있다.

```sql
alter table public.projects
validate constraint projects_source_fields_check;
```

## 서버 환경설정

DB 비밀번호와 Git 인증정보는 같은 애플리케이션 암호화 키를 사용한다.
운영환경에 Base64로 인코딩한 32바이트 키를 반드시 설정한다.

```text
DOENAAI_CREDENTIAL_ENCRYPTION_KEY={Base64 32-byte key}
DOENAAI_GIT_CONNECTION_TIMEOUT_SECONDS=10
```

암호화 키가 바뀌면 기존 인증정보를 복호화할 수 없으므로 Secret Manager
등에 보관하고 임의로 교체하지 않는다. 프런트에서 실제 API를 호출할 때는
Mock을 끈다.

```text
VITE_USE_MOCK=false
VITE_AUTH_MODE=supabase
VITE_AUTH_PROFILE_SOURCE=supabase
```

## 사용자 흐름

1. 프로젝트명, Git URL과 기본 브랜치를 입력한다.
2. 공급자와 인증 방식을 선택하고 필요한 인증정보를 입력한다.
3. `저장소 확인`을 누르면 백엔드가 JGit `ls-remote`로 접근 권한,
   브랜치와 최신 커밋 SHA를 확인한다.
4. 입력값이 바뀌면 성공 상태가 초기화된다.
5. Git과 DB가 모두 검증된 뒤 `분석 시작`을 누른다.
6. Git 인증정보와 DB 연결정보가 서버에서 암호화되어 저장된다.
7. 분석 작업 생성 직전에 저장된 인증정보로 Git 연결을 다시 확인한다.
8. 확인된 URL, 브랜치와 커밋 SHA를
   `analysis_jobs.input_snapshot`에 저장한다.

## API

### 연결 테스트

```http
POST /api/projects/{projectId}/repository-connection/test
```

### 검증 및 암호화 저장

```http
PUT /api/projects/{projectId}/repository-connection
```

두 API의 요청 형식은 같다.

```json
{
  "repositoryUrl": "https://gitlab.com/example/private-project.git",
  "branch": "main",
  "providerType": "AUTO",
  "authType": "HTTPS_TOKEN",
  "username": "git-user",
  "secret": "access-token",
  "sshPrivateKey": null,
  "sshPassphrase": null,
  "knownHostsEntry": null
}
```

SSH를 사용할 때는 URL과 인증 필드를 다음처럼 바꾼다.

```json
{
  "repositoryUrl": "git@git.company.local:team/project.git",
  "branch": "main",
  "providerType": "YONA",
  "authType": "SSH_KEY",
  "username": null,
  "secret": null,
  "sshPrivateKey": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----",
  "sshPassphrase": null,
  "knownHostsEntry": "git.company.local ssh-ed25519 AAAAC3..."
}
```

### 안전한 설정 조회

```http
GET /api/projects/{projectId}/repository-connection
```

조회 응답에는 `credentialsConfigured`, `knownHostsConfigured` 같은 설정
여부만 포함되며 Token, 비밀번호와 개인키는 반환하지 않는다.

## 구현 위치

- `backend/.../ProjectGitRepositoryController.java`
  - 테스트, 저장, 안전한 설정 조회 API
- `backend/.../GitRepositoryConnectionService.java`
  - URL/브랜치/호스트 정책 검증, HTTPS/SSH 인증, 암복호화
- `frontend/.../ProjectSourceForm.vue`
  - 공급자와 인증 방식별 입력 UI
- `frontend/.../ProjectRegisterView.vue`
  - 테스트, 암호화 저장, 분석 시작 흐름
- `supabase/migrations/005_project_repository_connection.sql`
  - 프로젝트별 저장소 인증 설정
- `supabase/migrations/006_git_host_policy.sql`
  - 허용 Git 호스트 및 프로젝트 소스 정합성 제약

저장소를 실제 작업 디렉터리에 복제하고 소스 분석기를 실행하는 Worker는
분석 작업 소비 단계에서 별도로 구현해야 한다. 현재 범위는 인증 검증,
설정 저장과 분석 입력 커밋 고정까지다.
