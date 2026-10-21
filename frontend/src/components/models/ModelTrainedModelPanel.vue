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
let generation = 0
let timer = null
let disposed = false
let refreshedDigest = ''
const jobs = computed(() =>
    (Array.isArray(catalog.value?.jobs) ? catalog.value.jobs : []).filter(
        (job) => job?.status === 'SUCCEEDED' && (job.target?.sourceModel || job.sourceModel) === props.modelName
    )
)
const selectedJob = computed(() => jobs.value.find((job) => job.id === selectedJobId.value))
const building = computed(() => buildingStates.has(build.value?.status))
const registering = computed(() => imported.value?.status === 'IMPORTING')
const locked = computed(() => Boolean(request.value || confirmation.value || building.value || registering.value || runtimeLocked.value))
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
    () => Boolean(request.value || runtimeBusy.value),
    (value) => emit('busy', value)
)
const schedule = () => {
    stopPolling()
    if (!disposed && props.visible && !document.hidden && !error.value && (building.value || registering.value)) timer = setTimeout(refreshStatus, 5000)
}
const acceptImport = async (value) => {
    imported.value = value
    if (value.status === 'READY' && value.digest !== refreshedDigest) {
        refreshedDigest = value.digest
        await props.reload()
        emit('changed')
    }
}
const refreshStatus = async () => {
    stopPolling()
    const id = selectedJobId.value,
        token = generation
    if (!id || !props.visible || document.hidden || request.value) return
    const results = await Promise.allSettled([getTrainedModelBuild(id), getTrainedModelImport(id)])
    if (!current(token, id)) return
    const [built, registered] = results
    if (built.status === 'fulfilled' && built.value.jobId === id && built.value.sourceModel === props.modelName) build.value = built.value
    else {
        error.value =
            built.status === 'rejected'
                ? operationErrorMessage(built.reason, '새 모델 생성 상태를 확인하지 못했습니다.')
                : '학습 대상과 새 모델 식별값이 다릅니다.'
        return
    }
    if (registered.status === 'fulfilled' && registered.value.jobId === id && registered.value.sourceModel === props.modelName)
        await acceptImport(registered.value)
    else {
        error.value =
            registered.status === 'rejected'
                ? operationErrorMessage(registered.reason, '분석 서버 등록 상태를 확인하지 못했습니다.')
                : '등록할 모델의 식별값이 다릅니다.'
        return
    }
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
        token = generation
    if ((mode === 'build' && !canBuild.value) || (mode === 'import' && !canImport.value) || (mode === 'cancel' && (!props.canTrain || !building.value))) return
    request.value = mode
    stopPolling()
    error.value = ''
    try {
        const result =
            mode === 'build' ? await startTrainedModelBuild(id) : mode === 'cancel' ? await cancelTrainedModelBuild(id) : await importTrainedModel(id)
        if (!current(token, id)) return
        if (result.jobId !== id || result.sourceModel !== props.modelName) throw new Error('MODEL_TARGET_MISMATCH')
        if (mode === 'import') await acceptImport(result)
        else build.value = result
        confirmation.value = ''
        if (result.status === 'UNKNOWN') error.value = '처리 결과를 확인하지 못했습니다. 상태를 먼저 새로고침한 뒤 같은 학습 결과에서 다시 요청하세요.'
    } catch (failure) {
        if (!disposed && token === generation)
            error.value = operationErrorMessage(failure, '요청 결과를 확인하지 못했습니다. 자동으로 다시 실행하지 않습니다. 상태를 먼저 새로고침해 주세요.')
    } finally {
        if (!disposed) {
            request.value = ''
            schedule()
        }
    }
}
watch(selectedJobId, () => {
    generation += 1
    build.value = null
    imported.value = null
    refreshedDigest = ''
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
                <BaseButton :disabled="!canImport" :loading="request === 'import'" variant="secondary" @click="openConfirmation('import')"
                    >분석 서버에 새 모델 등록</BaseButton
                >
            </div>
            <p class="mt-4 text-xs leading-6 text-slate-500 dark:text-slate-400">
                병합과 변환은 학습 서버에서 실행합니다. 분석 서버는 기존 모델 실행 환경으로 새 모델을 사용합니다.
            </p>
            <p v-if="!canTrain || !canDeploy" class="mt-2 text-xs leading-6 text-slate-500">
                모델 생성·취소에는 MODEL_TRAIN, 분석 서버 등록·적용에는 MODEL_DEPLOY 권한이 필요합니다.
            </p>
            <div
                v-if="imported && imported.status !== 'NOT_STARTED'"
                class="mt-5 rounded-xl bg-blue-50 p-4 text-sm leading-7 dark:bg-blue-950/40"
                role="status"
            >
                <p class="font-bold">{{ labels[imported.status] || '등록 상태 확인 필요' }}</p>
                <p class="mt-2">{{ imported.message }}</p>
                <p class="mt-2 break-all text-xs">새 모델 · {{ imported.model }}</p>
                <p v-if="imported.errorCode" class="mt-2 break-all text-xs text-amber-700 dark:text-amber-300">오류 코드 · {{ imported.errorCode }}</p>
            </div>
            <p v-if="imported?.status === 'READY' && !synchronized" class="mt-4 text-xs leading-6 text-amber-700 dark:text-amber-300">
                설치 목록에서 등록한 모델과 파일 식별값을 확인하지 못했습니다. 설치 목록을 새로고침해 주세요.
            </p>
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
                <p v-if="error" role="alert" class="text-rose-700 dark:text-rose-300">{{ error }}</p>
                <BaseButton :disabled="!confirmed" :loading="Boolean(request)" @click="execute">{{
                    confirmation === 'build' ? '확인 후 새 모델 생성' : confirmation === 'cancel' ? '확인 후 모델 생성 취소' : '확인 후 새 모델 등록'
                }}</BaseButton>
            </div>
        </BaseModal>
    </section>
</template>
