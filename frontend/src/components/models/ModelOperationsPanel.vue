<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getModelOperations, getModelOperation } from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import { formatOperationDate, operationActionLabel, operationErrorMessage, operationStatusLabel } from '@/lib/modelOperations'

const props = defineProps({ refreshKey: { type: Number, default: 0 } })
const records = ref(null)
const loading = ref(false)
const error = ref('')
const detail = ref(null)
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
let generation = 0
let detailGeneration = 0
let disposed = false
const refresh = async () => {
    const current = ++generation
    loading.value = true
    try {
        const value = await getModelOperations()
        if (!disposed && current === generation) {
            records.value = value
            error.value = ''
        }
    } catch (failure) {
        if (!disposed && current === generation) error.value = operationErrorMessage(failure, '작업 이력을 불러오지 못했습니다.')
    } finally {
        if (!disposed && current === generation) loading.value = false
    }
}
const showDetail = async (id) => {
    const current = ++detailGeneration
    detail.value = null
    detailOpen.value = true
    detailLoading.value = true
    detailError.value = ''
    try {
        const value = await getModelOperation(id)
        if (!disposed && current === detailGeneration && detailOpen.value) detail.value = value
    } catch (failure) {
        if (!disposed && current === detailGeneration) detailError.value = operationErrorMessage(failure, '작업 상세를 불러오지 못했습니다.')
    } finally {
        if (!disposed && current === detailGeneration) detailLoading.value = false
    }
}
watch(() => props.refreshKey, refresh)
onMounted(refresh)
onBeforeUnmount(() => {
    disposed = true
    generation += 1
    detailGeneration += 1
})
</script>

<template>
    <section class="min-w-0">
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
            <div>
                <h2 class="text-base font-extrabold">실행 작업 이력</h2>
                <p class="mt-1 text-xs leading-6 text-slate-500 dark:text-slate-400">
                    모델 검증·전환·복구와 학습 요청의 DB 기록입니다. 결과 확인 필요 상태는 상세를 다시 조회해 확인합니다.
                </p>
            </div>
            <BaseButton variant="secondary" size="small" :loading="loading" @click="refresh">이력 새로고침</BaseButton>
        </div>
        <p v-if="error" role="alert" class="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">{{ error }}</p>
        <p v-if="records?.truncated" class="mb-4 text-xs text-amber-700 dark:text-amber-300">최근 {{ records.limit || 100 }}개 작업만 표시합니다.</p>
        <p v-if="loading && !records" role="status" class="py-10 text-center text-sm text-slate-500">작업 이력을 불러오고 있습니다.</p>
        <p
            v-else-if="records && !records.operations?.length"
            class="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400"
        >
            기록된 실행 작업이 없습니다.
        </p>
        <div class="grid gap-3">
            <article
                v-for="operation in records?.operations || []"
                :key="operation.id"
                class="min-w-0 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
            >
                <div class="flex flex-wrap items-start justify-between gap-3">
                    <div class="min-w-0">
                        <p class="text-sm font-bold">{{ operationActionLabel(operation.action) }} · {{ operationStatusLabel(operation.status) }}</p>
                        <p class="mt-1 break-all text-xs text-slate-500 dark:text-slate-400">{{ operation.id }}</p>
                    </div>
                    <BaseButton size="small" variant="secondary" @click="showDetail(operation.id)">{{
                        operation.status === 'UNKNOWN' ? '결과 다시 확인' : '상세 보기'
                    }}</BaseButton>
                </div>
                <p class="mt-3 break-all text-sm leading-6">{{ operation.message || '안내 기록 없음' }}</p>
                <p class="mt-2 break-all text-xs text-slate-500 dark:text-slate-400">
                    {{ operation.beforeModel || '기록 없음' }} → {{ operation.afterModel || '기록 없음' }} ·
                    {{ formatOperationDate(operation.updatedAt || operation.createdAt) }}
                </p>
            </article>
        </div>
        <BaseModal
            v-model="detailOpen"
            title="실행 작업 상세"
            footer-text="결과가 불명확할 때는 이 요청을 다시 조회합니다. 새 전환이나 학습 요청을 자동 실행하지 않습니다."
            ><p v-if="detailLoading" role="status" class="py-10 text-center">작업 상세를 불러오고 있습니다.</p>
            <p v-if="detailError" role="alert" class="text-sm text-rose-700 dark:text-rose-300">{{ detailError }}</p>
            <div v-if="detail && !detailLoading" class="grid gap-4 text-sm leading-7">
                <p class="break-all font-bold">{{ operationActionLabel(detail.action) }} · {{ operationStatusLabel(detail.status) }}</p>
                <p class="break-all">{{ detail.message || '안내 기록 없음' }}</p>
                <dl class="grid gap-3 sm:grid-cols-2">
                    <div
                        v-for="item in [
                            { label: '요청 식별자', value: detail.id },
                            { label: '작업 사용자', value: detail.actorId },
                            {
                                label: '서버 역할',
                                value: { AI_INFERENCE: 'AI 분석/추론 서버', AI_TRAINING: 'AI 학습 서버' }[detail.serverRole] || detail.serverRole,
                            },
                            { label: '오류 코드', value: detail.errorCode },
                            { label: '변경 전 모델', value: detail.beforeModel },
                            { label: '변경 후 모델', value: detail.afterModel },
                        ]"
                        :key="item.label"
                        class="min-w-0"
                    >
                        <dt class="text-xs text-slate-500 dark:text-slate-400">{{ item.label }}</dt>
                        <dd class="break-all">{{ item.value || '기록 없음' }}</dd>
                    </div>
                </dl>
                <BaseButton variant="secondary" @click="showDetail(detail.id)">이 작업 다시 조회</BaseButton>
                <h3 class="font-bold">상태 변화 기록</h3>
                <p v-if="!detail.events?.length" class="text-slate-500">추가 상태 기록이 없습니다.</p>
                <ol class="grid gap-3">
                    <li v-for="(event, index) in detail.events || []" :key="event.id || index" class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">
                        <p class="font-bold">{{ operationStatusLabel(event.status) }}</p>
                        <p class="break-all">{{ event.message || '안내 기록 없음' }}</p>
                        <p v-if="event.errorCode" class="break-all text-xs text-rose-700 dark:text-rose-300">오류 코드 · {{ event.errorCode }}</p>
                        <p v-if="event.summary?.model || event.summary?.previousModel" class="break-all text-xs">
                            {{ event.summary.previousModel || '이전 모델 기록 없음' }} → {{ event.summary.model || '대상 모델 기록 없음' }}
                        </p>
                        <p class="mt-1 text-xs text-slate-500">{{ formatOperationDate(event.createdAt) }}</p>
                    </li>
                </ol>
            </div></BaseModal
        >
    </section>
</template>
