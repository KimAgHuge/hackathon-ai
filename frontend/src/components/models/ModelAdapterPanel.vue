<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getModelTraining, prepareModelAdapter } from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import ModelRuntimeControl from '@/components/models/ModelRuntimeControl.vue'
import { formatOperationDate, operationErrorMessage } from '@/lib/modelOperations'

const props = defineProps({
    modelName: { type: String, default: '' },
    visible: Boolean,
    available: Boolean,
    canDeploy: Boolean,
    snapshot: { type: Object, default: null },
    reload: { type: Function, required: true },
    refreshKey: { type: Number, default: 0 },
})
const emit = defineEmits(['changed', 'locked', 'busy'])
const state = ref(null)
const loading = ref(false)
const error = ref('')
const selectedJobId = ref('')
const prepared = ref(null)
const preparing = ref(false)
const confirmOpen = ref(false)
const confirmed = ref(false)
const runtimeLocked = ref(false)
const runtimeBusy = ref(false)
let generation = 0
let disposed = false
const jobs = computed(() =>
    (Array.isArray(state.value?.jobs) ? state.value.jobs : []).filter(
        (job) => job?.status === 'SUCCEEDED' && (job.target?.sourceModel || job.sourceModel) === props.modelName
    )
)
const selectedJob = computed(() => jobs.value.find((job) => job.id === selectedJobId.value))
const options = computed(() => [
    { value: '', label: '적용할 학습 결과를 선택하세요' },
    ...jobs.value.map((job) => ({
        value: job.id,
        label: `${formatOperationDate(job.updatedAt || job.createdAt)} · ${job.iterations ?? '미기록'}회 · ${job.id}`,
    })),
])
const synchronized = computed(
    () =>
        prepared.value?.status === 'READY' &&
        props.snapshot?.models?.some((model) => model.name === prepared.value.model && model.digest === prepared.value.digest && model.selectable)
)
watch(
    () => Boolean(preparing.value || confirmOpen.value || runtimeLocked.value),
    (value) => emit('locked', value)
)
watch(
    () => Boolean(preparing.value || runtimeBusy.value),
    (value) => emit('busy', value)
)
const refresh = async () => {
    if (!props.visible || !props.modelName) return
    const current = ++generation
    loading.value = true
    error.value = ''
    try {
        const result = await getModelTraining(props.modelName)
        if (disposed || current !== generation || !props.visible) return
        if (result.requestedModel && result.requestedModel !== props.modelName) {
            state.value = null
            selectedJobId.value = ''
            error.value = '선택한 모델과 서버가 확인한 학습 대상이 다릅니다. 최신 목록을 다시 확인해 주세요.'
            return
        }
        state.value = result
        if (!jobs.value.some((job) => job.id === selectedJobId.value)) selectedJobId.value = ''
    } catch (failure) {
        if (!disposed && current === generation) error.value = operationErrorMessage(failure, '완료한 학습 결과를 불러오지 못했습니다.')
    } finally {
        if (!disposed && current === generation) loading.value = false
    }
}
watch(
    () => props.visible,
    (visible) => {
        if (visible) refresh()
        else generation += 1
    },
    { immediate: true }
)
watch(() => props.refreshKey, refresh)
watch(
    () => props.modelName,
    () => {
        generation += 1
        state.value = null
        selectedJobId.value = ''
        prepared.value = null
        error.value = ''
        refresh()
    }
)
watch(selectedJobId, () => {
    prepared.value = null
    confirmed.value = false
})
const openPrepare = () => {
    if (!props.canDeploy || !props.available || !selectedJob.value || preparing.value || runtimeLocked.value || loading.value) return
    confirmed.value = false
    error.value = ''
    confirmOpen.value = true
}
const prepare = async () => {
    if (!props.canDeploy || !props.available || !selectedJob.value || !confirmed.value || preparing.value) return
    const jobId = selectedJobId.value
    preparing.value = true
    error.value = ''
    try {
        const result = await prepareModelAdapter(jobId)
        if (disposed || jobId !== selectedJobId.value) return
        prepared.value = result
        confirmOpen.value = false
        if (result.status === 'READY') await props.reload()
        emit('changed')
    } catch (failure) {
        if (!disposed)
            error.value = operationErrorMessage(failure, '전달 준비 결과를 확인하지 못했습니다. 같은 학습 결과의 전달 준비를 수동으로 다시 확인해 주세요.')
    } finally {
        if (!disposed) preparing.value = false
    }
}
onBeforeUnmount(() => {
    disposed = true
    generation += 1
    emit('locked', false)
    emit('busy', false)
})
</script>

