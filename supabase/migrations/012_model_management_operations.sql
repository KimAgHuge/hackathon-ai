-- 실행 요청을 먼저 보관하고 원격 처리 결과와 상태 변경을 별도 이력으로 남긴다.
begin;

create table if not exists public.model_management_operations (
    id uuid primary key,
    action varchar(30) not null check (action in ('MODEL_PROBE', 'MODEL_ACTIVATE', 'MODEL_ROLLBACK', 'TRAINING_START', 'TRAINING_CANCEL')),
    status varchar(30) not null default 'PENDING' check (status in ('PENDING', 'UNKNOWN', 'PREPARING', 'TRAINING', 'VERIFYING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'PASSED', 'APPLIED', 'ROLLED_BACK')),
    actor_id uuid not null,
    server_role varchar(30) not null check (server_role in ('AI_INFERENCE', 'AI_TRAINING')),
    target_key varchar(64) not null,
    request jsonb not null default '{}'::jsonb,
    result jsonb not null default '{}'::jsonb,
    before_model varchar(500),
    after_model varchar(500),
    error_code varchar(60),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists public.model_management_events (
    id bigint generated always as identity primary key,
    operation_id uuid not null references public.model_management_operations(id) on delete restrict,
    status varchar(30) not null,
    error_code varchar(60),
    summary jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists idx_model_management_operations_recent
    on public.model_management_operations(created_at desc, id desc);
create index if not exists idx_model_management_operations_target
    on public.model_management_operations(target_key, action, created_at desc);
create index if not exists idx_model_management_events_operation
    on public.model_management_events(operation_id, id);

-- 사용자 계정 삭제와 관계없이 요청 당시의 사용자 식별자와 변경 이력을 보존한다.
-- 이력은 추가만 허용한다. 정정이 필요하면 새 실행 요청과 새 이력을 남긴다.
create or replace function public.reject_model_management_event_change()
returns trigger language plpgsql set search_path = public as $$
begin
    raise exception 'Model management events are append-only';
end;
$$;
drop trigger if exists model_management_events_append_only on public.model_management_events;
create trigger model_management_events_append_only
    before update or delete on public.model_management_events
    for each row execute function public.reject_model_management_event_change();

alter table public.model_management_operations enable row level security;
alter table public.model_management_events enable row level security;
revoke all on public.model_management_operations, public.model_management_events from anon, authenticated;

commit;
