-- 이전 작업의 추론 설정은 확인할 수 없으므로 값을 채우지 않고 보존한다.
alter table public.analysis_jobs
    add column if not exists thinking_enabled boolean;

-- 새 작업은 추론 모드를 기본으로 켜며 요청마다 선택한 값을 명시해 저장한다.
alter table public.analysis_jobs
    alter column thinking_enabled set default true;

comment on column public.analysis_jobs.thinking_enabled
    is '분석 요청의 추론 모드 선택값. 이전 작업의 미기록 설정은 null로 보존한다.';