<template>
    <section class="grid gap-5">
        <div class="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <div class="flex flex-wrap items-start justify-between gap-4">
                <div class="min-w-0 flex-1">
                    <h3 class="text-base font-extrabold">완료한 학습 결과 선택</h3>
                    <p class="mt-2 text-xs leading-6 text-slate-500 dark:text-slate-400">
                        {{ modelName || '모델을 먼저 선택하세요' }}에 맞는 완료한 학습만 표시합니다.
                    </p>
                </div>
                <BaseButton variant="secondary" size="small" :loading="loading" :disabled="preparing || runtimeLocked" @click="refresh"
                    >학습 결과 새로고침</BaseButton
                >
            </div>
            <ol class="mt-5 grid gap-2 text-center text-xs font-bold text-slate-600 dark:text-slate-300 sm:grid-cols-4" aria-label="학습 결과 적용 순서">
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">1. 학습 결과 선택</li>
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">2. 전달 준비</li>
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">3. 응답 검증</li>
                <li class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800">4. 적용 확인</li>
            </ol>
            <BaseSelect
                wrapper-class="mt-5"
                v-model="selectedJobId"
                label="완료한 학습 결과"
                :options="options"
                :disabled="!available || loading || preparing || runtimeLocked"
            />
            <p v-if="state && !jobs.length" class="mt-4 text-sm leading-6 text-slate-500 dark:text-slate-400">
                이 모델에 적용할 수 있는 완료한 학습이 없습니다. 실행 작업 이력에서 상태를 확인해 주세요.
            </p>
            <p v-if="selectedJob" class="mt-4 break-all text-xs leading-6 text-slate-500 dark:text-slate-400">
                학습 기반 모델 · {{ selectedJob.target?.baseModel || '미기록' }}<br />기반 모델 버전 · {{ selectedJob.target?.revision || '미기록' }}
            </p>
            <BaseButton
                class="mt-5"
                variant="secondary"
                :disabled="!canDeploy || !selectedJob || !available || loading || preparing || runtimeLocked"
                :loading="preparing"
                @click="openPrepare"
                >{{ prepared ? '같은 어댑터 전달 준비 다시 확인' : '어댑터 전달 준비' }}</BaseButton
            >
            <p v-if="!canDeploy" class="mt-3 text-xs text-slate-500 dark:text-slate-400">전달 준비와 적용에는 MODEL_DEPLOY 권한이 필요합니다.</p>
            <div
                v-if="prepared"
                role="status"
                class="mt-5 rounded-xl p-4 text-sm leading-7"
                :class="
                    prepared.status === 'READY'
                        ? 'bg-blue-50 text-blue-900 dark:bg-blue-950/40 dark:text-blue-100'
                        : 'bg-amber-50 text-amber-900 dark:bg-amber-950/40 dark:text-amber-100'
                "
            >
                <p class="font-bold">{{ prepared.status === 'READY' ? '전달과 실행 준비 확인' : '전달 또는 실행 준비 확인 필요' }}</p>
                <p class="mt-2 break-all">{{ prepared.message || '서버의 준비 상태를 확인해 주세요.' }}</p>
                <p v-if="prepared.status === 'READY'" class="mt-2 text-xs">파일과 기반 모델 설정을 확인했습니다. 실제 추론은 아래 응답 검증으로 확인합니다.</p>
                <p v-if="prepared.errorCode" class="mt-2 break-all text-xs">오류 코드 · {{ prepared.errorCode }}</p>
            </div>
            <p v-if="prepared?.status === 'READY' && !synchronized" class="mt-3 text-xs leading-6 text-amber-700 dark:text-amber-300">
                설치 목록에서 준비한 어댑터를 확인하지 못했습니다. 설치 목록을 새로고침한 뒤 검증해 주세요.
            </p>
            <p v-if="error" role="alert" class="mt-4 rounded-lg bg-rose-50 p-3 text-sm leading-6 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
                {{ error }}
            </p>
        </div>
        <ModelRuntimeControl
            v-if="prepared?.status === 'READY'"
            adapter-mode
            :key="prepared.model"
            :snapshot="snapshot"
            :model-name="prepared.model"
            :available="available && synchronized"
            :can-deploy="canDeploy"
            :reload="reload"
            @changed="emit('changed')"
            @locked="runtimeLocked = $event"
            @busy="runtimeBusy = $event"
        />
        <BaseModal
            :model-value="confirmOpen"
            title="학습 어댑터 전달 준비 확인"
            :busy="preparing"
            footer-text="전달 준비만으로 운영 모델은 바뀌지 않습니다. 응답 검증과 적용 확인을 별도로 진행합니다."
            @update:model-value="confirmOpen = $event"
        >
            <div class="grid gap-4 text-sm leading-7">
                <p class="break-all font-bold">{{ modelName }} · {{ selectedJobId }}</p>
                <p>
                    학습 서버가 만든 어댑터의 가중치·설정과 숫자 요약을 공유 NAS에 게시하고 AI 분석 서버에서 서명·해시·기반 모델을 확인합니다. 학습 원문은
                    전달하지 않습니다.
                </p>
                <label class="flex items-start gap-3"
                    ><input v-model="confirmed" type="checkbox" class="mt-1.5 h-4 w-4 shrink-0" :disabled="preparing" /><span
                        >선택한 학습 결과를 NAS에 전달하고 준비 상태를 확인하는 데 동의합니다.</span
                    ></label
                >
                <p v-if="error" role="alert" class="text-rose-700 dark:text-rose-300">{{ error }}</p>
                <BaseButton :disabled="!confirmed" :loading="preparing" @click="prepare">확인 후 전달 준비</BaseButton>
            </div>
        </BaseModal>
    </section>
</template>
