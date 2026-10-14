begin;

-- 등록 해제는 프로젝트와 연결된 요구사항·분석 이력을 보존한다.
alter table public.projects
    add column if not exists del_yn char(1) not null default 'N',
    add column if not exists deleted_at timestamptz,
    add column if not exists deleted_by uuid;

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'projects_del_yn_check'
          and conrelid = 'public.projects'::regclass
    ) then
        alter table public.projects
            add constraint projects_del_yn_check check (del_yn in ('Y', 'N'));
    end if;
end
$$;

create index if not exists idx_projects_active_organization
    on public.projects (organization_id, created_at desc)
    where del_yn = 'N';

comment on column public.projects.del_yn is
    'Y이면 일반 프로젝트 목록·신규 작업 대상에서 제외한다. 관련 데이터는 유지한다.';

commit;
