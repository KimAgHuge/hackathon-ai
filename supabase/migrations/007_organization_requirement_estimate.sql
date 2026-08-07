-- 007_organization_requirement_estimate.sql
-- 회사·조직 tenant, 프로젝트 역할, 고객 요구사항 버전과 견적 검토/공개 흐름을 관리한다.
--
-- 적용 전제:
--   1. 001~006 migration이 순서대로 적용되어 있어야 한다.
--   2. 기존 projects 행은 organization_id가 없으므로 이 migration에서는 nullable로 둔다.
--   3. Backend에 조직 선택/검증을 연결하고 기존 프로젝트를 조직에 배정한 뒤
--      projects.organization_id의 NOT NULL 전환을 별도 migration에서 수행한다.
--   4. 브라우저는 이 테이블들을 Supabase Data API로 직접 사용하지 않는다.

begin;

-- =========================================================
-- 회사·조직 tenant
-- =========================================================

create table if not exists public.organizations (
    id uuid primary key default gen_random_uuid(),
    name varchar(200) not null,
    slug varchar(100) not null unique,
    description text,
    status varchar(20) not null default 'ACTIVE',
    created_by uuid
        references auth.users(id)
        on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint organizations_name_check
        check (length(trim(name)) > 0),

    constraint organizations_slug_check
        check (
            slug = lower(slug)
            and slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        ),

    constraint organizations_status_check
        check (status in ('ACTIVE', 'INACTIVE', 'ARCHIVED'))
);

create table if not exists public.organization_members (
    organization_id uuid not null
        references public.organizations(id)
        on delete cascade,
    user_id uuid not null
        references auth.users(id)
        on delete cascade,
    member_role varchar(20) not null default 'MEMBER',
    status varchar(20) not null default 'ACTIVE',
    assigned_by uuid
        references auth.users(id)
        on delete set null,
    joined_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    primary key (organization_id, user_id),

    constraint organization_members_role_check
        check (member_role in ('OWNER', 'ADMIN', 'MEMBER')),

    constraint organization_members_status_check
        check (status in ('ACTIVE', 'INACTIVE'))
);

create index if not exists idx_organization_members_user_id
    on public.organization_members(user_id);

create index if not exists idx_organization_members_status
    on public.organization_members(organization_id, status);

drop trigger if exists trg_organizations_updated_at
    on public.organizations;

create trigger trg_organizations_updated_at
before update on public.organizations
for each row
execute function public.set_updated_at();

drop trigger if exists trg_organization_members_updated_at
    on public.organization_members;

create trigger trg_organization_members_updated_at
before update on public.organization_members
for each row
execute function public.set_updated_at();

comment on table public.organizations is
    '서비스를 사용하는 회사 또는 조직 tenant';

comment on column public.organizations.slug is
    'URL과 외부 식별에 사용하는 변경 제한 tenant key';

comment on table public.organization_members is
    '조직에 속한 사용자와 조직 수준 역할';

-- =========================================================
-- 프로젝트 tenant와 프로젝트별 업무 역할
-- =========================================================

alter table public.projects
    add column if not exists organization_id uuid;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'projects_organization_id_fkey'
          and conrelid = 'public.projects'::regclass
    ) then
        alter table public.projects
            add constraint projects_organization_id_fkey
            foreign key (organization_id)
            references public.organizations(id)
            on delete restrict;
    end if;
end
$$;

create index if not exists idx_projects_organization_id
    on public.projects(organization_id);

-- 요구사항과 견적에서 project/organization 일치를 복합 FK로 검증한다.
create unique index if not exists uq_projects_id_organization_id
    on public.projects(id, organization_id);

comment on column public.projects.organization_id is
    '프로젝트 소유 조직. 기존 프로젝트 이관 전까지 nullable이며 신규 API에서는 필수로 검증';

alter table public.project_members
    add column if not exists assigned_by uuid
        references auth.users(id)
        on delete set null;

alter table public.project_members
    add column if not exists updated_at timestamptz not null default now();

alter table public.project_members
    drop constraint if exists project_members_member_role_check;

alter table public.project_members
    add constraint project_members_member_role_check
    check (
        member_role in (
            'OWNER',
            'MANAGER',
            'ANALYST',
            'VIEWER',
            'PM',
            'PL',
            'CUSTOMER',
            'DEVELOPER'
        )
    );

