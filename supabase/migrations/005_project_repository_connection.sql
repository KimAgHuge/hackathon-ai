-- 005_project_repository_connection.sql
-- 프로젝트는 Git 저장소 하나를 사용한다.
-- GitHub/GitLab/Bitbucket/Yona/사내 Git 서버의 인증방식과 연결 검증 상태를 관리한다.
-- Token, 비밀번호, SSH 개인키는 평문 저장 금지.
-- credential_ciphertext에는 Spring Boot에서 AES-256-GCM으로 암호화한 값만 저장한다.

begin;

create table if not exists public.project_repository_connections (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null unique
        references public.projects(id)
        on delete cascade,

    provider_type varchar(20) not null default 'AUTO',
    auth_type varchar(30) not null default 'NONE',
    username varchar(200),
    credential_ciphertext text,
    known_hosts_entry text,

    status varchar(20) not null default 'PENDING',
    verified_commit_sha varchar(64),
    last_tested_at timestamptz,
    last_test_result varchar(20),
    last_error_message text,

    metadata jsonb not null default '{}'::jsonb,
    created_by uuid
        references auth.users(id)
        on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint project_repository_provider_type_check
        check (
            provider_type in (
                'AUTO',
                'GITHUB',
                'GITLAB',
                'BITBUCKET',
                'YONA',
                'CUSTOM'
            )
        ),

    constraint project_repository_auth_type_check
        check (
            auth_type in (
                'NONE',
                'HTTPS_TOKEN',
                'HTTPS_BASIC',
                'SSH_KEY',
                'PROVIDER_APP'
            )
        ),

    constraint project_repository_status_check
        check (
            status in (
                'PENDING',
                'CONNECTED',
                'FAILED'
            )
        ),

    constraint project_repository_last_test_result_check
        check (
            last_test_result is null
            or last_test_result in (
                'SUCCESS',
                'FAILED'
            )
        ),

    constraint project_repository_commit_sha_check
        check (
            verified_commit_sha is null
            or verified_commit_sha ~
               '^([0-9a-f]{40}|[0-9a-f]{64})$'
        ),

    constraint project_repository_credential_check
        check (
            (
                auth_type = 'NONE'
                and credential_ciphertext is null
                and known_hosts_entry is null
            )
            or
            (
                auth_type = 'HTTPS_TOKEN'
                and username is not null
                and credential_ciphertext is not null
                and known_hosts_entry is null
            )
            or
            (
                auth_type = 'HTTPS_BASIC'
                and username is not null
                and credential_ciphertext is not null
                and known_hosts_entry is null
            )
            or
            (
                auth_type = 'SSH_KEY'
                and credential_ciphertext is not null
                and known_hosts_entry is not null
            )
            or auth_type = 'PROVIDER_APP'
        )
);

create index if not exists idx_project_repository_connections_status
    on public.project_repository_connections(status);

create index if not exists idx_project_repository_connections_provider
    on public.project_repository_connections(provider_type);

drop trigger if exists trg_project_repository_connections_updated_at
    on public.project_repository_connections;

create trigger trg_project_repository_connections_updated_at
before update on public.project_repository_connections
for each row
execute function public.set_updated_at();

alter table public.project_repository_connections
    enable row level security;

revoke all
    on table public.project_repository_connections
    from anon, authenticated;

comment on table public.project_repository_connections is
    '프로젝트 Git 저장소 인증방식과 마지막 연결 검증 결과';

comment on column public.project_repository_connections.credential_ciphertext is
    '평문 Token, 비밀번호, SSH 개인키 저장 금지. Spring Boot 암호문만 저장';

comment on column public.project_repository_connections.known_hosts_entry is
    'SSH 서버 공개키 known_hosts 한 줄. SSH_KEY 인증에서 서버 신원 검증에 사용';

insert into public.project_repository_connections (
    project_id,
    provider_type,
    auth_type,
    status,
    created_by,
    created_at,
    updated_at
)
select
    p.id,
    'AUTO',
    'NONE',
    'PENDING',
    p.created_by,
    p.created_at,
    p.updated_at
from public.projects p
where p.source_type = 'GIT'
  and p.repository_url is not null
on conflict (project_id) do nothing;

commit;
