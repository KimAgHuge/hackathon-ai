<script setup>
import { computed } from 'vue'

const props = defineProps({ status: { type: String, default: '' } })
const labels = {
    READY: '등록됨',
    PREPARING: '준비 중',
    VALIDATED: '검증 기록',
    ACTIVE: '활성 기록',
    INACTIVE: '비활성',
    ARCHIVED: '보관',
    STANDBY: '대기',
    PENDING: '대기',
    QUEUED: '대기',
    CLAIMED: '작업 할당',
    RUNNING: '진행 중',
    COMPLETED: '완료',
    FAILED: '실패',
    CANCELLED: '취소',
    PASSED: '통과 기록',
    APPROVED: '승인 기록',
    DEPLOYED: '배포 기록',
    ROLLED_BACK: '복구 기록',
    AVAILABLE: '응답 확인',
    UNAVAILABLE: '확인 불가',
    UNCONFIGURED: '연결 설정 필요',
}
const tone = computed(() => {
    if (['PASSED', 'COMPLETED', 'AVAILABLE'].includes(props.status))
        return 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300'
    if (['FAILED', 'UNAVAILABLE'].includes(props.status))
        return 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-800 dark:bg-rose-950/50 dark:text-rose-300'
    if (['RUNNING', 'READY', 'ACTIVE', 'DEPLOYED'].includes(props.status))
        return 'border-blue-200 bg-blue-50 text-blue-700 dark:border-blue-800 dark:bg-blue-950/50 dark:text-blue-300'
    return 'border-slate-200 bg-slate-100 text-slate-600 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-300'
})
</script>

<template>
    <span class="inline-flex max-w-full items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-bold" :class="tone">
        <span class="h-1.5 w-1.5 shrink-0 rounded-full bg-current" aria-hidden="true"></span>
        <span class="break-all">{{ labels[status] || status || '미확인' }}</span>
    </span>
</template>
