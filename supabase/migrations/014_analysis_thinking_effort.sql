-- 이전 작업의 추론 강도는 알 수 없으므로 값을 채우지 않고 보존한다.
alter table public.analysis_jobs
    add column if not exists thinking_effort text;

-- 새 작업은 보통 강도를 기본으로 사용하며 요청에서 선택한 값을 저장한다.
alter table public.analysis_jobs
    alter column thinking_effort set default 'medium';

alter table public.analysis_jobs
    add constraint analysis_jobs_thinking_effort_check
    check (thinking_effort is null or thinking_effort in ('low', 'medium', 'high'));

comment on column public.analysis_jobs.thinking_effort
    is '분석 요청의 추론 강도 선택값. 이전 작업의 미기록 설정은 null로 보존한다.';
