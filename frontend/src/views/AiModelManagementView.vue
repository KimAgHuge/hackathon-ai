<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getModelRegistry, getModelRuntime, getModelVersion, registerModelVersion, recordModelEvaluation } from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import ModelStateBadge from '@/components/models/ModelStateBadge.vue'
import ModelVersionDetailModal from '@/components/models/ModelVersionDetailModal.vue'
import ModelVersionRegisterModal from '@/components/models/ModelVersionRegisterModal.vue'
import ModelRuntimeControl from '@/components/models/ModelRuntimeControl.vue'
import ModelTrainingPanel from '@/components/models/ModelTrainingPanel.vue'
import ModelOperationsPanel from '@/components/models/ModelOperationsPanel.vue'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const registry = ref(null)
const runtime = ref(null)
const loading = ref(false)
const registryAvailable = ref(false)
const registryError = ref('')
const runtimeError = ref('')
const notice = ref('')
const activeTab = ref('versions')
const search = ref('')
const statusFilter = ref('')
const registerOpen = ref(false)
const registerBusy = ref(false)
const registerError = ref('')
const operationRefreshKey = ref(0)
const detailOpen = ref(false)
const detail = ref(null)
const detailLoading = ref(false)
const detailError = ref('')
const selectedVersionId = ref('')
const evaluationBusy = ref(false)
const evaluationError = ref('')
const evaluationSuccess = ref('')
let registryGeneration = 0
let detailGeneration = 0
let disposed = false

