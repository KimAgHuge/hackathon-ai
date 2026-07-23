begin;

-- =========================================================
-- 사용자 프로필
-- Supabase Auth auth.users와 1:1
-- =========================================================

create table if not exists public.profiles (
                                               id uuid primary key
                                               references auth.users(id)
    on delete cascade,

    display_name varchar(100) not null,

    department varchar(100),

    job_title varchar(100),

    status varchar(20) not null default 'ACTIVE'
    check (
              status in (
              'ACTIVE',
              'INACTIVE',
              'BLOCKED'
                        )
    ),

    last_login_at timestamptz,

    created_at timestamptz not null default now(),

    updated_at timestamptz not null default now()
    );


-- =========================================================
-- 역할
-- =========================================================

create table if not exists public.roles (
                                            id bigint generated always as identity primary key,

                                            code varchar(50) not null unique,

    name varchar(100) not null,

    description varchar(500),

    created_at timestamptz not null default now()
    );


-- =========================================================
-- 권한
-- =========================================================

create table if not exists public.permissions (
                                                  id bigint generated always as identity primary key,

                                                  code varchar(100) not null unique,

    name varchar(100) not null,

    description varchar(500),

    created_at timestamptz not null default now()
    );


-- =========================================================
-- 사용자 - 역할
-- =========================================================

create table if not exists public.user_roles (
                                                 user_id uuid not null
                                                 references auth.users(id)
    on delete cascade,

    role_id bigint not null
    references public.roles(id)
    on delete cascade,

    assigned_by uuid
    references auth.users(id)
    on delete set null,

    assigned_at timestamptz not null default now(),

    primary key (
                    user_id,
                    role_id
                )
    );


-- =========================================================
-- 역할 - 권한
-- =========================================================

create table if not exists public.role_permissions (
                                                       role_id bigint not null
                                                       references public.roles(id)
    on delete cascade,

    permission_id bigint not null
    references public.permissions(id)
    on delete cascade,

    created_at timestamptz not null default now(),

    primary key (
                    role_id,
                    permission_id
                )
    );


-- =========================================================
-- 기본 역할
-- =========================================================

insert into public.roles (
    code,
    name,
    description
)
values
    (
        'ADMIN',
        '관리자',
        '시스템 전체 관리 권한'
    ),
    (
        'ANALYST',
        '분석 담당자',
        '프로젝트 및 AI 분석 수행 권한'
    ),
    (
        'VIEWER',
        '조회 사용자',
        '프로젝트 및 분석 결과 조회 권한'
    )
    on conflict (code) do nothing;


-- =========================================================
-- 기본 권한
-- =========================================================

insert into public.permissions (
    code,
    name,
    description
)
values
    (
        'DASHBOARD_READ',
        '대시보드 조회',
        '대시보드 조회'
    ),

    (
        'PROJECT_READ',
        '프로젝트 조회',
        '프로젝트 조회'
    ),
    (
        'PROJECT_CREATE',
        '프로젝트 등록',
        '프로젝트 등록'
    ),
    (
        'PROJECT_UPDATE',
        '프로젝트 수정',
        '프로젝트 수정'
    ),

    (
        'CHANGE_REQUEST_READ',
        '변경요청 조회',
        '변경요청 조회'
    ),
    (
        'CHANGE_REQUEST_CREATE',
        '변경요청 등록',
        '변경요청 등록'
    ),
    (
        'CHANGE_REQUEST_UPDATE',
        '변경요청 수정',
        '변경요청 수정'
    ),

    (
        'ANALYSIS_READ',
        '분석 조회',
        'AI 분석 결과 조회'
    ),
    (
        'ANALYSIS_EXECUTE',
        '분석 실행',
        'AI 변경 영향도 분석 실행'
    ),
    (
        'ANALYSIS_APPROVE',
        '분석 승인',
        'AI 분석 결과 승인'
    ),

    (
        'DOCUMENT_READ',
        '문서 조회',
        'RAG 및 프로젝트 문서 조회'
    ),
    (
        'DOCUMENT_UPLOAD',
        '문서 등록',
        'RAG 및 프로젝트 문서 등록'
    ),

    (
        'MODEL_READ',
        'AI 모델 조회',
        'AI 모델 및 버전 조회'
    ),
    (
        'MODEL_TRAIN',
        'AI 모델 학습',
        'AI 모델 학습 작업 생성'
    ),
    (
        'MODEL_EVALUATE',
        'AI 모델 평가',
        '학습 모델 평가'
    ),
    (
        'MODEL_DEPLOY',
        'AI 모델 배포',
        'AI 서버에 모델 활성화'
    ),
    (
        'MODEL_ROLLBACK',
        'AI 모델 롤백',
        '기존 AI 모델 버전으로 롤백'
    ),

    (
        'SYSTEM_SETTING_READ',
        '설정 조회',
        '시스템 설정 조회'
    ),
    (
        'SYSTEM_SETTING_WRITE',
        '설정 변경',
        '시스템 설정 변경'
    ),

    (
        'USER_MANAGE',
        '사용자 관리',
        '사용자 및 역할 관리'
    )
    on conflict (code) do nothing;


