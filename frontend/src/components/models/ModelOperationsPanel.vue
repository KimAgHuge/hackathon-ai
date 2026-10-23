<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getModelOperations, getModelOperation, cancelModelTraining } from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import {
    activeTrainingStatuses,
    createTrainingPoller,
    formatOperationDate,
    operationActionLabel,
    operationErrorMessage,
    operationStatusLabel,
    uncertainRequestError,
} from '@/lib/modelOperations'

const props = defineProps({ canTrain: Boolean, visible: { type: Boolean, default: true }, refreshKey: { type: Number, default: 0 } })
const emit = defineEmits(['changed'])
const records = ref(null)
const loading = ref(false)
const error = ref('')
const message = ref('')
const detail = ref(null)
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const cancelTarget = ref(null)
const cancelOpen = ref(false)
const cancelConfirmed = ref(false)
const cancelBusy = ref(false)
const cancelError = ref('')
const pendingCancel = ref(null)
const page = ref(0)
const pageSize = 10
const search = ref('')
const appliedSearch = ref('')
const statusFilter = ref('')
let detailGeneration = 0
let disposed = false
const operations = computed(() => (Array.isArray(records.value?.operations) ? records.value.operations : []))
const isTraining = (operation) => ['TRAINING_START', 'TRAIN'].includes(operation?.action)
const sourceModel = (operation) => operation?.target?.sourceModel || operation?.sourceModel || '모델 미기록'
const selectedModel = (operation) => operation?.target?.selectedModel || operation?.selectedModel || sourceModel(operation)
const targetModel = (operation) =>
    isTraining(operation) ? selectedModel(operation) : operation?.afterModel || operation?.model || operation?.target?.model || '모델 미기록'
const hasActive = computed(() => operations.value.some((operation) => isTraining(operation) && activeTrainingStatuses.has(operation.status)))
const totalPages = computed(() => Math.max(1, records.value?.totalPages || 0))
const canUseRows = computed(() => Boolean(records.value) && !loading.value && !error.value)
const columns = [
    { key: 'createdAt', label: '실행 일시', minWidth: '170px' },
    { key: 'action', label: '작업', minWidth: '140px' },
    { key: 'target', label: '대상 모델', minWidth: '220px' },
    { key: 'status', label: '상태', minWidth: '150px' },
    {
        key: 'actions',
        label: '상세',
        minWidth: '180px',
        headerClass: 'sticky right-0 z-20 border-l border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800',
        cellClass: 'sticky right-0 z-10 border-l border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900',
    },
]
const statusOptions = [
    { value: '', label: '전체 상태' },
    ...['PENDING', 'PREPARING', 'TRAINING', 'VERIFYING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'PASSED', 'APPLIED', 'ROLLED_BACK', 'UNKNOWN'].map((value) => ({
        value,
        label: operationStatusLabel(value),
    })),
]
const statusClass = (status) => {
    if (status === 'FAILED') return 'bg-rose-50 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300'
    if (status === 'UNKNOWN') return 'bg-amber-50 text-amber-800 dark:bg-amber-950/40 dark:text-amber-200'
    if (['SUCCEEDED', 'PASSED', 'APPLIED', 'ROLLED_BACK'].includes(status)) return 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300'
    if (activeTrainingStatuses.has(status)) return 'bg-blue-50 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300'
    return 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'
}
const validPage = (value) =>
    Array.isArray(value?.operations) &&
    value.operations.length <= pageSize &&
    value.operations.every((operation) => operation && typeof operation.id === 'string' && operation.id.trim()) &&
    Number.isSafeInteger(value.page) &&
    value.page >= 0 &&
    value.pageSize === pageSize &&
    Number.isSafeInteger(value.totalCount) &&
    value.totalCount >= value.operations.length &&
    Number.isSafeInteger(value.totalPages) &&
    value.totalPages === Math.ceil(value.totalCount / pageSize) &&
    value.page < Math.max(1, value.totalPages)
const progressValue = (value) =>
    value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) && Number(value) >= 0 && Number(value) <= 100 ? Number(value) : null
const progressLabel = (value) => (progressValue(value) === null ? '미기록' : `${progressValue(value)}%`)
const metricValue = (value, unit = '') =>
    typeof value === 'number' && Number.isFinite(value) && value >= 0 ? `${value.toLocaleString('ko-KR', { maximumFractionDigits: 6 })}${unit}` : '미기록'
