-- 004_project_connection.sql
-- 프로젝트 소스(Git/ZIP)와 분석 대상 데이터베이스(DB 직접 연결/DDL 업로드) 정보를 관리한다.
-- DB 비밀번호는 평문 저장 금지. credential_ciphertext에는 Spring Boot에서 암호화한 값만 저장한다.

begin;

alter table public.projects
    add column if not exists source_type varchar(20) not null default 'GIT';

alter table public.projects
    add column if not exists source_file_name varchar(500);

alter table public.projects
    add column if not exists source_file_uri text;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'projects_source_type_check'
          and conrelid = 'public.projects'::regclass
    ) then
        alter table public.projects
            add constraint projects_source_type_check
            check (source_type in ('GIT', 'ZIP'));
    end if;
end
$$;

create table if not exists public.project_database_sources (
    id uuid primary key default gen_random_uuid(),
    project_id uuid not null references public.projects(id) on delete cascade,
    connection_mode varchar(30) not null,
    db_type varchar(30) not null,
    is_primary boolean not null default true,
    host varchar(255),
    port integer,
    database_name varchar(200),
    schema_name varchar(200),
    username varchar(200),
    credential_ciphertext text,
    ddl_file_name varchar(500),
    ddl_storage_uri text,
    status varchar(20) not null default 'PENDING',
    last_tested_at timestamptz,
    last_test_result varchar(20),
    last_error_message text,
    metadata jsonb not null default '{}'::jsonb,
    created_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint project_database_sources_connection_mode_check
        check (connection_mode in ('DB_CONNECTION', 'DDL_UPLOAD')),

    constraint project_database_sources_db_type_check
        check (db_type in ('TIBERO','ORACLE','POSTGRESQL','MYSQL','MARIADB','MSSQL','OTHER')),

    constraint project_database_sources_port_check
        check (port is null or (port > 0 and port <= 65535)),

    constraint project_database_sources_status_check
        check (status in ('PENDING', 'CONNECTED', 'READY', 'FAILED')),

    constraint project_database_sources_last_test_result_check
        check (last_test_result is null or last_test_result in ('SUCCESS', 'FAILED')),

    constraint project_database_sources_mode_fields_check
        check (
            (
                connection_mode = 'DB_CONNECTION'
                and host is not null
                and port is not null
                and database_name is not null
                and schema_name is not null
                and username is not null
            )
            or
            (
                connection_mode = 'DDL_UPLOAD'
                and ddl_file_name is not null
                and ddl_storage_uri is not null
            )
        )
);

create index if not exists idx_project_database_sources_project_id
    on public.project_database_sources(project_id);

create index if not exists idx_project_database_sources_status
    on public.project_database_sources(status);

create unique index if not exists uq_project_database_sources_primary
    on public.project_database_sources(project_id)
    where is_primary = true;

drop trigger if exists trg_project_database_sources_updated_at
    on public.project_database_sources;

create trigger trg_project_database_sources_updated_at
before update on public.project_database_sources
for each row
execute function public.set_updated_at();

alter table public.project_database_sources enable row level security;
revoke all on table public.project_database_sources from anon, authenticated;

comment on table public.project_database_sources is
    '프로젝트 분석 대상 DB 연결 또는 DDL 업로드 메타데이터';

comment on column public.project_database_sources.credential_ciphertext is
    '평문 비밀번호 금지. Spring Boot에서 암호화된 credential만 저장';

comment on column public.project_database_sources.database_name is
    'PostgreSQL DB명 또는 Oracle/Tibero의 서비스/DB 식별자 등 접속 대상 식별값';

commit;