drop trigger if exists trg_project_members_updated_at
    on public.project_members;

create trigger trg_project_members_updated_at
before update on public.project_members
for each row
execute function public.set_updated_at();

comment on column public.project_members.member_role is
    '프로젝트 범위 역할. 기존 역할과 PM/PL/CUSTOMER/DEVELOPER를 함께 지원';

-- =========================================================
-- 고객 요구사항과 불변 버전 snapshot
-- =========================================================

create table if not exists public.requirements (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null
        references public.organizations(id)
        on delete restrict,
    project_id uuid not null,
    requirement_key varchar(50),
    title varchar(300) not null,
    source_type varchar(30) not null default 'CUSTOMER',
    source_ref varchar(500),
    priority varchar(20) not null default 'MEDIUM',
    status varchar(30) not null default 'DRAFT',
    customer_visible boolean not null default false,
    requested_by uuid
        references auth.users(id)
        on delete set null,
    customer_confirmed_by uuid
        references auth.users(id)
        on delete set null,
    customer_confirmed_at timestamptz,
    created_by uuid
        references auth.users(id)
        on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint requirements_project_organization_fkey
        foreign key (project_id, organization_id)
        references public.projects(id, organization_id)
        on delete cascade,

    constraint requirements_title_check
        check (length(trim(title)) > 0),

    constraint requirements_key_check
        check (
            requirement_key is null
            or requirement_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,49}$'
        ),

    constraint requirements_source_type_check
        check (
            source_type in (
                'CUSTOMER',
                'PM',
                'GIT_COMMIT',
                'PULL_REQUEST',
                'EXTERNAL',
                'OTHER'
            )
        ),

    constraint requirements_priority_check
        check (priority in ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),

    constraint requirements_status_check
        check (
            status in (
                'DRAFT',
                'SUBMITTED',
                'REFINING',
                'READY',
                'ANALYZING',
                'REVIEWING',
                'ESTIMATING',
                'APPROVED',
                'REJECTED',
                'CANCELLED'
            )
        )
);

create unique index if not exists uq_requirements_project_key
    on public.requirements(project_id, requirement_key)
    where requirement_key is not null;

create unique index if not exists uq_requirements_id_project_organization
    on public.requirements(id, project_id, organization_id);

create index if not exists idx_requirements_organization_status
    on public.requirements(organization_id, status);

create index if not exists idx_requirements_project_status
    on public.requirements(project_id, status);

create index if not exists idx_requirements_requested_by
    on public.requirements(requested_by);

create index if not exists idx_requirements_created_at
    on public.requirements(created_at desc);

create table if not exists public.requirement_versions (
    id uuid primary key default gen_random_uuid(),
    requirement_id uuid not null
        references public.requirements(id)
        on delete cascade,
    version_number integer not null,
    title varchar(300) not null,
    original_content text not null,
    refined_content text,
    acceptance_criteria jsonb not null default '[]'::jsonb,
    attachments_metadata jsonb not null default '[]'::jsonb,
    change_note text,
    is_current boolean not null default true,
    created_by uuid
        references auth.users(id)
        on delete set null,
    created_at timestamptz not null default now(),

    constraint requirement_versions_number_check
        check (version_number > 0),

    constraint requirement_versions_title_check
        check (length(trim(title)) > 0),

    constraint requirement_versions_original_content_check
        check (length(trim(original_content)) > 0),

    constraint requirement_versions_acceptance_criteria_check
        check (jsonb_typeof(acceptance_criteria) = 'array'),

    constraint requirement_versions_attachments_check
        check (jsonb_typeof(attachments_metadata) = 'array'),

    unique (requirement_id, version_number)
);

create unique index if not exists uq_requirement_versions_current
    on public.requirement_versions(requirement_id)
    where is_current = true;

create index if not exists idx_requirement_versions_created_at
    on public.requirement_versions(requirement_id, created_at desc);

drop trigger if exists trg_requirements_updated_at
    on public.requirements;

create trigger trg_requirements_updated_at
before update on public.requirements
for each row
execute function public.set_updated_at();

