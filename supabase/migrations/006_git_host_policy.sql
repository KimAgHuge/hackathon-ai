-- 006_git_host_policy.sql
-- 임의 URL을 통한 SSRF를 막으면서 GitHub/GitLab/Yona/사내 Git 서버를
-- 애플리케이션 재배포 없이 허용할 수 있도록 Git 호스트 정책을 관리한다.

begin;

create table if not exists public.git_host_policies (
    id uuid primary key default gen_random_uuid(),
    host varchar(255) not null unique,
    display_name varchar(200) not null,
    provider_type varchar(20) not null default 'CUSTOM',

    https_enabled boolean not null default true,
    https_port integer not null default 443,
    ssh_enabled boolean not null default false,
    ssh_port integer not null default 22,

    enabled boolean not null default true,
    metadata jsonb not null default '{}'::jsonb,
    created_by uuid
        references auth.users(id)
        on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint git_host_policy_host_check
        check (
            host = lower(host)
            and host ~
                '^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$'
        ),

    constraint git_host_policy_provider_type_check
        check (
            provider_type in (
                'GITHUB',
                'GITLAB',
                'BITBUCKET',
                'YONA',
                'CUSTOM'
            )
        ),

    constraint git_host_policy_https_port_check
        check (
            https_port between 1 and 65535
        ),

    constraint git_host_policy_ssh_port_check
        check (
            ssh_port between 1 and 65535
        ),

    constraint git_host_policy_protocol_check
        check (
            https_enabled or ssh_enabled
        )
);

create index if not exists idx_git_host_policies_enabled
    on public.git_host_policies(enabled);

create index if not exists idx_git_host_policies_provider
    on public.git_host_policies(provider_type);

drop trigger if exists trg_git_host_policies_updated_at
    on public.git_host_policies;

create trigger trg_git_host_policies_updated_at
before update on public.git_host_policies
for each row
execute function public.set_updated_at();

alter table public.git_host_policies
    enable row level security;

revoke all
    on table public.git_host_policies
    from anon, authenticated;

comment on table public.git_host_policies is
    'Git 원격 조회를 허용하는 호스트와 프로토콜/포트 정책';

insert into public.git_host_policies (
    host,
    display_name,
    provider_type,
    https_enabled,
    https_port,
    ssh_enabled,
    ssh_port
)
values
    (
        'github.com',
        'GitHub',
        'GITHUB',
        true,
        443,
        true,
        22
    ),
    (
        'gitlab.com',
        'GitLab',
        'GITLAB',
        true,
        443,
        true,
        22
    ),
    (
        'bitbucket.org',
        'Bitbucket',
        'BITBUCKET',
        true,
        443,
        true,
        22
    )
on conflict (host) do nothing;

-- 신규/수정되는 프로젝트가 소스 유형에 맞는 입력을 갖도록 한다.
-- 기존 데이터에 repository_url이 비어 있을 수 있으므로 NOT VALID로 추가한다.
-- 기존 데이터를 정리한 뒤 별도 점검에서 VALIDATE CONSTRAINT를 실행할 수 있다.
do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'projects_source_fields_check'
          and conrelid = 'public.projects'::regclass
    ) then
        alter table public.projects
            add constraint projects_source_fields_check
            check (
                (
                    source_type = 'GIT'
                    and repository_url is not null
                    and length(trim(repository_url)) > 0
                    and length(trim(default_branch)) > 0
                )
                or
                (
                    source_type = 'ZIP'
                    and source_file_name is not null
                    and source_file_uri is not null
                )
            )
            not valid;
    end if;
end
$$;

commit;
