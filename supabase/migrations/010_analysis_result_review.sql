-- 기존 AI 원본은 유지하고 사람의 검토 결정을 별도로 기록한다.
alter table public.analysis_results
    add column if not exists review_status varchar(16) not null default 'PENDING',
    add column if not exists reviewed_by uuid references auth.users(id) on delete set null,
    add column if not exists reviewed_at timestamptz;

-- 과거에 승인 처리된 결과는 새 상태와 검토 이력에 반영한다.
update public.analysis_results
set review_status = 'APPROVED',
    reviewed_by = approved_by,
    reviewed_at = approved_at
where approved = true and review_status = 'PENDING';

do $$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'analysis_results_review_status_check'
          and conrelid = 'public.analysis_results'::regclass
    ) then
        alter table public.analysis_results
            add constraint analysis_results_review_status_check
            check (review_status in ('PENDING', 'APPROVED', 'REJECTED'));
    end if;
end
$$;

create index if not exists idx_analysis_results_review_status
    on public.analysis_results(review_status);