const canRegister = computed(() => authStore.hasPermission('MODEL_TRAIN') || authStore.hasPermission('MODEL_DEPLOY'))
const canEvaluate = computed(() => authStore.hasPermission('MODEL_EVALUATE'))
const canDeploy = computed(() => authStore.hasPermission('MODEL_DEPLOY'))
const canRollback = computed(() => authStore.hasPermission('MODEL_ROLLBACK'))
const canTrain = computed(() => authStore.hasPermission('MODEL_TRAIN'))
const asList = (value) => (Array.isArray(value) ? value : [])
const models = computed(() => asList(registry.value?.models))
const versions = computed(() => asList(registry.value?.versions))
const deployments = computed(() => asList(registry.value?.deployments))
const tabs = computed(() => [
    { key: 'versions', label: '모델 버전', count: versions.value.length },
    { key: 'trainingJobs', label: '학습 작업', count: null },
    { key: 'deployments', label: '배포 이력', count: deployments.value.length },
    { key: 'operations', label: '실행 작업 이력', count: null },
])
const statusLabels = {
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
    APPROVED: '승인 기록',
    DEPLOYED: '배포 기록',
    ROLLED_BACK: '복구 기록',
}
const currentRows = computed(() => ({ versions: versions.value, deployments: deployments.value })[activeTab.value] || [])
const statusOptions = computed(() => [
    { value: '', label: '모든 상태' },
    ...[...new Set(currentRows.value.map((row) => row.status).filter(Boolean))].map((status) => ({ value: status, label: statusLabels[status] || status })),
])
const filteredRows = computed(() => {
    const keyword = search.value.trim().toLocaleLowerCase()
    return currentRows.value.filter((row) => {
        const text = [row.id, row.modelName, row.version, row.baseModelRef, row.datasetName, row.workerName, row.serverName, row.deploymentRole]
            .filter(Boolean)
            .join(' ')
            .toLocaleLowerCase()
        return (!statusFilter.value || row.status === statusFilter.value) && (!keyword || text.includes(keyword))
    })
})
const executionReason = computed(
    () => registry.value?.capabilities?.reason || '등록 파일의 수신·변환·직접 배포는 지원하지 않습니다. 설치 모델 전환은 별도 영역에서 진행합니다.'
)
const truncated = computed(() => registry.value?.truncated?.[activeTab.value] === true)
const versionColumns = [
    { key: 'version', label: '모델 / 버전', minWidth: '220px' },
    { key: 'baseModelRef', label: '기반 모델 / 유형', minWidth: '230px' },
    { key: 'datasetName', label: '학습 데이터셋', minWidth: '180px' },
    { key: 'latestEvaluationScore', label: '최근 평가', minWidth: '140px' },
    { key: 'status', label: '등록 상태', minWidth: '120px' },
    { key: 'createdAt', label: '등록 일시', minWidth: '170px' },
    {
        key: 'actions',
        label: '상세',
        minWidth: '100px',
        headerClass: 'sticky right-0 z-20 border-l border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800',
        cellClass: 'sticky right-0 z-10 border-l border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900',
    },
]
const deploymentColumns = [
    { key: 'version', label: '모델 / 버전', minWidth: '220px' },
    { key: 'serverName', label: '배포 서버', minWidth: '200px' },
    { key: 'deploymentRole', label: '역할', minWidth: '130px' },
    { key: 'status', label: '저장된 상태', minWidth: '130px' },
    { key: 'deployedAt', label: '배포 일시', minWidth: '170px' },
    {
        key: 'actions',
        label: '준비 상태',
        minWidth: '120px',
        headerClass: 'sticky right-0 z-20 border-l border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800',
        cellClass: 'sticky right-0 z-10 border-l border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900',
    },
]
const artifactLabel = (value) => ({ BASE: '기반 모델', LORA: 'LoRA 어댑터', MERGED: '병합 모델', EXTERNAL: '외부 모델' })[value] || '기록 없음'
const dateLabel = (value) => {
    if (!value) return '기록 없음'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '시각 확인 불가' : date.toLocaleString('ko-KR')
}
const scoreLabel = (value) => (value === null || value === undefined || value === '' ? '미측정' : `${value}점`)
const errorMessage = (error, fallback) => {
    const status = error?.response?.status
    if (status === 401) return '로그인이 만료되었습니다. 다시 로그인한 뒤 시도해 주세요.'
    if (status === 403) return '이 작업에 필요한 권한이 없습니다. 관리자에게 권한을 확인해 주세요.'
    const message = error?.response?.data?.message
    return typeof message === 'string' && message.trim() ? message : fallback
}
const refreshModels = async () => {
    const generation = ++registryGeneration
    loading.value = true
    const [catalogResult, runtimeResult] = await Promise.allSettled([getModelRegistry(), getModelRuntime()])
    if (disposed || generation !== registryGeneration) return
    if (catalogResult.status === 'fulfilled') {
        registry.value = catalogResult.value
        registryAvailable.value = true
        registryError.value = ''
    } else {
        registryAvailable.value = false
        registryError.value = errorMessage(catalogResult.reason, '모델 관리 기록을 불러오지 못했습니다. 서버 연결 상태를 확인하고 다시 시도해 주세요.')
    }
    if (runtimeResult.status === 'fulfilled') {
        runtime.value = runtimeResult.value
        runtimeError.value = ''
    } else {
        runtime.value = null
        runtimeError.value = errorMessage(runtimeResult.reason, '추론 서버 상태를 확인하지 못했습니다. 관리 기록 조회와는 별도로 다시 확인해 주세요.')
    }
    loading.value = false
}
const selectTab = (key) => {
    activeTab.value = key
    search.value = ''
    statusFilter.value = ''
}
const resetFilters = () => {
    search.value = ''
    statusFilter.value = ''
}
const refreshAfterOperation = () => {
    operationRefreshKey.value += 1
    refreshModels()
}
const openRegister = () => {
    if (!canRegister.value || !registryAvailable.value || loading.value) return
    registerError.value = ''
    registerOpen.value = true
}
const refreshDetail = async (id = selectedVersionId.value) => {
    if (!id) return
    const generation = ++detailGeneration
    detailLoading.value = !detail.value
    detailError.value = ''
    try {
        const result = await getModelVersion(id)
        if (disposed || generation !== detailGeneration || !detailOpen.value || selectedVersionId.value !== id) return
        detail.value = result
    } catch (error) {
        if (disposed || generation !== detailGeneration || !detailOpen.value || selectedVersionId.value !== id) return
        detailError.value = errorMessage(error, '버전 상세를 불러오지 못했습니다. 서버 연결 상태를 확인하고 다시 시도해 주세요.')
    } finally {
        if (!disposed && generation === detailGeneration) detailLoading.value = false
    }
}
const openDetail = (id, initial = null) => {
    if (!id) return Promise.resolve()
    selectedVersionId.value = id
    detail.value = initial
    detailError.value = ''
    evaluationError.value = ''
    evaluationSuccess.value = ''
    detailOpen.value = true
    return refreshDetail(id)
}
const closeDetail = (open) => {
    if (evaluationBusy.value) return
    detailOpen.value = open
    if (!open) {
        detailGeneration += 1
        detailLoading.value = false
    }
}
const registerVersion = async (body) => {
    if (registerBusy.value || !canRegister.value || !registryAvailable.value) return
    registerBusy.value = true
    registerError.value = ''
    let saved
    try {
        saved = await registerModelVersion(body)
    } catch (error) {
        if (!disposed) registerError.value = errorMessage(error, '등록 정보를 저장하지 못했습니다. 입력 내용과 서버 연결을 확인해 주세요.')
    } finally {
        if (!disposed) registerBusy.value = false
    }
    if (!saved || disposed) return
    registerOpen.value = false
    notice.value = '모델 버전 등록 정보를 서버에 저장했습니다. 파일 복사, 학습, 평가와 배포는 실행하지 않았습니다.'
    await Promise.all([refreshModels(), openDetail(saved.id, saved)])
}
const saveEvaluation = async (body) => {
    if (evaluationBusy.value || !canEvaluate.value || !detail.value) return
    const id = selectedVersionId.value
    evaluationBusy.value = true
    evaluationError.value = ''
    evaluationSuccess.value = ''
    let saved
    try {
        saved = await recordModelEvaluation(id, body)
    } catch (error) {
        if (!disposed && selectedVersionId.value === id)
            evaluationError.value = errorMessage(error, '수동 평가 기록을 저장하지 못했습니다. 입력 내용과 서버 연결을 확인해 주세요.')
    }
    if (saved && !disposed && selectedVersionId.value === id) {
        detail.value = saved
        evaluationSuccess.value = '수동 평가 기록을 서버에 저장했습니다. 자동 평가, 학습과 배포는 실행하지 않았습니다.'
        notice.value = evaluationSuccess.value
        await Promise.all([refreshModels(), refreshDetail(id)])
    }
    if (!disposed) evaluationBusy.value = false
}

