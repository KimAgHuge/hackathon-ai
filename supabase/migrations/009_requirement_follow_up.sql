-- A follow-up creates a new immutable requirement version. Existing analysis results stay unchanged.
alter table public.requirement_versions
    add column if not exists clarifications jsonb not null default '[]'::jsonb;

alter table public.requirement_versions
    add column if not exists source_analysis_job_id uuid;

alter table public.requirement_versions
    add column if not exists parent_version_id uuid;

-- Connect pre-existing versions in their recorded order before enforcing new links.
with ordered as (
    select id, lag(id) over (partition by requirement_id order by version_number) as previous_id
    from public.requirement_versions
)
update public.requirement_versions as version
set parent_version_id = ordered.previous_id
from ordered
where version.id = ordered.id
  and version.parent_version_id is null
  and ordered.previous_id is not null;

-- The composite key prevents a version from pointing at another requirement's history.
create unique index if not exists uq_requirement_versions_id_requirement
    on public.requirement_versions(id, requirement_id);

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'requirement_versions_clarifications_array_check'
          and conrelid = 'public.requirement_versions'::regclass
    ) then
        alter table public.requirement_versions
            add constraint requirement_versions_clarifications_array_check
            check (jsonb_typeof(clarifications) = 'array');
    end if;
    if not exists (
        select 1 from pg_constraint
        where conname = 'requirement_versions_source_analysis_job_id_fkey'
          and conrelid = 'public.requirement_versions'::regclass
    ) then
        alter table public.requirement_versions
            add constraint requirement_versions_source_analysis_job_id_fkey
            foreign key (source_analysis_job_id) references public.analysis_jobs(id)
            on delete set null;
    end if;
    if not exists (
        select 1 from pg_constraint
        where conname = 'requirement_versions_parent_version_id_fkey'
          and conrelid = 'public.requirement_versions'::regclass
    ) then
        alter table public.requirement_versions
            add constraint requirement_versions_parent_version_id_fkey
            foreign key (parent_version_id, requirement_id)
            references public.requirement_versions(id, requirement_id)
            on delete no action deferrable initially deferred;
    end if;
    if not exists (
        select 1 from pg_constraint
        where conname = 'requirement_versions_parent_presence_check'
          and conrelid = 'public.requirement_versions'::regclass
    ) then
        -- Enforce new writes without rejecting an unexpected legacy orphan during migration.
        alter table public.requirement_versions
            add constraint requirement_versions_parent_presence_check
            check (version_number = 1 or parent_version_id is not null)
            not valid;
    end if;
    if not exists (
        select 1 from pg_constraint
        where conname = 'requirement_versions_no_self_parent_check'
          and conrelid = 'public.requirement_versions'::regclass
    ) then
        alter table public.requirement_versions
            add constraint requirement_versions_no_self_parent_check
            check (parent_version_id is distinct from id);
    end if;
end $$;

create unique index if not exists uq_requirement_versions_parent
    on public.requirement_versions(parent_version_id)
    where parent_version_id is not null;

create index if not exists idx_requirement_versions_source_analysis_job
    on public.requirement_versions(source_analysis_job_id)
    where source_analysis_job_id is not null;
