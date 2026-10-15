-- 새 분석 작업은 지정된 워커만 가져간다. 구버전 워커는 새 작업 종류를 조회하지 않는다.
alter table public.analysis_jobs
    add column if not exists target_worker_id varchar(80),
    add column if not exists claimed_by_worker_id varchar(80);

alter table public.analysis_jobs
    drop constraint if exists analysis_jobs_analysis_type_check;

alter table public.analysis_jobs
    add constraint analysis_jobs_analysis_type_check
    check (analysis_type in (
        'CHANGE_REQUEST', 'PROJECT_SCAN', 'PROJECT_SCAN_V2', 'REQUIREMENT', 'REANALYSIS'
    ));

alter table public.analysis_jobs
    drop constraint if exists analysis_jobs_v2_target_worker_check;

alter table public.analysis_jobs
    add constraint analysis_jobs_v2_target_worker_check
    check (analysis_type <> 'PROJECT_SCAN_V2'
           or (target_worker_id is not null and btrim(target_worker_id) <> ''));

create index if not exists idx_analysis_jobs_v2_worker_queue
    on public.analysis_jobs(target_worker_id, created_at, id)
    where status = 'PENDING' and analysis_type = 'PROJECT_SCAN_V2';
