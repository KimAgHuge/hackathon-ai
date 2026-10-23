<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
    getModelTraining,
    getTrainedModelBuild,
    startTrainedModelBuild,
    cancelTrainedModelBuild,
    importTrainedModel,
    getTrainedModelImport,
} from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import ModelRuntimeControl from '@/components/models/ModelRuntimeControl.vue'
import { formatOperationDate, operationErrorMessage } from '@/lib/modelOperations'

const props = defineProps({
    modelName: { type: String, default: '' },
    visible: Boolean,
    available: Boolean,
    canTrain: Boolean,
    canDeploy: Boolean,
    snapshot: { type: Object, default: null },
    reload: { type: Function, required: true },
    refreshKey: { type: Number, default: 0 },
})
const emit = defineEmits(['changed', 'locked', 'busy'])
const catalog = ref(null)
const selectedJobId = ref('')
const build = ref(null)
const imported = ref(null)
const loading = ref(false)
const request = ref('')
const error = ref('')
const confirmation = ref('')
const confirmed = ref(false)
const runtimeLocked = ref(false)
const runtimeBusy = ref(false)
const importUncertain = ref(false)
const importCheckedAt = ref('')
const checkingStatus = ref(false)
const syncingInstalled = ref(false)
const importSyncError = ref('')
const buildingStates = new Set(['PENDING', 'FUSING', 'CONVERTING', 'QUANTIZING', 'PUBLISHING'])
const labels = {
    NOT_BUILT: '생성 전',
    PENDING: '접수 대기',
    FUSING: '학습 결과 병합 중',
    CONVERTING: '모델 형식 변환 중',
    QUANTIZING: '실행용 가중치 준비 중',
    PUBLISHING: '완성 모델 전달 중',
    SUCCEEDED: '새 모델 생성 완료',
    FAILED: '생성 실패',
    CANCELLED: '생성 취소',
    IMPORTING: '분석 서버 등록 중',
    READY: '분석 서버 등록 완료',
    WAITING: '파일 동기화 대기',
    UNAVAILABLE: '준비 확인 필요',
    UNKNOWN: '결과 확인 필요',
    NOT_STARTED: '등록 전',
}
const importPhases = [
    {
        value: 'DOWNLOADING',
        label: '모델 파일 다운로드',
        title: '모델 파일 준비 대기',
        message: '공유 저장소의 모델 파일이 AI 분석/추론 서버에 준비되기를 기다립니다.',
    },
    { value: 'VERIFYING', label: '파일 검증', title: '모델 파일 검증 중', message: '전달된 모델 파일의 식별값과 구성을 확인하고 있습니다.' },
    {
        value: 'COPYING',
        label: '로컬 보관',
        title: '분석 서버에 모델 파일 보관 중',
        message: '검증한 모델 파일을 AI 분석/추론 서버의 보관 위치로 복사하고 있습니다.',
    },
    {
        value: 'REGISTERING',
        label: '실행 엔진 등록',
        title: '모델 실행 엔진에 등록 중',
        message: 'AI 분석/추론 서버의 모델 실행 엔진에 새 모델을 등록하고 있습니다.',
    },
]
let generation = 0
let statusGeneration = 0
let installedGeneration = 0
let timer = null
let disposed = false
let refreshedDigest = ''
let notifiedDigest = ''
const jobSelectedModel = (job) => job?.target?.selectedModel || job?.selectedModel || job?.target?.sourceModel || job?.sourceModel
const jobSourceModel = (job) => job?.target?.sourceModel || job?.sourceModel
const jobs = computed(() =>
    (Array.isArray(catalog.value?.jobs) ? catalog.value.jobs : []).filter((job) => job?.status === 'SUCCEEDED' && jobSelectedModel(job) === props.modelName)
)
const selectedJob = computed(() => jobs.value.find((job) => job.id === selectedJobId.value))
const building = computed(() => buildingStates.has(build.value?.status))
const registering = computed(() => imported.value?.status === 'IMPORTING')
const importPhase = computed(() => registering.value && importPhases.find((phase) => phase.value === imported.value?.phase))
const importStatusVisible = computed(() => request.value === 'import' || importUncertain.value || (imported.value && imported.value.status !== 'NOT_STARTED'))
const importStatusTitle = computed(() => {
    if (request.value === 'import') return '등록 요청을 보내고 있습니다'
    if (importUncertain.value) return '등록 요청 결과 확인 필요'
    if (registering.value && error.value) return '등록 상태 확인이 중단되었습니다'
    if (importPhase.value) return importPhase.value.title
    if (imported.value?.status === 'FAILED') return '분석 서버 등록 실패'
    if (imported.value?.status === 'UNAVAILABLE') return '모델 등록을 완료하지 못했습니다'
    if (imported.value?.status === 'WAITING') return '등록할 모델 파일 준비 대기'
    if (imported.value?.status === 'UNKNOWN') return '등록 결과 확인 필요'
    return labels[imported.value?.status] || '등록 상태 확인 필요'
})
const importStatusMessage = computed(() => {
    if (request.value === 'import') return '서버의 접수 응답을 기다리고 있습니다. 응답을 받으면 진행 상태를 자동으로 확인합니다.'
    if (importUncertain.value) return '요청의 응답을 확인하지 못했습니다. 서버가 접수했을 수 있으므로 등록 상태를 먼저 다시 확인해 주세요.'
    return imported.value?.message || importPhase.value?.message || '등록 상태를 확인해 주세요.'
})
const locked = computed(() =>
    Boolean(request.value || confirmation.value || building.value || registering.value || runtimeLocked.value || syncingInstalled.value)
)
const canBuild = computed(
    () =>
        props.canTrain &&
        props.available &&
        selectedJob.value &&
        build.value?.available === true &&
        !building.value &&
        build.value?.status !== 'SUCCEEDED' &&
        !request.value &&
        !runtimeLocked.value &&
        !registering.value &&
        !error.value &&
        !loading.value
)
const canImport = computed(
    () =>
        props.canDeploy &&
        props.available &&
        build.value?.status === 'SUCCEEDED' &&
        !request.value &&
        !runtimeLocked.value &&
        !registering.value &&
        imported.value?.status !== 'READY' &&
        imported.value?.status !== 'UNKNOWN' &&
        !importUncertain.value &&
        !error.value &&
        !loading.value
)
const synchronized = computed(
    () =>
        imported.value?.status === 'READY' &&
        props.snapshot?.models?.some(
            (model) => model.name === imported.value.model && model.digest === imported.value.digest && model.selectable && model.trainedModel === true
        )
)
const options = computed(() => [
    { value: '', label: '새 모델을 만들 학습 결과를 선택하세요' },
    ...jobs.value.map((job) => ({
        value: job.id,
        label: `${formatOperationDate(job.updatedAt || job.createdAt)} · ${job.iterations ?? '미기록'}회 · ${job.id}`,
    })),
])
const progress = computed(() =>
    typeof build.value?.progress === 'number' && build.value.progress >= 0 && build.value.progress <= 100 ? build.value.progress : null
)
const stopPolling = () => {
    clearTimeout(timer)
    timer = null
}
const current = (value, id) => !disposed && value === generation && id === selectedJobId.value && props.visible && !document.hidden
watch(locked, (value) => emit('locked', value))
watch(
    () => Boolean(request.value || runtimeBusy.value || syncingInstalled.value),
    (value) => emit('busy', value)
)
const schedule = () => {
    stopPolling()
    if (!disposed && props.visible && !document.hidden && !error.value && (building.value || registering.value)) timer = setTimeout(refreshStatus, 5000)
}
const refreshInstalled = async (value = imported.value) => {
    if (value?.status !== 'READY' || syncingInstalled.value) return
    const token = generation,
        id = selectedJobId.value,
        installedToken = ++installedGeneration
    syncingInstalled.value = true
    try {
        await props.reload()
        if (!current(token, id)) return
        refreshedDigest = value.digest
        importSyncError.value = ''
    } catch (failure) {
        if (current(token, id))
            importSyncError.value = operationErrorMessage(failure, '새 모델 등록은 완료했지만 설치 목록을 갱신하지 못했습니다. 설치 목록을 다시 확인해 주세요.')
    } finally {
        if (!disposed && installedToken === installedGeneration) syncingInstalled.value = false
    }
}
const acceptImport = async (value) => {
    imported.value = value
    importUncertain.value = false
    importCheckedAt.value = new Date().toISOString()
    if (value.status === 'READY' && value.digest !== notifiedDigest) {
        notifiedDigest = value.digest
        emit('changed')
    }
    if (value.status === 'READY' && value.digest !== refreshedDigest) await refreshInstalled(value)
}
const refreshStatus = async () => {
    stopPolling()
    const id = selectedJobId.value,
        token = generation,
        statusToken = ++statusGeneration
    if (!id || !props.visible || document.hidden || request.value) return
    checkingStatus.value = true
    const results = await Promise.allSettled([getTrainedModelBuild(id), getTrainedModelImport(id)])
    if (!current(token, id) || statusToken !== statusGeneration) return
    checkingStatus.value = false
    const [built, registered] = results
    if (built.status === 'fulfilled' && built.value.jobId === id && built.value.sourceModel === jobSourceModel(selectedJob.value)) build.value = built.value
    else {
        error.value =
            built.status === 'rejected'
                ? operationErrorMessage(built.reason, '새 모델 생성 상태를 확인하지 못했습니다.')
                : '학습 대상과 새 모델 식별값이 다릅니다.'
        return
    }
    if (registered.status === 'fulfilled' && registered.value.jobId === id && registered.value.sourceModel === jobSourceModel(selectedJob.value))
        await acceptImport(registered.value)
    else {
        error.value =
            registered.status === 'rejected'
                ? operationErrorMessage(registered.reason, '분석 서버 등록 상태를 확인하지 못했습니다.')
                : '등록할 모델의 식별값이 다릅니다.'
        return
    }
    if (!current(token, id) || statusToken !== statusGeneration) return
    error.value = ''
    schedule()
}
const refresh = async () => {
    stopPolling()
    if (!props.visible || !props.modelName || document.hidden) return
    const token = generation
    loading.value = true
    error.value = ''
    try {
        const result = await getModelTraining(props.modelName)
        if (disposed || token !== generation || !props.visible) return
        if (result.requestedModel && result.requestedModel !== props.modelName) {
            catalog.value = null
            selectedJobId.value = ''
            build.value = null
            imported.value = null
            error.value = '선택한 모델과 서버가 확인한 학습 대상이 다릅니다.'
            return
        }
        catalog.value = result
        if (!jobs.value.some((job) => job.id === selectedJobId.value)) selectedJobId.value = ''
        if (selectedJobId.value) await refreshStatus()
    } catch (failure) {
        if (!disposed && token === generation)
            error.value = operationErrorMessage(
                failure,
                failure.message === 'MODEL_TARGET_MISMATCH' ? '선택한 모델과 서버가 확인한 학습 대상이 다릅니다.' : '학습 결과를 불러오지 못했습니다.'
            )
    } finally {
        if (!disposed && token === generation) loading.value = false
    }
}
const openConfirmation = (mode) => {
    if (request.value || confirmation.value || runtimeLocked.value || registering.value || loading.value) return
    if ((mode === 'build' && !canBuild.value) || (mode === 'import' && !canImport.value) || (mode === 'cancel' && (!props.canTrain || !building.value))) return
    confirmed.value = false
    confirmation.value = mode
}
const execute = async () => {
    if (!confirmed.value || request.value || !selectedJob.value) return
    const mode = confirmation.value,
        id = selectedJobId.value,
        model = props.modelName,
        token = generation
    if ((mode === 'build' && !canBuild.value) || (mode === 'import' && !canImport.value) || (mode === 'cancel' && (!props.canTrain || !building.value))) return
    request.value = mode
    statusGeneration += 1
    checkingStatus.value = false
    if (mode === 'import') importUncertain.value = true
    stopPolling()
    error.value = ''
    try {
        const result =
            mode === 'build' ? await startTrainedModelBuild(id) : mode === 'cancel' ? await cancelTrainedModelBuild(id) : await importTrainedModel(id)
        if (!current(token, id)) return
        if (result.jobId !== id || result.sourceModel !== jobSourceModel(selectedJob.value)) throw new Error('MODEL_TARGET_MISMATCH')
        if (mode === 'import') await acceptImport(result)
        else build.value = result
        if (!current(token, id)) return
        confirmation.value = ''
        if (result.status === 'UNKNOWN') error.value = '처리 결과를 확인하지 못했습니다. 상태를 먼저 새로고침한 뒤 같은 학습 결과에서 다시 요청하세요.'
    } catch (failure) {
        if (!disposed && token === generation) {
            if (mode === 'import') {
                importUncertain.value = true
                confirmation.value = ''
            }
            error.value = operationErrorMessage(failure, '요청 결과를 확인하지 못했습니다. 자동으로 다시 실행하지 않습니다. 상태를 먼저 새로고침해 주세요.')
        }
    } finally {
        if (!disposed) {
            request.value = ''
            if (
                mode === 'import' &&
                token !== generation &&
                id === selectedJobId.value &&
                model === props.modelName &&
                props.visible &&
                !document.hidden &&
                !error.value
            ) {
                confirmation.value = ''
                void refreshStatus()
            } else schedule()
        }
    }
}
watch(selectedJobId, () => {
    generation += 1
    statusGeneration += 1
    checkingStatus.value = false
    build.value = null
    imported.value = null
    refreshedDigest = ''
    notifiedDigest = ''
    importCheckedAt.value = ''
    importUncertain.value = false
    importSyncError.value = ''
    installedGeneration += 1
    syncingInstalled.value = false
    confirmed.value = false
    if (!selectedJobId.value) return
    if (!selectedJob.value) {
        if (!error.value) error.value = '목록에서 선택한 모델의 완료한 학습 결과를 선택해 주세요.'
        return
    }
    error.value = ''
    refreshStatus()
})
watch(
    () => props.modelName,
    () => {
        generation += 1
        catalog.value = null
        selectedJobId.value = ''
        build.value = null
        imported.value = null
        error.value = ''
        refresh()
    }
)
watch(
    () => props.visible,
    (visible) => {
        if (visible) refresh()
        else {
            generation += 1
            stopPolling()
        }
    },
    { immediate: true }
)
watch(
    () => props.refreshKey,
    () => {
        if (!request.value) refresh()
    }
)
const onVisibility = () => {
    if (document.hidden) {
        generation += 1
        stopPolling()
    } else if (!error.value) refresh()
}
onMounted(() => document.addEventListener('visibilitychange', onVisibility))
onBeforeUnmount(() => {
    disposed = true
    generation += 1
    stopPolling()
    document.removeEventListener('visibilitychange', onVisibility)
    emit('locked', false)
    emit('busy', false)
})
</script>

