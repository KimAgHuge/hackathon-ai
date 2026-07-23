begin;


-- =========================================================
-- 프로젝트
-- =========================================================

create table if not exists public.projects (
                                               id uuid primary key
                                               default gen_random_uuid(),

    name varchar(200) not null,

    description text,

    repository_url text,

    default_branch varchar(200) not null default 'main',

    status varchar(20) not null default 'ACTIVE'
    check (
              status in (
              'ACTIVE',
              'INACTIVE',
              'ARCHIVED'
                        )
    ),

    created_by uuid
    references auth.users(id)
    on delete set null,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- 프로젝트 구성원
--
-- 전역 Role과 별개로
-- 프로젝트 내부에서의 역할
-- =========================================================

create table if not exists public.project_members (
                                                      project_id uuid not null
                                                      references public.projects(id)
    on delete cascade,

    user_id uuid not null
    references auth.users(id)
    on delete cascade,

    member_role varchar(20) not null default 'VIEWER'
    check (
              member_role in (
              'OWNER',
              'MANAGER',
              'ANALYST',
              'VIEWER'
                             )
    ),

    created_at timestamptz not null default now(),

    primary key (
                    project_id,
                    user_id
                )
    );


-- =========================================================
-- 변경 요청
--
-- 사용자가 직접 입력하거나
-- Git Commit / Pull Request에서 생성 가능
-- =========================================================

create table if not exists public.change_requests (
                                                      id uuid primary key
                                                      default gen_random_uuid(),

    project_id uuid not null
    references public.projects(id)
    on delete cascade,

    title varchar(300) not null,

    description text,

    source_type varchar(30) not null default 'MANUAL'
    check (
              source_type in (
              'MANUAL',
              'GIT_COMMIT',
              'PULL_REQUEST',
              'OTHER'
                             )
    ),

    -- commit SHA, PR 번호 등의 외부 식별자
    source_ref varchar(500),

    base_ref varchar(200),

    target_ref varchar(200),

    status varchar(20) not null default 'DRAFT'
    check (
              status in (
              'DRAFT',
              'READY',
              'ANALYZING',
              'COMPLETED',
              'APPROVED',
              'CANCELLED'
                        )
    ),

    -- Git 또는 외부 시스템에서 추가로 받은 정보
    metadata jsonb not null default '{}'::jsonb,

    created_by uuid
    references auth.users(id)
    on delete set null,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- AI 분석 작업
-- =========================================================

create table if not exists public.analysis_jobs (
                                                    id uuid primary key
                                                    default gen_random_uuid(),

    project_id uuid not null
    references public.projects(id)
    on delete cascade,

    change_request_id uuid
    references public.change_requests(id)
    on delete set null,

    requested_by uuid
    references auth.users(id)
    on delete set null,

    analysis_type varchar(30) not null default 'CHANGE_REQUEST'
    check (
              analysis_type in (
              'CHANGE_REQUEST',
              'PROJECT_SCAN',
              'REANALYSIS'
                               )
    ),

    status varchar(20) not null default 'PENDING'
    check (
              status in (
              'PENDING',
              'RUNNING',
              'COMPLETED',
              'FAILED',
              'CANCELLED'
                        )
    ),

    source_progress integer not null default 0
    check (
              source_progress between 0 and 100
          ),

    database_progress integer not null default 0
    check (
              database_progress between 0 and 100
          ),

    relation_progress integer not null default 0
    check (
              relation_progress between 0 and 100
          ),

    -- 분석에 사용된 옵션
    analysis_options jsonb not null default '{}'::jsonb,

    -- 분석 요청 당시의 입력값 보존
    -- Git commit, 변경 요청 내용 등의 Snapshot
    input_snapshot jsonb not null default '{}'::jsonb,

    -- 실제 분석에 사용된 모델 버전
    --
    -- 003에서 model_versions 생성 후 FK 연결
    model_version_id uuid,

    error_message text,

    started_at timestamptz,

    completed_at timestamptz,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- AI 분석 결과
-- =========================================================

create table if not exists public.analysis_results (
                                                       id uuid primary key
                                                       default gen_random_uuid(),

    analysis_job_id uuid not null unique
    references public.analysis_jobs(id)
    on delete cascade,

    -- AI 원본 결과
    original_result jsonb not null,

    -- 사용자가 수정한 최종 결과
    corrected_result jsonb,

    risk_level varchar(20)
    check (
              risk_level is null
              or risk_level in (
              'LOW',
              'MEDIUM',
              'HIGH',
              'CRITICAL',
              'UNKNOWN'
                               )
    ),

    summary text,

    approved boolean not null default false,

    approved_by uuid
    references auth.users(id)
    on delete set null,

    approved_at timestamptz,

    review_note text,

    -- 추론 시간, 토큰량 등 부가정보
    inference_metadata jsonb not null default '{}'::jsonb,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- 인덱스
-- =========================================================

create index if not exists idx_projects_status
    on public.projects(status);


create index if not exists idx_project_members_user_id
    on public.project_members(user_id);


create index if not exists idx_change_requests_project_id
    on public.change_requests(project_id);


create index if not exists idx_change_requests_status
    on public.change_requests(status);


create index if not exists idx_change_requests_created_at
    on public.change_requests(created_at desc);


create index if not exists idx_analysis_jobs_project_id
    on public.analysis_jobs(project_id);


create index if not exists idx_analysis_jobs_change_request_id
    on public.analysis_jobs(change_request_id);


create index if not exists idx_analysis_jobs_requested_by
    on public.analysis_jobs(requested_by);


create index if not exists idx_analysis_jobs_status
    on public.analysis_jobs(status);


create index if not exists idx_analysis_jobs_created_at
    on public.analysis_jobs(created_at desc);


create index if not exists idx_analysis_results_risk_level
    on public.analysis_results(risk_level);


create index if not exists idx_analysis_results_approved
    on public.analysis_results(approved);


-- =========================================================
-- updated_at
-- =========================================================

drop trigger if exists projects_set_updated_at
on public.projects;

create trigger projects_set_updated_at
    before update
    on public.projects
    for each row
    execute function public.set_updated_at();


drop trigger if exists change_requests_set_updated_at
on public.change_requests;

create trigger change_requests_set_updated_at
    before update
    on public.change_requests
    for each row
    execute function public.set_updated_at();


drop trigger if exists analysis_jobs_set_updated_at
on public.analysis_jobs;

create trigger analysis_jobs_set_updated_at
    before update
    on public.analysis_jobs
    for each row
    execute function public.set_updated_at();


drop trigger if exists analysis_results_set_updated_at
on public.analysis_results;

create trigger analysis_results_set_updated_at
    before update
    on public.analysis_results
    for each row
    execute function public.set_updated_at();


-- =========================================================
-- RLS
-- =========================================================

alter table public.projects
    enable row level security;

alter table public.project_members
    enable row level security;

alter table public.change_requests
    enable row level security;

alter table public.analysis_jobs
    enable row level security;

alter table public.analysis_results
    enable row level security;


-- =========================================================
-- Vue → Supabase 업무 데이터 직접 접근 차단
--
-- Browser
--   ↓
-- Spring Boot
--   ↓
-- PostgreSQL
-- =========================================================

revoke all privileges
    on table
    public.projects,
    public.project_members,
    public.change_requests,
    public.analysis_jobs,
    public.analysis_results
    from anon, authenticated;


commit;