onMounted(refreshModels)
onBeforeUnmount(() => {
    disposed = true
    registryGeneration += 1
    detailGeneration += 1
})
</script>

<template>
    <div class="min-w-0">
        <ViewHeading title="AI 모델 관리" description="서버에 저장된 모델 버전과 평가 기록을 관리하고, 추론 서버의 응답 상태를 별도로 확인합니다.">
            <template #actions>
                <BaseButton variant="secondary" :loading="loading" :disabled="registerBusy || evaluationBusy" @click="refreshAfterOperation"
                    >새로고침</BaseButton
                >
                <BaseButton v-if="canTrain" variant="secondary" @click="selectTab('trainingJobs')">웹 학습 관리</BaseButton>
                <BaseButton v-if="canRegister" :disabled="!registryAvailable || loading || registerBusy" @click="openRegister">모델 버전 등록</BaseButton>
            </template>
        </ViewHeading>
        <p v-if="canRegister && !registryAvailable" class="mb-4 text-xs text-slate-500 dark:text-slate-400">
            모델 목록을 정상적으로 불러온 뒤 버전을 등록할 수 있습니다.
        </p>
        <div
            v-if="notice"
            role="status"
            class="mb-5 flex items-start justify-between gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800 dark:border-emerald-800 dark:bg-emerald-950/30 dark:text-emerald-200"
        >
            <p class="min-w-0 leading-6">{{ notice }}</p>
            <button
                type="button"
                class="shrink-0 rounded px-2 py-1 text-xs font-bold hover:bg-emerald-100 dark:hover:bg-emerald-900"
                aria-label="저장 안내 닫기"
                @click="notice = ''"
            >
                닫기
            </button>
        </div>
        <section
            class="overflow-hidden rounded-2xl border border-blue-200 bg-gradient-to-br from-[#071a38] via-[#123a76] to-[#1659b7] text-white shadow-lg shadow-blue-950/10 dark:border-blue-900"
        >
            <div class="grid lg:grid-cols-[1.2fr_0.8fr]">
                <div class="min-w-0 p-5 sm:p-6">
                    <div class="flex flex-wrap items-center gap-3">
                        <h2 class="text-sm font-bold text-blue-100">추론 서버 확인</h2>
                        <ModelStateBadge v-if="runtime" :status="runtime.status" />
                        <span v-else class="rounded-full border border-white/20 bg-white/10 px-3 py-1 text-xs font-bold">{{
                            loading ? '확인 중' : '미확인'
                        }}</span>
                    </div>
                    <p class="mt-4 text-xs text-blue-200">서버가 응답한 모델 식별자</p>
                    <h3 class="mt-2 break-all text-xl font-extrabold sm:text-2xl">{{ runtime?.model || '확인된 모델 없음' }}</h3>
                    <p class="mt-3 break-all text-sm leading-6 text-blue-100">
                        {{ runtimeError || runtime?.message || '관리 기록과 별도로 추론 서버 연결을 확인합니다.' }}
                    </p>
                    <p class="mt-4 text-xs text-blue-200">확인 시각 · {{ dateLabel(runtime?.checkedAt) }}</p>
                    <p class="mt-2 text-xs leading-6 text-blue-200">설치·응답 확인이며 메모리 적재와 실제 추론 완료를 보증하지 않습니다.</p>
                </div>
                <div class="border-t border-white/15 bg-white/5 p-5 sm:p-6 lg:border-l lg:border-t-0">
                    <h3 class="text-sm font-bold">모델 관리 순서</h3>
                    <ol class="mt-4 grid gap-3 text-sm text-blue-100">
                        <li class="flex items-center gap-3">
                            <span class="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-white/15 text-xs font-bold">1</span>학습 산출물과 파일 위치
                            확인
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-white/15 text-xs font-bold">2</span>모델 버전 등록
                        </li>
                        <li class="flex items-center gap-3">
                            <span class="grid h-6 w-6 shrink-0 place-items-center rounded-full bg-white/15 text-xs font-bold">3</span>별도로 수행한 평가 기록
                        </li>
                    </ol>
                    <p class="mt-4 text-xs leading-6 text-blue-200">등록과 평가는 관리 기록만 저장하며 파일 자동 수신이나 배포를 실행하지 않습니다.</p>
                </div>
            </div>
        </section>
        <ModelRuntimeControl :can-deploy="canDeploy" :can-rollback="canRollback" :refresh-key="operationRefreshKey" @changed="refreshAfterOperation" />
        <div class="mt-5 grid gap-3 sm:grid-cols-3">
            <section
                v-for="item in [
                    { label: '등록된 버전', value: versions.length, hint: '서버가 반환한 관리 기록' },
                    { label: '등록 모델', value: models.length, hint: 'DB에 등록한 모델 관리 정보' },
                    { label: '배포 기록', value: deployments.length, hint: '현재 실행 상태와 별도' },
                ]"
                :key="item.label"
                class="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900"
            >
                <p class="text-xs font-semibold text-slate-500 dark:text-slate-400">{{ item.label }}</p>
                <p class="mt-2 text-2xl font-extrabold text-slate-900 dark:text-slate-100">
                    {{ registry ? item.value : '—' }}<span v-if="registry" class="ml-1 text-sm font-semibold text-slate-400">개</span>
                </p>
                <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">{{ item.hint }}</p>
            </section>
        </div>
        <div
            v-if="registryError"
            role="alert"
            class="mt-5 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
            <p>{{ registryError }}</p>
            <p v-if="registry" class="mt-2 text-xs">아래에는 마지막으로 불러온 기록이 남아 있습니다. 최신 상태가 아니므로 등록은 잠시 사용할 수 없습니다.</p>
            <BaseButton class="mt-3" variant="secondary" :loading="loading" @click="refreshModels">다시 불러오기</BaseButton>
        </div>
        <section class="mt-6 min-w-0">
            <div class="flex flex-wrap gap-2 border-b border-slate-200 pb-4 dark:border-slate-700" aria-label="모델 관리 목록">
                <button
                    v-for="tab in tabs"
                    :key="tab.key"
                    type="button"
                    class="inline-flex items-center gap-2 rounded-lg px-4 py-2.5 text-sm font-bold"
                    :class="
                        activeTab === tab.key
                            ? 'bg-blue-600 text-white'
                            : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700'
                    "
                    :aria-pressed="activeTab === tab.key"
                    @click="selectTab(tab.key)"
                >
                    {{ tab.label
                    }}<span v-if="tab.count !== null" class="rounded-full bg-black/10 px-2 py-0.5 text-xs dark:bg-white/10">{{
                        registry ? tab.count : '—'
                    }}</span>
                </button>
            </div>
            <div v-if="['versions', 'deployments'].includes(activeTab)" class="my-4 grid items-end gap-3 sm:grid-cols-[minmax(0,1fr)_180px_auto]">
                <BaseInput v-model="search" label="목록 검색" type="search" placeholder="모델 이름, 버전, 식별자, 서버 검색" :disabled="!registry" />
                <BaseSelect v-model="statusFilter" label="상태" :options="statusOptions" :disabled="!registry" />
                <BaseButton variant="secondary" :disabled="!search && !statusFilter" @click="resetFilters">초기화</BaseButton>
            </div>
            <div
                v-if="['versions', 'deployments'].includes(activeTab)"
                class="mb-4 flex flex-wrap items-start justify-between gap-2 text-xs text-slate-500 dark:text-slate-400"
            >
                <p>{{ registry ? `불러온 ${currentRows.length}개 중 ${filteredRows.length}개 표시` : '서버의 관리 기록을 불러옵니다.' }}</p>
                <p v-if="truncated" class="text-amber-700 dark:text-amber-300">
                    최근 {{ registry.limit || 100 }}개만 불러왔습니다. 검색과 필터는 불러온 목록 안에서 적용됩니다.
                </p>
            </div>
            <ModelTrainingPanel
                v-show="activeTab === 'trainingJobs'"
                :visible="activeTab === 'trainingJobs'"
                :can-train="canTrain"
                :refresh-key="operationRefreshKey"
                @changed="refreshAfterOperation"
            />
            <ModelOperationsPanel v-if="activeTab === 'operations'" :refresh-key="operationRefreshKey" />
            <template v-else-if="activeTab === 'trainingJobs'"></template>
            <div
                v-else-if="!registry && registryError"
                class="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm leading-6 text-slate-500 dark:border-slate-700 dark:text-slate-400"
            >
                관리 기록을 불러오지 못해 목록을 표시할 수 없습니다. 위의 다시 불러오기로 연결 상태를 확인해 주세요.
            </div>
            <template v-else-if="activeTab === 'versions'">
                <p class="mb-4 text-xs leading-6 text-slate-500 dark:text-slate-400">
                    등록 상태와 수동 평가 기록은 파일 검증이나 배포 준비 완료를 뜻하지 않습니다. 평가하지 않은 점수는 미측정으로 표시합니다.
                </p>
                <div class="hidden md:block">
                    <BaseTable
                        :columns="versionColumns"
                        :rows="filteredRows"
                        :loading="loading && !registry"
                        empty-text="표시할 모델 버전이 없습니다."
                        row-key="id"
                    >
                        <template #cell-version="{ row }"
                            ><p class="break-all font-bold text-slate-900 dark:text-slate-100">{{ row.modelName }}</p>
                            <p class="mt-1 break-all text-xs text-slate-500 dark:text-slate-400">{{ row.version }}</p></template
                        >
                        <template #cell-baseModelRef="{ row }"
                            ><p class="break-all text-xs">{{ row.baseModelRef || '기록 없음' }}</p>
                            <span class="mt-1 inline-block text-xs text-slate-400">{{ artifactLabel(row.artifactType) }}</span></template
                        >
                        <template #cell-datasetName="{ value }"
                            ><span class="break-all text-xs">{{ value || '기록 없음' }}</span></template
                        >
                        <template #cell-latestEvaluationScore="{ row }"
                            ><p class="text-sm font-bold">{{ scoreLabel(row.latestEvaluationScore) }}</p>
                            <ModelStateBadge v-if="row.latestEvaluationStatus" class="mt-1" :status="row.latestEvaluationStatus"
                        /></template>
                        <template #cell-status="{ value }"><ModelStateBadge :status="value" /></template>
                        <template #cell-createdAt="{ value }"
                            ><span class="text-xs">{{ dateLabel(value) }}</span></template
                        >
                        <template #cell-actions="{ row }"
                            ><BaseButton size="small" variant="secondary" @click="openDetail(row.id)">상세 보기</BaseButton></template
                        >
                    </BaseTable>
                </div>
                <div class="grid gap-3 md:hidden">
                    <p v-if="loading && !registry" role="status" class="py-10 text-center text-sm text-slate-500">모델 버전을 불러오고 있습니다.</p>
                    <div
                        v-else-if="!filteredRows.length"
                        class="rounded-xl border border-dashed border-slate-300 p-8 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400"
                    >
                        {{ search || statusFilter ? '검색 조건에 맞는 모델 버전이 없습니다.' : '등록된 모델 버전이 없습니다.' }}
                    </div>
                    <article
                        v-for="row in filteredRows"
                        :key="row.id"
                        class="min-w-0 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900"
                    >
                        <div class="flex flex-wrap items-start justify-between gap-3">
                            <div class="min-w-0">
                                <p class="break-all text-sm font-extrabold text-slate-900 dark:text-slate-100">{{ row.modelName }}</p>
                                <p class="mt-1 break-all text-xs text-slate-500 dark:text-slate-400">{{ row.version }}</p>
                            </div>
                            <ModelStateBadge :status="row.status" />
                        </div>
                        <dl class="mt-4 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-2 text-xs">
                            <dt class="text-slate-500">기반 모델</dt>
                            <dd class="break-all">{{ row.baseModelRef || '기록 없음' }}</dd>
                            <dt class="text-slate-500">최근 평가</dt>
                            <dd>{{ scoreLabel(row.latestEvaluationScore) }}</dd>
                            <dt class="text-slate-500">등록 일시</dt>
                            <dd>{{ dateLabel(row.createdAt) }}</dd>
                        </dl>
                        <BaseButton class="mt-4" block variant="secondary" size="small" @click="openDetail(row.id)">상세 보기</BaseButton>
                    </article>
                </div>
            </template>
            <template v-else>
                <div
                    class="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200"
                >
                    <p class="font-bold">배포 이력은 현재 실행 모델과 다를 수 있습니다.</p>
                    <p class="mt-1">{{ executionReason }}</p>
                    <div class="mt-3 flex flex-wrap gap-2">
                        <BaseButton disabled size="small">등록 파일 직접 배포 불가</BaseButton>
                    </div>
                    <p class="mt-3 text-xs">
                        설치된 Ollama 모델의 전환과 복구는 상단의 설치 모델 영역에서 진행합니다. 등록한 LoRA 파일을 바로 적용하는 기능은 아닙니다.
                    </p>
                </div>
                <BaseTable
                    :columns="deploymentColumns"
                    :rows="filteredRows"
                    :loading="loading && !registry"
                    row-key="id"
                    :empty-text="
                        search || statusFilter
                            ? '검색 조건에 맞는 배포 기록이 없습니다.'
                            : '등록된 배포 기록이 없습니다. 실제 추론 서버 상태는 상단에서 별도로 확인해 주세요.'
                    "
                >
                    <template #cell-version="{ row }"
                        ><p class="break-all font-bold">{{ row.modelName }}</p>
                        <p class="mt-1 break-all text-xs text-slate-500 dark:text-slate-400">{{ row.version }}</p></template
                    >
                    <template #cell-serverName="{ value }"
                        ><span class="break-all text-xs">{{ value || '기록 없음' }}</span></template
                    >
                    <template #cell-deploymentRole="{ value }"
                        ><span class="break-all text-xs">{{ value || '기록 없음' }}</span></template
                    >
                    <template #cell-status="{ value }"><ModelStateBadge :status="value" /></template>
                    <template #cell-deployedAt="{ value }"
                        ><span class="text-xs">{{ dateLabel(value) }}</span></template
                    >
                    <template #cell-actions="{ row }"
                        ><BaseButton size="small" variant="secondary" @click="openDetail(row.modelVersionId)">버전 상세</BaseButton></template
                    >
                </BaseTable>
            </template>
        </section>
        <ModelVersionRegisterModal
            v-model="registerOpen"
            :models="models"
            :models-truncated="registry?.truncated?.models === true"
            :busy="registerBusy"
            :error="registerError"
            @submit="registerVersion"
        />
        <ModelVersionDetailModal
            :model-value="detailOpen"
            :version="detail"
            :loading="detailLoading"
            :error="detailError"
            :busy="evaluationBusy"
            :evaluation-error="evaluationError"
            :evaluation-success="evaluationSuccess"
            :can-evaluate="canEvaluate"
            :can-deploy="canDeploy"
            :can-rollback="canRollback"
            @update:model-value="closeDetail"
            @reload="refreshDetail()"
            @submit-evaluation="saveEvaluation"
        />
    </div>
</template>