comment on table public.requirements is
    '고객 요청에서 분석·견적까지 이어지는 요구사항의 현재 workflow 상태';

comment on table public.requirement_versions is
    '요구사항 원문, 정제 내용과 인수 조건의 버전별 snapshot';

comment on column public.requirement_versions.acceptance_criteria is
    '문자열 또는 구조화 객체의 JSON 배열';

comment on column public.requirement_versions.is_current is
    '요구사항별 현재 버전은 하나만 허용. 새 버전 생성과 이전 버전 해제는 한 transaction에서 처리';

-- 분석 입력이 어떤 요구사항 버전을 기준으로 했는지 재현 가능하게 연결한다.
alter table public.analysis_jobs
    add column if not exists requirement_version_id uuid;

alter table public.analysis_jobs
    drop constraint if exists analysis_jobs_analysis_type_check;

alter table public.analysis_jobs
    add constraint analysis_jobs_analysis_type_check
    check (
        analysis_type in (
            'CHANGE_REQUEST',
            'PROJECT_SCAN',
            'REQUIREMENT',
            'REANALYSIS'
        )
    );

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'analysis_jobs_requirement_version_id_fkey'
          and conrelid = 'public.analysis_jobs'::regclass
    ) then
        alter table public.analysis_jobs
            add constraint analysis_jobs_requirement_version_id_fkey
            foreign key (requirement_version_id)
            references public.requirement_versions(id)
            on delete set null;
    end if;
end
$$;

create index if not exists idx_analysis_jobs_requirement_version_id
    on public.analysis_jobs(requirement_version_id);

comment on column public.analysis_jobs.requirement_version_id is
    '분석 요청 시점에 사용한 요구사항 버전 snapshot';

create or replace function public.validate_analysis_job_requirement_scope()
returns trigger
language plpgsql
set search_path = public
as $$
begin
    if new.requirement_version_id is not null
       and not exists (
           select 1
           from public.requirement_versions rv
           join public.requirements r
             on r.id = rv.requirement_id
           where rv.id = new.requirement_version_id
             and r.project_id = new.project_id
       ) then
        raise exception
            'analysis job requirement version must belong to the same project'
            using errcode = '23514';
    end if;

    return new;
end;
$$;

drop trigger if exists trg_analysis_jobs_requirement_scope
    on public.analysis_jobs;

create trigger trg_analysis_jobs_requirement_scope
before insert or update of project_id, requirement_version_id
on public.analysis_jobs
for each row
execute function public.validate_analysis_job_requirement_scope();

-- =========================================================
-- 견적 버전
-- estimates의 각 행이 하나의 견적 version이다.
-- =========================================================

create table if not exists public.estimates (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null
        references public.organizations(id)
        on delete restrict,
    project_id uuid not null,
    requirement_id uuid not null,
    analysis_result_id uuid
        references public.analysis_results(id)
        on delete set null,
    version_number integer not null,
    title varchar(300) not null,
    status varchar(30) not null default 'DRAFT',
    currency char(3) not null default 'KRW',
    effort_unit varchar(20) not null default 'PERSON_DAY',
    minimum_effort numeric(12, 2) not null default 0,
    expected_effort numeric(12, 2) not null default 0,
    maximum_effort numeric(12, 2) not null default 0,
    minimum_duration_days integer not null default 0,
    expected_duration_days integer not null default 0,
    maximum_duration_days integer not null default 0,
    subtotal_amount numeric(19, 2) not null default 0,
    risk_buffer_amount numeric(19, 2) not null default 0,
    total_amount numeric(19, 2) not null default 0,
    assumptions text,
    exclusions text,
    customer_summary text,
    internal_note text,
    customer_visible boolean not null default false,
    submitted_by uuid
        references auth.users(id)
        on delete set null,
    submitted_at timestamptz,
    approved_by uuid
        references auth.users(id)
        on delete set null,
    approved_at timestamptz,
    published_by uuid
        references auth.users(id)
        on delete set null,
    published_at timestamptz,
    created_by uuid
        references auth.users(id)
        on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint estimates_requirement_scope_fkey
        foreign key (requirement_id, project_id, organization_id)
        references public.requirements(id, project_id, organization_id)
        on delete cascade,

    constraint estimates_version_number_check
        check (version_number > 0),

    constraint estimates_title_check
        check (length(trim(title)) > 0),

    constraint estimates_status_check
        check (
            status in (
                'DRAFT',
                'IN_REVIEW',
                'CHANGES_REQUESTED',
                'APPROVED',
                'PUBLISHED',
                'SUPERSEDED',
                'CANCELLED'
            )
        ),

    constraint estimates_currency_check
        check (currency ~ '^[A-Z]{3}$'),

    constraint estimates_effort_unit_check
        check (effort_unit in ('PERSON_HOUR', 'PERSON_DAY', 'PERSON_MONTH')),

    constraint estimates_effort_range_check
        check (
            minimum_effort >= 0
            and minimum_effort <= expected_effort
            and expected_effort <= maximum_effort
        ),

    constraint estimates_duration_range_check
        check (
            minimum_duration_days >= 0
            and minimum_duration_days <= expected_duration_days
            and expected_duration_days <= maximum_duration_days
        ),

    constraint estimates_amount_check
        check (
            subtotal_amount >= 0
            and risk_buffer_amount >= 0
            and total_amount >= 0
            and total_amount = subtotal_amount + risk_buffer_amount
        ),

    constraint estimates_approval_check
        check (
            status not in ('APPROVED', 'PUBLISHED', 'SUPERSEDED')
            or approved_at is not null
        ),

    constraint estimates_publication_check
        check (
            status <> 'PUBLISHED'
            or published_at is not null
        ),

    constraint estimates_customer_visibility_check
        check (not customer_visible or status = 'PUBLISHED'),

    unique (requirement_id, version_number)
);