-- =========================================================
-- ADMIN
-- 전체 권한
-- =========================================================

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
    on conflict do nothing;


-- =========================================================
-- ANALYST
-- =========================================================

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
                            'DASHBOARD_READ',

                            'PROJECT_READ',
                            'PROJECT_CREATE',
                            'PROJECT_UPDATE',

                            'CHANGE_REQUEST_READ',
                            'CHANGE_REQUEST_CREATE',
                            'CHANGE_REQUEST_UPDATE',

                            'ANALYSIS_READ',
                            'ANALYSIS_EXECUTE',
                            'ANALYSIS_APPROVE',

                            'DOCUMENT_READ',
                            'DOCUMENT_UPLOAD',

                            'MODEL_READ',
                            'MODEL_TRAIN',
                            'MODEL_EVALUATE',

                            'SYSTEM_SETTING_READ'
                  )
where r.code = 'ANALYST'
    on conflict do nothing;


-- =========================================================
-- VIEWER
-- =========================================================

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
                            'DASHBOARD_READ',
                            'PROJECT_READ',
                            'CHANGE_REQUEST_READ',
                            'ANALYSIS_READ',
                            'DOCUMENT_READ',
                            'MODEL_READ',
                            'SYSTEM_SETTING_READ'
                  )
where r.code = 'VIEWER'
    on conflict do nothing;


-- =========================================================
-- updated_at 공통 Trigger 함수
-- 002, 003에서도 재사용
-- =========================================================

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin

    new.updated_at = now();

return new;

end;
$$;


drop trigger if exists profiles_set_updated_at
on public.profiles;

create trigger profiles_set_updated_at
    before update
    on public.profiles
    for each row
    execute function public.set_updated_at();


-- =========================================================
-- Supabase Auth 사용자 생성
--
-- auth.users
--     ↓
-- profiles 자동 생성
--     ↓
-- VIEWER 기본 권한 부여
-- =========================================================

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare

default_role_id bigint;

begin

insert into public.profiles (
    id,
    display_name,
    status
)
values (
           new.id,

           coalesce(
                   nullif(
                           new.raw_user_meta_data ->> 'display_name',
                ''
            ),
                   nullif(
                           split_part(
                                   coalesce(new.email, ''),
                                   '@',
                                   1
                           ),
                           ''
                   ),
                   '사용자'
           ),

           'ACTIVE'
       )
    on conflict (id) do nothing;


select id
into default_role_id
from public.roles
where code = 'VIEWER';


if default_role_id is not null then

        insert into public.user_roles (
            user_id,
            role_id
        )
        values (
            new.id,
            default_role_id
        )
        on conflict do nothing;

end if;


return new;

end;
$$;


drop trigger if exists on_auth_user_created
on auth.users;

create trigger on_auth_user_created
    after insert
    on auth.users
    for each row
    execute function public.handle_new_user();


-- =========================================================
-- 인덱스
-- =========================================================

create index if not exists idx_user_roles_role_id
    on public.user_roles(role_id);

create index if not exists idx_role_permissions_permission_id
    on public.role_permissions(permission_id);


-- =========================================================
-- RLS
-- =========================================================

alter table public.profiles
    enable row level security;

alter table public.roles
    enable row level security;

alter table public.permissions
    enable row level security;

alter table public.user_roles
    enable row level security;

alter table public.role_permissions
    enable row level security;


-- =========================================================
-- 프론트엔드의 Data API 직접 접근 차단
-- 실제 업무 DB 접근은 Spring Boot 담당
-- =========================================================

revoke all privileges
    on table
    public.profiles,
    public.roles,
    public.permissions,
    public.user_roles,
    public.role_permissions
    from anon, authenticated;


commit;