const metricItems = (operation) =>
    [
        { label: '학습 자료', key: 'trainingSamples', unit: '건' },
        { label: '검증 자료', key: 'validationSamples', unit: '건' },
        { label: '시험 자료', key: 'testSamples', unit: '건' },
        { label: '제외된 시험 자료', key: 'excludedTestSamples', unit: '건' },
        { label: '최종 학습 손실', key: 'finalTrainingLoss' },
        { label: '학습 전 검증 손실', key: 'validationLossBefore' },
        { label: '학습 후 검증 손실', key: 'validationLossAfter' },
        { label: '어댑터 텐서 수', key: 'adapterTensors' },
        { label: '최대 GPU 메모리', key: 'peakGpuGiB', unit: ' GiB' },
        { label: '재로딩 최대 차이', key: 'reloadMaxLogitDifference' },
    ]
        .filter((item) => typeof operation?.metrics?.[item.key] === 'number')
        .map((item) => ({ label: item.label, value: metricValue(operation.metrics[item.key], item.unit) }))
const detailItems = computed(() => [
    { label: '요청 식별자', value: detail.value?.id },
    { label: '작업 사용자', value: detail.value?.actorId },
    { label: '서버 역할', value: { AI_INFERENCE: 'AI 분석/추론 서버', AI_TRAINING: 'AI 학습 서버' }[detail.value?.serverRole] || detail.value?.serverRole },
    { label: '오류 코드', value: detail.value?.errorCode },
    ...(isTraining(detail.value)
        ? [
              { label: '학습 모델', value: selectedModel(detail.value) },
              ...(selectedModel(detail.value) !== sourceModel(detail.value) ? [{ label: '승인 자료 출처 모델', value: sourceModel(detail.value) }] : []),
              { label: '학습 기반 모델', value: detail.value?.target?.baseModel },
              { label: '기반 모델 버전', value: detail.value?.target?.revision },
              { label: '진행률', value: progressLabel(detail.value?.progress) },
              { label: '반복 횟수', value: detail.value?.iterations == null ? '미기록' : `${detail.value.iterations}회` },
              { label: '접수 시각', value: formatOperationDate(detail.value?.createdAt) },
              { label: '최근 갱신', value: formatOperationDate(detail.value?.updatedAt) },
              ...metricItems(detail.value),
          ]
        : [
              { label: '변경 전 모델', value: detail.value?.beforeModel },
              { label: '변경 후 모델', value: detail.value?.afterModel || detail.value?.model },
              { label: '접수 시각', value: formatOperationDate(detail.value?.createdAt) },
              { label: '최근 갱신', value: formatOperationDate(detail.value?.updatedAt) },
          ]),
])
const monitor = createTrainingPoller({
    async fetchStatus() {
        const value = await getModelOperations({ page: page.value, size: pageSize, query: appliedSearch.value, status: statusFilter.value })
        if (!validPage(value)) throw new Error('MODEL_OPERATION_PAGINATION_REQUIRED')
        return { ...value, jobs: value.operations.filter(isTraining) }
    },
    isHidden: () => document.hidden || !props.visible,
    applyStatus(value) {
        records.value = value
        page.value = value.page
        loading.value = false
        error.value = ''
    },
    onError(failure) {
        loading.value = false
        error.value =
            failure.message === 'MODEL_OPERATION_PAGINATION_REQUIRED'
                ? '실행 이력의 페이지 정보를 확인하지 못했습니다. 백엔드를 재시작한 뒤 다시 조회해 주세요.'
                : operationErrorMessage(failure, '작업 이력 조회에 실패해 자동 조회를 멈췄습니다. 수동으로 다시 불러와 주세요.')
    },
})
const refresh = async () => {
    if (document.hidden || !props.visible) return
    loading.value = true
    await monitor.refresh()
}
const loadPage = async (nextPage = 0) => {
    monitor.pause()
    page.value = nextPage
    records.value = null
    error.value = ''
    detailGeneration += 1
    detailOpen.value = false
    await refresh()
}
const searchHistory = () => {
    appliedSearch.value = search.value.trim()
    return loadPage()
}
const filterStatus = (value) => {
    statusFilter.value = value
    return loadPage()
}
const movePage = (nextPage) => {
    if (!canUseRows.value || nextPage < 0 || nextPage >= totalPages.value) return
    return loadPage(nextPage)
}
const onVisibility = () => {
    if (document.hidden) monitor.pause()
    else if (!error.value) refresh()
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
const openDetail = (id) => {
    if (canUseRows.value && operations.value.some((operation) => operation.id === id)) return showDetail(id)
}
const openCancel = (operation) => {
    if (
        !canUseRows.value ||
        !operations.value.some((current) => current.id === operation.id) ||
        !props.canTrain ||
        cancelBusy.value ||
        pendingCancel.value ||
        !isTraining(operation) ||
        !activeTrainingStatuses.has(operation.status)
    )
        return
    cancelTarget.value = operation
    cancelOpen.value = true
    cancelConfirmed.value = false
    cancelError.value = ''
}
const finishCancel = async (result) => {
    const status = result.operation?.status || result.status
    if (!['SUCCEEDED', 'CANCELLED', 'FAILED'].includes(status)) {
        cancelError.value = '취소 요청 결과가 아직 확정되지 않았습니다. 새 요청을 만들지 말고 기존 요청 상태를 먼저 확인해 주세요.'
        return
    }
    pendingCancel.value = null
    cancelOpen.value = false
    cancelConfirmed.value = false
    if (status === 'FAILED') {
        cancelError.value = result.message || '학습 취소 요청을 완료하지 못했습니다. 작업 이력과 서버 상태를 확인해 주세요.'
        message.value = ''
    } else {
        cancelError.value = ''
        message.value = '취소 요청 결과를 확인했습니다. 대상 학습의 최종 상태는 아래 실행 작업 이력에서 확인해 주세요.'
    }
    await refresh()
    emit('changed')
}
const submitCancel = async () => {
    if (!props.canTrain || cancelBusy.value || (!pendingCancel.value && !cancelConfirmed.value)) return
    if (!pendingCancel.value) {
        if (!canUseRows.value) return
        const current = operations.value.find((operation) => operation.id === cancelTarget.value?.id)
        if (!current || !isTraining(current) || !activeTrainingStatuses.has(current.status)) {
            cancelConfirmed.value = false
            cancelError.value = '대상 학습의 상태가 바뀌었습니다. 최신 상태를 확인한 뒤 다시 선택해 주세요.'
            return
        }
        pendingCancel.value = { id: current.id, body: { operationId: crypto.randomUUID() } }
    }
    cancelBusy.value = true
    cancelError.value = ''
    try {
        const request = pendingCancel.value
        const result = await cancelModelTraining(request.id, request.body)
        if (!disposed) await finishCancel(result)
    } catch (failure) {
        if (disposed) return
        cancelError.value = operationErrorMessage(failure, '취소 응답을 확인하지 못했습니다. 기존 요청 상태를 먼저 확인해 주세요.')
        if (!uncertainRequestError(failure)) pendingCancel.value = null
    } finally {
        if (!disposed) cancelBusy.value = false
    }
}
const checkPendingCancel = async () => {
    if (!pendingCancel.value || cancelBusy.value) return
    cancelBusy.value = true
    cancelError.value = ''
    try {
        const result = await getModelOperation(pendingCancel.value.body.operationId)
        if (!disposed) await finishCancel(result)
    } catch (failure) {
        if (!disposed) cancelError.value = operationErrorMessage(failure, '기존 취소 요청을 확인하지 못했습니다. 같은 요청 식별자로만 수동 재시도해 주세요.')
    } finally {
        if (!disposed) cancelBusy.value = false
    }
}
watch(() => props.refreshKey, refresh)
watch(
    () => props.visible,
    (visible) => {
        if (visible) refresh()
        else monitor.pause()
    }
)
onMounted(() => {
    refresh()
    document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
    disposed = true
    monitor.stop()
    detailGeneration += 1
    document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
    <section class="min-w-0">
        <div class="mb-6 flex flex-wrap items-center justify-between gap-4">
            <div class="min-w-0 flex-1 basis-72">
                <h2 class="text-base font-extrabold">실행 작업 이력</h2>
                <p class="mt-2 text-xs leading-6 text-slate-500 dark:text-slate-400">모델 검증·변경·복구와 학습의 진행 상태, 완료 결과를 함께 확인합니다.</p>
            </div>
            <BaseButton variant="secondary" size="small" :loading="loading" @click="refresh">이력 새로고침</BaseButton>
        </div>
        <p v-if="hasActive" class="mb-4 text-xs leading-6 text-blue-700 dark:text-blue-300">
            진행 중인 학습이 있어 5초마다 상태를 확인합니다. 화면이 숨겨지거나 조회에 실패하면 자동 조회를 멈춥니다.
        </p>
        <p v-if="message" role="status" class="mb-4 rounded-lg bg-blue-50 p-3 text-sm leading-6 text-blue-800 dark:bg-blue-950/40 dark:text-blue-200">
            {{ message }}
        </p>
        <p v-if="error" role="alert" class="mb-4 rounded-lg bg-rose-50 p-3 text-sm text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">{{ error }}</p>
        <p
            v-if="cancelError && !cancelOpen"
            role="alert"
            class="mb-4 rounded-lg bg-rose-50 p-3 text-sm leading-6 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300"
        >
            {{ cancelError }}
        </p>
        <div v-if="pendingCancel && !cancelOpen" class="mb-5 rounded-xl border border-amber-200 p-4 dark:border-amber-800">
            <p class="break-all text-xs leading-6">확인 중인 취소 요청 · {{ pendingCancel.body.operationId }}</p>
            <div class="mt-3 flex flex-wrap gap-2">
                <BaseButton size="small" variant="secondary" :disabled="cancelBusy" @click="checkPendingCancel">기존 요청 상태 확인</BaseButton>
                <BaseButton size="small" variant="secondary" :disabled="cancelBusy || !canTrain" @click="submitCancel">같은 요청 수동 재시도</BaseButton>
            </div>
        </div>
        <form class="mb-5 flex flex-wrap items-end gap-3" @submit.prevent="searchHistory">
            <BaseInput
                v-model="search"
                label="작업 이력 검색"
                wrapper-class="min-w-0 flex-1 basis-72"
                placeholder="작업 종류, 모델명, 요청 식별자 검색"
                maxlength="200"
            />
            <BaseSelect
                :model-value="statusFilter"
                label="작업 상태"
                wrapper-class="w-full sm:w-44"
                :options="statusOptions"
                @update:model-value="filterStatus"
            />
            <BaseButton type="submit" variant="secondary">검색</BaseButton>
        </form>
        <p class="mb-4 text-xs leading-6 text-slate-500 dark:text-slate-400">
            저장된 전체 이력을 검색하고 한 페이지에 10건씩 조회합니다. 진행률과 결과의 자세한 내용은 상세 보기에서 확인합니다.
        </p>
        <BaseTable
            :columns="columns"
            :rows="operations"
            :loading="loading"
            :empty-text="appliedSearch || statusFilter ? '검색 조건에 맞는 실행 작업이 없습니다.' : '기록된 실행 작업이 없습니다.'"
        >
            <template #cell-createdAt="{ row }">
                <span class="whitespace-nowrap text-xs">{{ formatOperationDate(row.createdAt) }}</span>
            </template>
            <template #cell-action="{ row }">
                <span class="text-sm font-semibold">{{ operationActionLabel(row.action) }}</span>
                <p class="mt-1 max-w-56 break-all text-xs leading-5 text-slate-500 dark:text-slate-400">{{ row.id }}</p>
            </template>
            <template #cell-target="{ row }">
                <p class="max-w-sm break-all text-sm font-semibold">{{ targetModel(row) }}</p>
                <p v-if="isTraining(row) && selectedModel(row) !== sourceModel(row)" class="mt-1 max-w-sm break-all text-xs text-slate-500 dark:text-slate-400">
                    승인 자료 출처 · {{ sourceModel(row) }}
                </p>
            </template>
            <template #cell-status="{ row }">
                <span class="inline-flex whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-bold" :class="statusClass(row.status)">{{
                    operationStatusLabel(row.status)
                }}</span>
                <p v-if="isTraining(row)" class="mt-1 text-xs text-slate-500 dark:text-slate-400">진행률 · {{ progressLabel(row.progress) }}</p>
            </template>
            <template #cell-actions="{ row }">
                <div class="flex flex-wrap gap-2">
                    <BaseButton size="small" variant="secondary" :disabled="!canUseRows" @click="openDetail(row.id)">{{
                        row.status === 'UNKNOWN' ? '결과 다시 확인' : '상세 보기'
                    }}</BaseButton>
                    <BaseButton
                        v-if="canTrain && isTraining(row) && activeTrainingStatuses.has(row.status)"
                        size="small"
                        variant="secondary"
                        :disabled="!canUseRows || cancelBusy || Boolean(pendingCancel)"
                        @click="openCancel(row)"
                        >취소 요청</BaseButton
                    >
                </div>
            </template>
        </BaseTable>
        <div class="mt-4 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-500 dark:text-slate-400">
            <p>총 {{ (records?.totalCount || 0).toLocaleString('ko-KR') }}건 · 페이지당 10건</p>
            <div class="flex items-center gap-3">
                <BaseButton size="small" variant="secondary" :disabled="!canUseRows || page <= 0" @click="movePage(page - 1)">이전</BaseButton>
                <span class="min-w-16 text-center font-bold">{{ page + 1 }} / {{ totalPages }}</span>
                <BaseButton size="small" variant="secondary" :disabled="!canUseRows || page >= totalPages - 1" @click="movePage(page + 1)">다음</BaseButton>
            </div>
        </div>
        <BaseModal
            v-model="detailOpen"
            title="실행 작업 상세"
            footer-text="결과가 불명확할 때는 이 요청을 다시 조회합니다. 새 변경이나 학습 요청을 자동 실행하지 않습니다."
        >
            <p v-if="detailLoading" role="status" class="py-10 text-center">작업 상세를 불러오고 있습니다.</p>
            <p v-if="detailError" role="alert" class="text-sm text-rose-700 dark:text-rose-300">{{ detailError }}</p>
            <div v-if="detail && !detailLoading" class="grid gap-4 text-sm leading-7">
                <p class="break-all font-bold">{{ operationActionLabel(detail.action) }} · {{ operationStatusLabel(detail.status) }}</p>
                <p class="break-all">{{ detail.message || '안내 기록 없음' }}</p>
                <dl class="grid gap-4 sm:grid-cols-2">
                    <div v-for="item in detailItems" :key="item.label" class="min-w-0">
                        <dt class="text-xs text-slate-500 dark:text-slate-400">{{ item.label }}</dt>
                        <dd class="mt-2 break-all">{{ item.value || '기록 없음' }}</dd>
                    </div>
                </dl>
                <p
                    v-if="isTraining(detail) && detail.status === 'SUCCEEDED'"
                    class="rounded-lg bg-blue-50 p-4 text-xs leading-6 text-blue-800 dark:bg-blue-950/40 dark:text-blue-200"
                >
                    추가 학습과 결과 저장·재로딩을 완료했습니다. 새 모델 생성과 등록은 모델 작업에서 진행하며 운영 모델은 자동 변경되지 않습니다.
                </p>
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
            </div>
        </BaseModal>
        <BaseModal
            :model-value="cancelOpen"
            title="학습 취소 요청 확인"
            :busy="cancelBusy"
            footer-text="안전한 중단 지점에서 취소하며 이미 만든 자료와 산출물을 임의로 삭제하지 않습니다."
            @update:model-value="cancelOpen = $event"
        >
            <div class="grid gap-4 text-sm leading-7">
                <p class="break-all font-bold">취소 대상 · {{ cancelTarget?.id }}</p>
                <p class="break-all">학습 모델 · {{ selectedModel(cancelTarget) }}</p>
                <p>취소 요청 뒤 안전한 중단 지점에서 학습을 멈춥니다. 즉시 종료를 보증하지 않으며 학습이 먼저 완료됐다면 완료 상태가 유지될 수 있습니다.</p>
                <label class="flex items-start gap-3"
                    ><input v-model="cancelConfirmed" type="checkbox" class="mt-1.5 h-4 w-4 shrink-0" :disabled="cancelBusy || Boolean(pendingCancel)" /><span
                        >대상 학습과 안전한 중단 범위를 확인했습니다.</span
                    ></label
                >
                <p v-if="cancelError" role="alert" class="text-rose-700 dark:text-rose-300">{{ cancelError }}</p>
                <p v-if="pendingCancel" class="break-all text-xs text-slate-500">요청 식별자 · {{ pendingCancel.body.operationId }}</p>
                <div class="flex flex-wrap gap-2">
                    <BaseButton :disabled="!cancelConfirmed || Boolean(pendingCancel) || !canTrain" :loading="cancelBusy" @click="submitCancel"
                        >확인 후 취소 요청</BaseButton
                    ><BaseButton v-if="pendingCancel" variant="secondary" :disabled="cancelBusy" @click="checkPendingCancel">기존 요청 상태 확인</BaseButton
                    ><BaseButton v-if="pendingCancel" variant="secondary" :disabled="cancelBusy || !canTrain" @click="submitCancel"
                        >같은 요청 수동 재시도</BaseButton
                    >
                </div>
            </div>
        </BaseModal>
    </section>
</template>