create unique index if not exists uq_estimates_published_requirement
    on public.estimates(requirement_id)
    where status = 'PUBLISHED';

create index if not exists idx_estimates_organization_status
    on public.estimates(organization_id, status);

create index if not exists idx_estimates_project_status
    on public.estimates(project_id, status);

create index if not exists idx_estimates_requirement_created_at
    on public.estimates(requirement_id, created_at desc);

create index if not exists idx_estimates_analysis_result_id
    on public.estimates(analysis_result_id);

create or replace function public.validate_estimate_analysis_scope()
returns trigger
language plpgsql
set search_path = public
as $$
begin
    if new.analysis_result_id is not null
       and not exists (
           select 1
           from public.analysis_results ar
           join public.analysis_jobs aj
             on aj.id = ar.analysis_job_id
           where ar.id = new.analysis_result_id
             and aj.project_id = new.project_id
       ) then
        raise exception
            'estimate analysis result must belong to the same project'
            using errcode = '23514';
    end if;

    return new;
end;
$$;

drop trigger if exists trg_estimates_analysis_scope
    on public.estimates;

create trigger trg_estimates_analysis_scope
before insert or update of project_id, analysis_result_id
on public.estimates
for each row
execute function public.validate_estimate_analysis_scope();

create table if not exists public.estimate_items (
    id uuid primary key default gen_random_uuid(),
    estimate_id uuid not null
        references public.estimates(id)
        on delete cascade,
    parent_item_id uuid,
    sort_order integer not null default 0,
    category varchar(50) not null default 'OTHER',
    title varchar(300) not null,
    description text,
    origin varchar(30) not null default 'HUMAN_CREATED',
    status varchar(20) not null default 'PROPOSED',
    complexity varchar(20),
    minimum_effort numeric(12, 2) not null default 0,
    expected_effort numeric(12, 2) not null default 0,
    maximum_effort numeric(12, 2) not null default 0,
    minimum_duration_days integer not null default 0,
    expected_duration_days integer not null default 0,
    maximum_duration_days integer not null default 0,
    base_amount numeric(19, 2) not null default 0,
    risk_buffer_amount numeric(19, 2) not null default 0,
    total_amount numeric(19, 2) not null default 0,
    evidence jsonb not null default '[]'::jsonb,
    dependencies jsonb not null default '[]'::jsonb,
    assumptions text,
    created_by uuid
        references auth.users(id)
        on delete set null,
    updated_by uuid
        references auth.users(id)
        on delete set null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint estimate_items_sort_order_check
        check (sort_order >= 0),

    constraint estimate_items_category_check
        check (length(trim(category)) > 0),

    constraint estimate_items_title_check
        check (length(trim(title)) > 0),

    constraint estimate_items_origin_check
        check (origin in ('AI_SUGGESTED', 'HUMAN_CREATED', 'HUMAN_ADJUSTED')),

    constraint estimate_items_status_check
        check (status in ('PROPOSED', 'CONFIRMED', 'EXCLUDED')),

    constraint estimate_items_complexity_check
        check (
            complexity is null
            or complexity in ('LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH')
        ),

    constraint estimate_items_effort_range_check
        check (
            minimum_effort >= 0
            and minimum_effort <= expected_effort
            and expected_effort <= maximum_effort
        ),

    constraint estimate_items_duration_range_check
        check (
            minimum_duration_days >= 0
            and minimum_duration_days <= expected_duration_days
            and expected_duration_days <= maximum_duration_days
        ),

    constraint estimate_items_amount_check
        check (
            base_amount >= 0
            and risk_buffer_amount >= 0
            and total_amount >= 0
            and total_amount = base_amount + risk_buffer_amount
        ),

    constraint estimate_items_evidence_check
        check (jsonb_typeof(evidence) = 'array'),

    constraint estimate_items_dependencies_check
        check (jsonb_typeof(dependencies) = 'array'),

    constraint estimate_items_parent_check
        check (parent_item_id is null or parent_item_id <> id),

    constraint estimate_items_id_estimate_unique
        unique (id, estimate_id),

    constraint estimate_items_parent_scope_fkey
        foreign key (parent_item_id, estimate_id)
        references public.estimate_items(id, estimate_id)
        on delete cascade
);