<template>
    <section class="grid gap-5">
        <div class="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <div class="flex flex-wrap items-start justify-between gap-4">
                <div class="min-w-0 flex-1">
                    <h3 class="text-base font-extrabold">학습 결과로 새 모델 만들기</h3>
                    <p class="mt-2 text-xs leading-6 text-slate-500 dark:text-slate-400">
                        {{ modelName }}의 완료한 학습을 기반 모델과 합쳐 새 분석용 모델 버전을 만듭니다.
                    </p>
                </div>
                <BaseButton variant="secondary" size="small" :loading="loading" :disabled="Boolean(request) || runtimeLocked" @click="refresh"
                    >준비 상태 새로고침</BaseButton
                >
            </div>
            <ol class="mt-5 grid gap-2 text-center text-xs font-bold text-slate-600 dark:text-slate-300 sm:grid-cols-4" aria-label="새 학습 모델 적용 순서">
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">1. 학습 결과 선택</li>
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">2. 새 모델 생성</li>
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">3. 분석 서버 등록</li>
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">4. 검증과 적용</li>
            </ol>
            <BaseSelect wrapper-class="mt-5" v-model="selectedJobId" label="완료한 학습 결과" :options="options" :disabled="!available || loading || locked" />
            <p v-if="catalog && !jobs.length" class="mt-4 text-sm leading-6 text-slate-500">
                이 모델의 완료한 학습이 없습니다. 실행 작업 이력에서 학습 상태를 확인해 주세요.
            </p>
            <p v-if="selectedJob" class="mt-4 break-all text-xs leading-6 text-slate-500 dark:text-slate-400">
                선택한 학습 모델 · {{ jobSelectedModel(selectedJob) }}<br />
                승인 자료 출처 모델 · {{ jobSourceModel(selectedJob) || '미기록' }}<br />
                학습 기반 모델 · {{ selectedJob.target?.baseModel || '미기록' }}<br />기반 모델 버전 · {{ selectedJob.target?.revision || '미기록' }}
            </p>
            <div v-if="build" class="mt-5 rounded-xl border border-slate-200 p-4 dark:border-slate-700" role="status">
                <p class="text-sm font-bold">
                    {{ labels[build.status] || '상태 확인 필요' }}<span v-if="progress !== null"> · {{ progress }}%</span>
                </p>
                <p class="mt-2 text-sm leading-7">{{ build.message }}</p>
                <div
                    v-if="progress !== null && building"
                    role="progressbar"
                    :aria-valuenow="progress"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    aria-label="새 모델 생성 진행률"
                    class="mt-4 h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800"
                >
                    <div class="h-full bg-blue-600" :style="{ width: `${progress}%` }"></div>
                </div>
                <p v-if="build.errorCode || build.reasonCode" class="mt-3 break-all text-xs text-amber-700 dark:text-amber-300">
                    준비 코드 · {{ build.errorCode || build.reasonCode }}
                </p>
            </div>
            <div class="mt-5 flex flex-wrap gap-3">
                <BaseButton :disabled="!canBuild" :loading="request === 'build'" @click="openConfirmation('build')">새 학습 모델 생성</BaseButton>
                <BaseButton v-if="building && canTrain" variant="secondary" :disabled="Boolean(request)" @click="openConfirmation('cancel')"
                    >모델 생성 취소</BaseButton
                >
                <BaseButton :disabled="!canImport" :loading="request === 'import'" variant="secondary" @click="openConfirmation('import')">
                    {{ request === 'import' ? '등록 요청 확인 중' : '분석 서버에 새 모델 등록' }}
                </BaseButton>
            </div>
            <p class="mt-4 text-xs leading-6 text-slate-500 dark:text-slate-400">
                병합과 변환은 학습 서버에서 실행합니다. 분석 서버는 기존 모델 실행 환경으로 새 모델을 사용합니다.
            </p>
            <p v-if="!canTrain || !canDeploy" class="mt-2 text-xs leading-6 text-slate-500">
                모델 생성·취소에는 MODEL_TRAIN, 분석 서버 등록·적용에는 MODEL_DEPLOY 권한이 필요합니다.
            </p>
            <div
                v-if="importStatusVisible"
                class="mt-5 rounded-xl p-4 text-sm leading-7"
                :class="
                    imported?.status === 'READY'
                        ? 'bg-emerald-50 dark:bg-emerald-950/40'
                        : error || importUncertain || ['UNAVAILABLE', 'WAITING', 'FAILED', 'UNKNOWN'].includes(imported?.status)
                          ? 'bg-amber-50 dark:bg-amber-950/40'
                          : 'bg-blue-50 dark:bg-blue-950/40'
                "
                role="status"
                aria-live="polite"
                :aria-busy="request === 'import' || (registering && !error)"
            >
                <div class="flex items-center gap-3">
                    <span
                        v-if="request === 'import' || (registering && !error)"
                        class="h-4 w-4 shrink-0 animate-spin rounded-full border-2 border-blue-600 border-r-transparent"
                        aria-hidden="true"
                    ></span>
                    <p class="font-bold">{{ importStatusTitle }}</p>
                </div>
                <p class="mt-2">{{ importStatusMessage }}</p>
                <template v-if="registering && request !== 'import'">
                    <p v-if="error" class="mt-3 font-bold text-amber-800 dark:text-amber-200">
                        아래 내용은 마지막으로 확인한 상태입니다. 서버에서 계속 처리 중일 수 있습니다.
                    </p>
                    <ol v-if="importPhase" class="mt-4 grid gap-2 text-xs sm:grid-cols-2 lg:grid-cols-4" aria-label="분석 서버 등록 단계">
                        <li
                            v-for="phase in importPhases"
                            :key="phase.value"
                            class="rounded-lg border px-3 py-2.5"
                            :class="
                                phase.value === importPhase.value
                                    ? 'border-blue-500 bg-white font-bold text-blue-800 dark:border-blue-400 dark:bg-blue-900/50 dark:text-blue-100'
                                    : 'border-blue-100 text-slate-500 dark:border-blue-900 dark:text-slate-400'
                            "
                            :aria-current="phase.value === importPhase.value ? 'step' : undefined"
                        >
                            <span>{{ phase.label }}</span>
                            <span v-if="phase.value === importPhase.value" class="mt-1 block">{{ error ? '마지막 확인 단계' : '현재 단계' }}</span>
                        </li>
                    </ol>
                    <p v-else class="mt-3 text-xs text-slate-600 dark:text-slate-300">
                        서버가 세부 진행 단계를 제공하지 않아 현재 작업 위치는 확인할 수 없습니다.
                    </p>
                </template>
                <p v-if="imported?.model" class="mt-3 break-all text-xs">새 모델 · {{ imported.model }}</p>
                <p v-if="imported?.errorCode" class="mt-2 break-all text-xs text-amber-700 dark:text-amber-300">오류 코드 · {{ imported.errorCode }}</p>
                <p v-if="importCheckedAt" class="mt-3 text-xs text-slate-600 dark:text-slate-300">
                    마지막 상태 확인 · <time :datetime="importCheckedAt">{{ formatOperationDate(importCheckedAt) }}</time>
                </p>
                <p v-if="request !== 'import' && registering && !error" class="mt-2 text-xs text-slate-600 dark:text-slate-300">
                    {{ checkingStatus || loading ? '최신 상태를 조회하고 있습니다.' : '화면이 열려 있는 동안 5초마다 상태를 확인합니다.' }}
                </p>
                <p v-if="imported?.status === 'READY'" class="mt-3 text-xs font-bold">
                    등록이 완료되었습니다. 아래에서 새 모델의 응답을 검증한 뒤 적용해 주세요.
                </p>
                <p v-if="['UNAVAILABLE', 'WAITING', 'FAILED'].includes(imported?.status)" class="mt-3 text-xs">
                    안내된 원인을 확인하고 준비 상태를 새로고침한 뒤 등록을 다시 요청해 주세요.
                </p>
                <p v-if="imported?.status === 'UNKNOWN'" class="mt-3 text-xs">
                    서버가 요청을 접수했을 수 있습니다. 등록을 다시 요청하기 전에 최신 상태를 확인해 주세요.
                </p>
                <BaseButton
                    v-if="imported?.status === 'UNKNOWN' || (importUncertain && request !== 'import') || (error && registering)"
                    class="mt-4"
                    variant="secondary"
                    size="small"
                    :loading="loading"
                    :disabled="Boolean(request)"
                    @click="refresh"
                    >등록 상태 다시 확인</BaseButton
                >
            </div>
            <p v-if="imported?.status === 'READY' && !synchronized" class="mt-4 text-xs leading-6 text-amber-700 dark:text-amber-300">
                설치 목록에서 등록한 모델과 파일 식별값을 확인하지 못했습니다. 설치 목록을 새로고침해 주세요.
            </p>
            <p
                v-if="imported?.status === 'READY' && imported.historySyncRequired"
                role="alert"
                class="mt-4 rounded-lg bg-amber-50 p-3 text-sm leading-6 text-amber-800 dark:bg-amber-950/40 dark:text-amber-200"
            >
                모델 등록은 완료했지만 버전 기록을 저장하지 못했습니다. 모델 관리 기록의 기록 동기화로 보완해 주세요. 모델을 다시 등록할 필요는 없습니다.
            </p>
            <p
                v-if="importSyncError"
                role="alert"
                class="mt-4 rounded-lg bg-amber-50 p-3 text-sm leading-6 text-amber-800 dark:bg-amber-950/40 dark:text-amber-200"
            >
                {{ importSyncError }}
            </p>
            <BaseButton
                v-if="imported?.status === 'READY' && (!synchronized || importSyncError)"
                class="mt-4"
                variant="secondary"
                size="small"
                :loading="syncingInstalled"
                :disabled="Boolean(request)"
                @click="refreshInstalled()"
                >설치 목록 다시 확인</BaseButton
            >
            <p v-if="error" role="alert" class="mt-4 rounded-lg bg-rose-50 p-3 text-sm leading-6 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
                {{ error }}
            </p>
        </div>
        <ModelRuntimeControl
            v-if="imported?.status === 'READY'"
            trained-mode
            :key="imported.model"
            :snapshot="snapshot"
            :model-name="imported.model"
            :available="available && synchronized && !error && !loading"
            :can-deploy="canDeploy"
            :reload="reload"
            @changed="emit('changed')"
            @locked="runtimeLocked = $event"
            @busy="runtimeBusy = $event"
        />
        <BaseModal
            :model-value="Boolean(confirmation)"
            :title="confirmation === 'build' ? '새 학습 모델 생성 확인' : confirmation === 'cancel' ? '모델 생성 취소 확인' : '분석 서버 등록 확인'"
            :busy="Boolean(request)"
            footer-text="새 모델 생성과 등록만으로 운영 모델은 바뀌지 않습니다. 응답 검증 후 적용을 확인합니다."
            @update:model-value="
                (open) => {
                    if (!open && !request) confirmation = ''
                }
            "
        >
            <div class="grid gap-4 text-sm leading-7">
                <p class="break-all font-bold">{{ modelName }} · {{ selectedJobId }}</p>
                <p v-if="confirmation === 'build'">
                    학습 서버에서 완료한 학습 결과를 기반 모델과 합치고 분석용 새 모델을 만듭니다. GPU·메모리·저장 공간과 시간이 필요하며, 완성 파일을 공유
                    NAS에 게시합니다. 기존 모델과 학습 원본은 보존합니다.
                </p>
                <p v-else-if="confirmation === 'cancel'">모델 생성 작업을 안전한 중단 지점에서 취소합니다. 이미 완료한 학습 결과는 유지합니다.</p>
                <p v-else>
                    공유 NAS의 완성 모델을 분석 서버의 기존 모델 실행 환경에 새 이름으로 등록합니다. 모델 가중치와 검증 요약을 전달하며 학습 원문은 전달하지
                    않습니다.
                </p>
                <label class="flex items-start gap-3"
                    ><input v-model="confirmed" type="checkbox" class="mt-1.5 h-4 w-4 shrink-0" :disabled="Boolean(request)" /><span
                        >선택한 학습 결과와 실행 범위, 자원 사용을 확인했습니다.</span
                    ></label
                >
                <p
                    v-if="request === 'import'"
                    role="status"
                    aria-live="polite"
                    class="rounded-lg bg-blue-50 p-3 text-blue-800 dark:bg-blue-950/40 dark:text-blue-200"
                >
                    등록 요청을 보내고 있습니다. 서버의 접수 응답을 기다린 뒤 다운로드와 파일 검증, 실행 엔진 등록 상태를 자동으로 표시합니다.
                </p>
                <p v-if="error" role="alert" class="text-rose-700 dark:text-rose-300">{{ error }}</p>
                <BaseButton :disabled="!confirmed" :loading="Boolean(request)" @click="execute">{{
                    request === 'import'
                        ? '등록 요청 확인 중'
                        : confirmation === 'build'
                          ? '확인 후 새 모델 생성'
                          : confirmation === 'cancel'
                            ? '확인 후 모델 생성 취소'
                            : '확인 후 새 모델 등록'
                }}</BaseButton>
            </div>
        </BaseModal>
    </section>
</template>
