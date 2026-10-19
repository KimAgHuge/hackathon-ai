-- =========================================================
-- pgvector
--
-- 지금 당장 vector column을 만들지는 않음.
-- 향후 004_documents_rag.sql에서 사용.
-- =========================================================

create extension if not exists vector
with schema extensions;


begin;


-- =========================================================
-- AI 모델
--
-- 논리적인 모델 단위
--
-- 예)
-- doenaai-code-analyzer
-- doenaai-embedding
-- doenaai-reranker
-- =========================================================

create table if not exists public.ai_models (
                                                id uuid primary key
                                                default gen_random_uuid(),

    name varchar(200) not null unique,

    description text,

    model_type varchar(30) not null default 'LLM'
    check (
              model_type in (
              'LLM',
              'EMBEDDING',
              'RERANKER'
                            )
    ),

    -- HuggingFace / MLX 등의 원본 모델
    provider varchar(100),

    base_model_ref varchar(500),

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
-- 학습 데이터셋
--
-- AI 학습 서버가 사용할 데이터
--
-- 예:
-- 분석 결과 + 사용자 수정 결과 → JSONL 생성
-- =========================================================

create table if not exists public.training_datasets (
                                                        id uuid primary key
                                                        default gen_random_uuid(),

    name varchar(200) not null,

    description text,

    source_type varchar(30) not null default 'MANUAL'
    check (
              source_type in (
              'MANUAL',
              'ANALYSIS_RESULT',
              'MIXED',
              'EXTERNAL'
                             )
    ),

    dataset_format varchar(30) not null default 'JSONL'
    check (
              dataset_format in (
              'JSONL',
              'JSON',
              'CSV',
              'PARQUET',
              'OTHER'
                                )
    ),

    -- LOCAL / Supabase Storage / S3 등을 모두 수용
    storage_provider varchar(30) not null default 'LOCAL'
    check (
              storage_provider in (
              'LOCAL',
              'SUPABASE_STORAGE',
              'S3',
              'OTHER'
                                  )
    ),

    storage_uri text,

    example_count integer not null default 0
    check (
              example_count >= 0
          ),

    status varchar(20) not null default 'PREPARING'
    check (
              status in (
              'PREPARING',
              'READY',
              'ARCHIVED',
              'FAILED'
                        )
    ),

    metadata jsonb not null default '{}'::jsonb,

    created_by uuid
    references auth.users(id)
    on delete set null,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- 학습 작업
--
-- M4 Max Training Worker에서 처리
-- =========================================================

create table if not exists public.training_jobs (
                                                    id uuid primary key
                                                    default gen_random_uuid(),

    model_id uuid not null
    references public.ai_models(id)
    on delete restrict,

    dataset_id uuid not null
    references public.training_datasets(id)
    on delete restrict,

    requested_by uuid
    references auth.users(id)
    on delete set null,

    status varchar(20) not null default 'PENDING'
    check (
              status in (
              'PENDING',
              'CLAIMED',
              'RUNNING',
              'COMPLETED',
              'FAILED',
              'CANCELLED'
                        )
    ),

    progress integer not null default 0
    check (
              progress between 0 and 100
          ),

    -- 작업을 가져간 AI 학습 서버
    -- 예: macbook-m4max
    worker_name varchar(200),

    -- 실제 학습에 사용한 Base Model 기록
    base_model_ref varchar(500),

    -- epoch / learningRate / LoRA rank 등
    hyper_parameters jsonb not null default '{}'::jsonb,

    -- 학습 산출물이 생성될 위치
    output_uri text,

    log_uri text,

    error_message text,

    claimed_at timestamptz,

    started_at timestamptz,

    completed_at timestamptz,

    heartbeat_at timestamptz,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- 모델 버전
--
-- 학습 완료 후 생성되는 실제 버전
--
-- 예)
--
-- doenaai-code-analyzer
--    ├─ 1.0.0
--    ├─ 1.1.0-LoRA
--    └─ 1.2.0-LoRA
-- =========================================================

create table if not exists public.model_versions (
                                                     id uuid primary key
                                                     default gen_random_uuid(),

    model_id uuid not null
    references public.ai_models(id)
    on delete cascade,

    training_job_id uuid
    references public.training_jobs(id)
    on delete set null,

    version varchar(100) not null,

    artifact_type varchar(30) not null
    check (
              artifact_type in (
              'BASE',
              'LORA',
              'MERGED',
              'EXTERNAL'
                               )
    ),

    status varchar(20) not null default 'READY'
    check (
              status in (
              'PREPARING',
              'READY',
              'VALIDATED',
              'FAILED',
              'ARCHIVED'
                        )
    ),

    -- HuggingFace / MLX 모델 ID 등
    base_model_ref varchar(500),

    storage_provider varchar(30)
    check (
              storage_provider is null
              or storage_provider in (
              'LOCAL',
              'SUPABASE_STORAGE',
              'S3',
              'OTHER'
                                     )
    ),

    -- 전체 모델 파일 위치
    artifact_uri text,

    -- LoRA Adapter인 경우 Adapter 위치
    adapter_uri text,

    checksum varchar(256),

    metadata jsonb not null default '{}'::jsonb,

    created_by uuid
    references auth.users(id)
    on delete set null,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now(),

    unique (
               model_id,
               version
           )
    );


-- =========================================================
-- 모델 평가
--
-- 학습된 모델을 활성화하기 전에 평가
-- =========================================================

create table if not exists public.model_evaluations (
                                                        id uuid primary key
                                                        default gen_random_uuid(),

    model_version_id uuid not null
    references public.model_versions(id)
    on delete cascade,

    dataset_id uuid
    references public.training_datasets(id)
    on delete set null,

    evaluation_type varchar(30) not null default 'AUTOMATIC'
    check (
              evaluation_type in (
              'AUTOMATIC',
              'MANUAL',
              'BENCHMARK'
                                 )
    ),

    status varchar(20) not null default 'PENDING'
    check (
              status in (
              'PENDING',
              'RUNNING',
              'PASSED',
              'FAILED'
                        )
    ),

    -- 정확도, loss, human score 등 자유롭게 저장
    metrics jsonb not null default '{}'::jsonb,

    overall_score numeric(10, 4),

    notes text,

    evaluated_by uuid
    references auth.users(id)
    on delete set null,

    evaluated_at timestamptz,

    created_at timestamptz not null default now()
    );


-- =========================================================
-- 모델 배포
--
-- 어떤 서버에서 어떤 모델 버전이 활성화되어 있는지 관리
--
-- 예:
-- mac-studio-ai / ANALYSIS / v1.2.0 / ACTIVE
--
-- 향후 RAG:
-- mac-studio-ai / EMBEDDING / embedding-v1 / ACTIVE
-- =========================================================

create table if not exists public.model_deployments (
                                                        id uuid primary key
                                                        default gen_random_uuid(),

    model_version_id uuid not null
    references public.model_versions(id)
    on delete restrict,

    -- 예: mac-studio-ai
    server_name varchar(200) not null,

    deployment_role varchar(30) not null
    check (
              deployment_role in (
              'ANALYSIS',
              'EMBEDDING',
              'RERANKING'
                                 )
    ),

    status varchar(20) not null default 'INACTIVE'
    check (
              status in (
              'ACTIVE',
              'INACTIVE',
              'FAILED'
                        )
    ),

    -- 내부 AI 분석/추론 서버 Endpoint
    -- 필요할 때만 사용
    endpoint_url text,

    deployed_by uuid
    references auth.users(id)
    on delete set null,

    deployed_at timestamptz,

    undeployed_at timestamptz,

    metadata jsonb not null default '{}'::jsonb,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- 동시에 같은 서버/역할에 ACTIVE 모델은 하나만 허용
--
-- mac-studio-ai
--    ANALYSIS  → 1개
--    EMBEDDING → 1개
-- =========================================================

create unique index if not exists uq_model_deployments_active
    on public.model_deployments (
    server_name,
    deployment_role
    )
    where status = 'ACTIVE';


-- =========================================================
-- 분석 작업과 실제 사용 모델 연결
--
-- analysis_jobs.model_version_id는 002에서 미리 생성
-- =========================================================

do $$
begin

    if not exists (
        select 1
        from pg_constraint
        where conname = 'fk_analysis_jobs_model_version'
    ) then

alter table public.analysis_jobs
    add constraint fk_analysis_jobs_model_version
        foreign key (model_version_id)
            references public.model_versions(id)
            on delete set null;

end if;

end;
$$;


-- =========================================================
-- 인덱스
-- =========================================================

create index if not exists idx_ai_models_type
    on public.ai_models(model_type);


create index if not exists idx_ai_models_status
    on public.ai_models(status);


create index if not exists idx_training_datasets_status
    on public.training_datasets(status);


create index if not exists idx_training_jobs_model_id
    on public.training_jobs(model_id);


create index if not exists idx_training_jobs_dataset_id
    on public.training_jobs(dataset_id);


create index if not exists idx_training_jobs_status
    on public.training_jobs(status);


create index if not exists idx_training_jobs_created_at
    on public.training_jobs(created_at);


create index if not exists idx_model_versions_model_id
    on public.model_versions(model_id);


create index if not exists idx_model_versions_status
    on public.model_versions(status);


create index if not exists idx_model_evaluations_model_version
    on public.model_evaluations(model_version_id);


create index if not exists idx_model_deployments_model_version
    on public.model_deployments(model_version_id);


create index if not exists idx_model_deployments_server
    on public.model_deployments(server_name);


-- =========================================================
-- updated_at
-- =========================================================

drop trigger if exists ai_models_set_updated_at
on public.ai_models;

create trigger ai_models_set_updated_at
    before update
    on public.ai_models
    for each row
    execute function public.set_updated_at();


drop trigger if exists training_datasets_set_updated_at
on public.training_datasets;

create trigger training_datasets_set_updated_at
    before update
    on public.training_datasets
    for each row
    execute function public.set_updated_at();


drop trigger if exists training_jobs_set_updated_at
on public.training_jobs;

create trigger training_jobs_set_updated_at
    before update
    on public.training_jobs
    for each row
    execute function public.set_updated_at();


drop trigger if exists model_versions_set_updated_at
on public.model_versions;

create trigger model_versions_set_updated_at
    before update
    on public.model_versions
    for each row
    execute function public.set_updated_at();


drop trigger if exists model_deployments_set_updated_at
on public.model_deployments;

create trigger model_deployments_set_updated_at
    before update
    on public.model_deployments
    for each row
    execute function public.set_updated_at();


-- =========================================================
-- RLS
-- =========================================================

alter table public.ai_models
    enable row level security;

alter table public.training_datasets
    enable row level security;

alter table public.training_jobs
    enable row level security;

alter table public.model_versions
    enable row level security;

alter table public.model_evaluations
    enable row level security;

alter table public.model_deployments
    enable row level security;


-- =========================================================
-- 브라우저 직접 접근 차단
-- =========================================================

revoke all privileges
    on table
    public.ai_models,
    public.training_datasets,
    public.training_jobs,
    public.model_versions,
    public.model_evaluations,
    public.model_deployments
    from anon, authenticated;


commit;