create index if not exists idx_estimate_items_estimate_sort
    on public.estimate_items(estimate_id, sort_order);

create index if not exists idx_estimate_items_parent_id
    on public.estimate_items(parent_item_id);

create table if not exists public.estimate_reviews (
    id uuid primary key default gen_random_uuid(),
    estimate_id uuid not null
        references public.estimates(id)
        on delete cascade,
    reviewer_id uuid
        references auth.users(id)
        on delete set null,
    reviewer_role varchar(20) not null,
    decision varchar(30) not null,
    comment text,
    review_snapshot jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),

    constraint estimate_reviews_reviewer_role_check
        check (reviewer_role in ('PM', 'PL', 'DEVELOPER', 'ADMIN')),

    constraint estimate_reviews_decision_check
        check (decision in ('COMMENT', 'APPROVE', 'REQUEST_CHANGES', 'REJECT')),

    constraint estimate_reviews_comment_check
        check (
            decision not in ('COMMENT', 'REQUEST_CHANGES', 'REJECT')
            or length(trim(coalesce(comment, ''))) > 0
        ),

    constraint estimate_reviews_snapshot_check
        check (jsonb_typeof(review_snapshot) = 'object')
);

create index if not exists idx_estimate_reviews_estimate_created_at
    on public.estimate_reviews(estimate_id, created_at desc);

create index if not exists idx_estimate_reviews_reviewer_id
    on public.estimate_reviews(reviewer_id);

drop trigger if exists trg_estimates_updated_at
    on public.estimates;

create trigger trg_estimates_updated_at
before update on public.estimates
for each row
execute function public.set_updated_at();

drop trigger if exists trg_estimate_items_updated_at
    on public.estimate_items;

create trigger trg_estimate_items_updated_at
before update on public.estimate_items
for each row
execute function public.set_updated_at();

comment on table public.estimates is
    '요구사항별 견적 version과 검토·승인·고객 공개 상태';

comment on column public.estimates.customer_visible is
    'PUBLISHED 상태에서만 true. 고객 응답은 Backend가 프로젝트 역할과 함께 검증';

comment on column public.estimates.internal_note is
    '고객 응답에 포함하지 않는 PM/PL 내부 메모';

comment on table public.estimate_items is
    '견적의 작업 분해, 작업량·기간 범위, 금액과 분석 근거';

comment on column public.estimate_items.evidence is
    'AI 또는 검토자가 제시한 파일/API/SQL/DB 객체 근거의 JSON 배열';

comment on table public.estimate_reviews is
    'PL/개발자/PM의 견적 검토 결정과 당시 값 snapshot';

