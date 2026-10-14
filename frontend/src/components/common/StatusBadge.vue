<script setup>
const props = defineProps({
    status: {
        type: String,
        default: 'UNKNOWN',
    },
})

const styles = {
    ONLINE: 'border-emerald-200 bg-emerald-50 text-emerald-700',
    ACTIVE: 'border-emerald-200 bg-emerald-50 text-emerald-700',
    READY: 'border-blue-200 bg-blue-50 text-blue-700',
    COMPLETED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
    RUNNING: 'border-violet-200 bg-violet-50 text-violet-700',
    FAILED: 'border-rose-200 bg-rose-50 text-rose-700',
    APPROVED: 'border-emerald-200 bg-emerald-50 text-emerald-700',
    ANALYZED: 'border-blue-200 bg-blue-50 text-blue-700',
    REVIEW: 'border-amber-200 bg-amber-50 text-amber-700',
    PENDING: 'border-amber-200 bg-amber-50 text-amber-700',
    REQUESTED: 'border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/70 text-slate-600',
    ANALYZING: 'border-violet-200 bg-violet-50 text-violet-700',
    DRAFT: 'border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/70 text-slate-600',
    SUBMITTED: 'border-blue-200 bg-blue-50 text-blue-700',
    REFINING: 'border-amber-200 bg-amber-50 text-amber-700',
    REVIEWING: 'border-amber-200 bg-amber-50 text-amber-700',
    ESTIMATING: 'border-violet-200 bg-violet-50 text-violet-700',
    REJECTED: 'border-rose-200 bg-rose-50 text-rose-700',
    CANCELLED: 'border-slate-200 dark:border-slate-700 bg-slate-100 text-slate-500',
    STANDBY: 'border-blue-200 bg-blue-50 text-blue-700',
    CORRECTED: 'border-violet-200 bg-violet-50 text-violet-700',
    OFFLINE: 'border-slate-200 dark:border-slate-700 bg-slate-100 text-slate-600',
    ARCHIVED: 'border-slate-200 dark:border-slate-700 bg-slate-100 text-slate-500',
    ERROR: 'border-rose-200 bg-rose-50 text-rose-700',
}

const labels = {
    ONLINE: '정상',
    ACTIVE: '활성',
    READY: '준비됨',
    COMPLETED: '완료',
    RUNNING: '진행 중',
    FAILED: '실패',
    APPROVED: '승인',
    ANALYZED: '분석완료',
    REVIEW: '검토중',
    PENDING: '대기',
    REQUESTED: '요청',
    ANALYZING: '분석중',
    DRAFT: '초안',
    SUBMITTED: '접수',
    REFINING: '구체화 중',
    REVIEWING: '검토 중',
    ESTIMATING: '견적 작성 중',
    REJECTED: '반려',
    CANCELLED: '취소',
    STANDBY: '대기 모델',
    CORRECTED: '수정됨',
    OFFLINE: '오프라인',
    ARCHIVED: '보관',
    ERROR: '오류',
}
</script>

<template>
    <span
        class="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-1 text-[11px] font-bold"
        :class="styles[props.status] || 'border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/70 text-slate-600'"
    >
        <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-current"></span>
        {{ labels[props.status] || props.status }}
    </span>
</template>