-- =========================================================
-- 업무 권한
-- 전역 permission과 프로젝트 member_role을 Backend에서 함께 검증한다.
-- =========================================================

insert into public.permissions (
    code,
    name,
    description
)
values
    ('ORGANIZATION_READ', '조직 조회', '소속 조직과 구성원 조회'),
    ('ORGANIZATION_MANAGE', '조직 관리', '조직과 조직 구성원 관리'),
    ('REQUIREMENT_READ', '요구사항 조회', '프로젝트 요구사항과 버전 조회'),
    ('REQUIREMENT_CREATE', '요구사항 등록', '고객 또는 PM 요구사항 등록'),
    ('REQUIREMENT_UPDATE', '요구사항 수정', '요구사항 정제와 새 버전 생성'),
    ('REQUIREMENT_CONFIRM', '요구사항 확정', '분석 가능한 요구사항으로 확정'),
    ('ESTIMATE_READ', '견적 조회', '권한 범위의 견적 조회'),
    ('ESTIMATE_CREATE', '견적 생성', '분석 결과 기반 견적 초안 생성'),
    ('ESTIMATE_UPDATE', '견적 수정', '견적과 작업 항목 수정'),
    ('ESTIMATE_REVIEW', '견적 검토', '견적 승인 또는 변경 요청'),
    ('ESTIMATE_PUBLISH', '견적 공개', '승인된 견적을 고객에게 공개')
on conflict (code) do nothing;

-- ADMIN은 신규 권한 전체를 받는다.
insert into public.role_permissions (
    role_id,
    permission_id
)
select
    r.id,
    p.id
from public.roles r
cross join public.permissions p
where r.code = 'ADMIN'
  and p.code in (
      'ORGANIZATION_READ',
      'ORGANIZATION_MANAGE',
      'REQUIREMENT_READ',
      'REQUIREMENT_CREATE',
      'REQUIREMENT_UPDATE',
      'REQUIREMENT_CONFIRM',
      'ESTIMATE_READ',
      'ESTIMATE_CREATE',
      'ESTIMATE_UPDATE',
      'ESTIMATE_REVIEW',
      'ESTIMATE_PUBLISH'
  )
on conflict do nothing;

-- ANALYST는 PM/PL/개발 업무 API를 사용할 수 있고 실제 범위는 프로젝트 역할로 제한한다.
insert into public.role_permissions (
    role_id,
    permission_id
)
select
    r.id,
    p.id
from public.roles r
join public.permissions p
  on p.code in (
      'ORGANIZATION_READ',
      'REQUIREMENT_READ',
      'REQUIREMENT_CREATE',
      'REQUIREMENT_UPDATE',
      'REQUIREMENT_CONFIRM',
      'ESTIMATE_READ',
      'ESTIMATE_CREATE',
      'ESTIMATE_UPDATE',
      'ESTIMATE_REVIEW',
      'ESTIMATE_PUBLISH'
  )
where r.code = 'ANALYST'
on conflict do nothing;

-- VIEWER/CUSTOMER 계정은 Backend의 프로젝트 CUSTOMER 역할 검증 후 공개 데이터만 조회한다.
insert into public.role_permissions (
    role_id,
    permission_id
)
select
    r.id,
    p.id
from public.roles r
join public.permissions p
  on p.code in (
      'ORGANIZATION_READ',
      'REQUIREMENT_READ',
      'ESTIMATE_READ'
  )
where r.code = 'VIEWER'
on conflict do nothing;

-- =========================================================
-- RLS와 브라우저 직접 접근 차단
-- Spring Boot가 tenant와 프로젝트 역할을 검증한 뒤 DB에 접근한다.
-- =========================================================

alter table public.organizations
    enable row level security;

alter table public.organization_members
    enable row level security;

alter table public.requirements
    enable row level security;

alter table public.requirement_versions
    enable row level security;

alter table public.estimates
    enable row level security;

alter table public.estimate_items
    enable row level security;

alter table public.estimate_reviews
    enable row level security;

revoke all privileges
    on table
    public.organizations,
    public.organization_members,
    public.requirements,
    public.requirement_versions,
    public.estimates,
    public.estimate_items,
    public.estimate_reviews
    from anon, authenticated;

commit;
