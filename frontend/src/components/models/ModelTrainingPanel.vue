<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getModelTraining, startModelTraining, getModelOperation } from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import { activeTrainingStatuses, createTrainingPoller, operationErrorMessage, operationStatusLabel, uncertainRequestError } from '@/lib/modelOperations'

const props = defineProps({
    canTrain: Boolean,
    modelName: { type: String, default: '' },
    visible: { type: Boolean, default: true },
    refreshKey: { type: Number, default: 0 },
})
const emit = defineEmits(['changed', 'locked', 'busy'])
const state = ref(null)
const loading = ref(false)
const error = ref('')
const message = ref('')
const busy = ref(false)
const modal = ref('')
const iterations = ref('10')
const confirmed = ref(false)
const pending = ref(null)
const validation = ref('')
let disposed = false
const jobs = computed(() => (Array.isArray(state.value?.jobs) ? state.value.jobs : []))
const trainingTarget = computed(() => state.value?.dataset?.target)
const trainingSamples = computed(() => (Array.isArray(state.value?.dataset?.samples) ? state.value.dataset.samples : []))
const hasActive = computed(() => state.value?.busy === true || jobs.value.some((job) => activeTrainingStatuses.has(job.status)))
const canStart = computed(
    () =>
        props.canTrain &&
        trainingTarget.value?.sourceModel === props.modelName &&
        state.value?.capabilities?.trainingExecution === true &&
        Boolean(trainingTarget.value?.digest) &&
        !hasActive.value &&
        !pending.value &&
        !error.value &&
        !loading.value
)
const monitor = createTrainingPoller({
    fetchStatus: () => getModelTraining(props.modelName),
    isHidden: () => document.hidden || !props.visible,
    applyStatus(value) {
        state.value = value
        loading.value = false
        error.value = ''
    },
    onError(failure) {
        loading.value = false
        error.value = operationErrorMessage(failure, '학습 작업 조회에 실패해 자동 조회를 멈췄습니다. 수동으로 다시 불러와 주세요.')
    },
})
const refresh = async () => {
    if (document.hidden || !props.visible || !props.modelName) return
    loading.value = true
    await monitor.refresh()
}
const onVisibility = () => {
    if (document.hidden) monitor.pause()
    else if (!error.value) refresh()
}
watch(
    () => Boolean(busy.value || pending.value || modal.value),
    (value) => emit('locked', value)
)
watch(
    () => Boolean(busy.value),
    (value) => emit('busy', value)
)
watch(
    () => props.modelName,
    () => {
        monitor.pause()
        state.value = null
        error.value = ''
        message.value = ''
        confirmed.value = false
        refresh()
    }
)
watch(() => props.refreshKey, refresh)
watch(
    () => props.visible,
    (visible) => {
        if (visible) refresh()
        else monitor.pause()
    }
)
watch(
    () => trainingTarget.value?.digest,
    (current, previous) => {
        if (current !== previous && modal.value === 'start' && !pending.value) {
            confirmed.value = false
            validation.value = '학습 대상 모델 또는 기반 버전이 바뀌었습니다. 변경된 대상을 다시 확인해 주세요.'
        }
    }
)
const openStart = () => {
    if (!canStart.value || busy.value) return
    modal.value = 'start'
    confirmed.value = false
    validation.value = ''
}
const finish = async (result) => {
    const status = result.operation?.status || result.status
    if (status === 'UNKNOWN') {
        validation.value = '서버가 요청 결과를 확정하지 못했습니다. 기존 요청 상태를 먼저 확인해 주세요.'
        return
    }
    message.value = result.message || '요청을 서버에 접수했습니다. 실제 진행 상태는 작업 목록에서 확인해 주세요.'
    pending.value = null
    modal.value = ''
    await refresh()
    emit('changed')
}
const submit = async () => {
    if (busy.value || !props.canTrain || (!pending.value && !confirmed.value)) return
    if (!pending.value) {
        if (modal.value !== 'start') return
        const value = Number(iterations.value)
        if (!Number.isInteger(value) || value < 1 || value > 20) {
            validation.value = '학습 반복 횟수는 1부터 20 사이의 정수로 입력해 주세요.'
            return
        }
        if (!canStart.value) {
            validation.value = '현재 학습 실행 상태를 다시 확인해 주세요.'
            return
        }
        pending.value = {
            body: { operationId: crypto.randomUUID(), iterations: value, model: trainingTarget.value.sourceModel, digest: trainingTarget.value.digest },
        }
    }
    busy.value = true
    validation.value = ''
    try {
        const result = await startModelTraining(pending.value.body)
        if (!disposed) await finish(result)
    } catch (failure) {
        if (disposed) return
        validation.value = operationErrorMessage(failure, '응답을 확인하지 못했습니다. 새 작업을 만들지 말고 기존 요청 상태를 먼저 확인해 주세요.')
        if (!uncertainRequestError(failure)) pending.value = null
    } finally {
        if (!disposed) busy.value = false
    }
}
const checkPending = async () => {
    if (!pending.value || busy.value) return
    busy.value = true
    try {
        const result = await getModelOperation(pending.value.body.operationId)
        if (disposed) return
        if (result.status === 'UNKNOWN') validation.value = `기존 요청 상태: ${operationStatusLabel(result.status)}. 새 요청을 만들지 말고 다시 확인해 주세요.`
        else await finish(result)
    } catch (failure) {
        if (!disposed) validation.value = operationErrorMessage(failure, '기존 요청 상태를 확인하지 못했습니다. 같은 요청 식별자로만 수동 재시도해 주세요.')
    } finally {
        if (!disposed) busy.value = false
    }
}
onMounted(() => {
    refresh()
    document.addEventListener('visibilitychange', onVisibility)
})
onBeforeUnmount(() => {
    disposed = true
    monitor.stop()
    emit('locked', false)
    emit('busy', false)
    document.removeEventListener('visibilitychange', onVisibility)
})
defineExpose({ openStart, refresh })
</script>

<template>
    <section class="min-w-0">
        <div class="mb-6 flex flex-wrap items-start justify-between gap-4 sm:items-center">
            <div class="min-w-0 flex-1 basis-72">
                <h2 class="text-base font-extrabold">선택 모델 학습</h2>
                <p class="mt-2 text-xs leading-6 text-slate-500 dark:text-slate-400">
                    선택한 모델로 분석하고 승인한 자료로 시험 학습을 실행합니다. 진행 상황과 취소는 실행 작업 이력에서 확인합니다.
                </p>
            </div>
            <div class="flex shrink-0 flex-wrap items-center gap-2">
                <BaseButton variant="secondary" :loading="loading" size="small" @click="refresh">작업 새로고침</BaseButton
                ><BaseButton v-if="canTrain" :disabled="!canStart || busy" size="small" @click="openStart">학습 시작</BaseButton>
            </div>
        </div>
        <section class="mb-5 rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900 sm:p-6">
            <h3 class="text-sm font-bold">학습 대상과 사용 자료</h3>
            <p v-if="state?.requestedModel" class="mt-2 break-all text-xs text-slate-500 dark:text-slate-400">선택한 모델 · {{ state.requestedModel }}</p>
            <p class="mt-1 text-xs leading-6 text-slate-500 dark:text-slate-400">선택한 모델에 대응하는 학습 기반 모델과 버전을 자동으로 확인합니다.</p>
            <template v-if="trainingTarget">
                <p class="mt-3 break-all text-sm font-bold">{{ trainingTarget.sourceModel }} → {{ trainingTarget.baseModel }}</p>
                <p class="mt-1 break-all text-xs text-slate-500 dark:text-slate-400">기반 모델 버전 · {{ trainingTarget.revision }}</p>
                <p class="mt-3 text-xs leading-6 text-slate-500 dark:text-slate-400">
                    같은 모델로 분석하고 승인한 자료만 사용합니다. 생성 모델이 다르거나 기록이 없는 자료는 제외합니다.
                </p>
                <p class="mt-3 text-sm font-semibold">사용 가능한 승인 자료 · {{ state.dataset.total ?? 0 }}건</p>
                <ul class="mt-2 grid gap-2 text-xs sm:grid-cols-2">
                    <li v-for="sample in trainingSamples" :key="sample.caseId" class="break-all rounded-lg bg-slate-50 p-3 dark:bg-slate-800">
                        {{ sample.caseId }} · {{ sample.sourceModel }}
                    </li>
                </ul>
                <p v-if="state.dataset.truncated" class="mt-2 text-xs text-slate-500">최근 목록 중 최대 100건만 표시합니다.</p>
                <p v-if="state.dataset.excluded" class="mt-2 text-xs text-amber-700 dark:text-amber-300">
                    다른 모델 또는 생성 모델 미확인 자료 {{ state.dataset.excluded }}건은 제외했습니다.
                </p>
            </template>
            <p v-else class="mt-2 text-sm text-slate-500">호환되는 학습 대상 모델을 확인하지 못했습니다. 모델 대응 등록과 서버 연결을 확인해 주세요.</p>
        </section>
        <p v-if="state?.capabilities?.reason" class="mb-4 rounded-lg bg-slate-100 p-3 text-sm leading-6 dark:bg-slate-800">{{ state.capabilities.reason }}</p>
        <p v-if="trainingTarget" class="mb-5 rounded-xl bg-blue-50 p-4 text-sm font-bold leading-7 text-blue-900 dark:bg-blue-950/40 dark:text-blue-100">
            {{ trainingTarget.sourceModel }}에 승인 자료 {{ state.dataset.total ?? 0 }}건으로 {{ iterations }}회 시험 학습합니다.
        </p>
        <p v-if="hasActive" class="mb-4 text-xs text-blue-700 dark:text-blue-300">
            학습 서버에서 진행 중인 작업이 있어 새 학습을 시작할 수 없습니다. 활성 작업은 5초마다 순서대로 확인합니다. 화면이 숨겨지면 멈추며 조회 오류 뒤에는
            수동 새로고침이 필요합니다.
        </p>
        <p v-if="!canTrain" class="mb-4 text-xs text-slate-500 dark:text-slate-400">학습 시작에는 MODEL_TRAIN 권한이 필요합니다.</p>
        <p v-if="message" role="status" class="mb-4 rounded-lg bg-blue-50 p-3 text-sm leading-6 text-blue-800 dark:bg-blue-950/40 dark:text-blue-200">
            {{ message }}
        </p>
        <p v-if="error" role="alert" class="mb-4 rounded-lg bg-rose-50 p-3 text-sm leading-6 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
            {{ error }}
        </p>
        <div v-if="pending && !modal" class="mb-4 rounded-lg border border-amber-200 p-4 dark:border-amber-800">
            <p class="break-all text-xs leading-6">확인 중인 요청 · {{ pending.body.operationId }}</p>
            <p class="mt-2 text-sm">{{ validation }}</p>
            <div class="mt-3 flex flex-wrap gap-2">
                <BaseButton size="small" variant="secondary" :disabled="busy" @click="checkPending">기존 요청 상태 확인</BaseButton
                ><BaseButton size="small" variant="secondary" :disabled="busy" @click="submit">같은 요청 수동 재시도</BaseButton>
            </div>
        </div>
        <p v-if="!state && loading" role="status" class="py-10 text-center text-sm text-slate-500">학습 대상을 불러오고 있습니다.</p>
        <BaseModal
            :model-value="Boolean(modal)"
            title="학습 시작 확인"
            :busy="busy"
            footer-text="학습 서버에서 실제 작업을 수행합니다. 학습 결과가 운영 모델로 자동 적용되지는 않습니다."
            @update:model-value="
                (open) => {
                    if (!open && !busy) modal = ''
                }
            "
        >
            <div class="grid gap-4 text-sm leading-7">
                <p class="rounded-lg bg-amber-50 p-4 text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
                    학습 서버의 GPU·메모리·전원과 저장 공간을 사용하며, 다른 작업의 응답 속도에 영향을 줄 수 있습니다. 승인된 자료만 사용하며 학습·저장·재로딩을
                    수행합니다.
                </p>
                <p class="break-all font-bold">학습 대상 · {{ trainingTarget?.sourceModel }} / {{ trainingTarget?.baseModel }}</p>
                <BaseInput v-model="iterations" label="학습 반복 횟수" type="number" min="1" max="20" step="1" :disabled="busy || Boolean(pending)" />
                <p>생성된 어댑터는 ‘학습 결과 적용’에서 전달 준비와 응답 검증을 거쳐 적용할 수 있습니다. 학습만 실행하면 현재 분석 모델은 바뀌지 않습니다.</p>
                <label class="flex items-start gap-3"
                    ><input v-model="confirmed" type="checkbox" class="mt-1.5 h-4 w-4 shrink-0" :disabled="busy || Boolean(pending)" /><span
                        >실제 자원 사용과 운영 모델에 자동 적용되지 않는 범위를 확인했습니다.</span
                    ></label
                >
                <p v-if="validation" role="alert" class="text-rose-700 dark:text-rose-300">{{ validation }}</p>
                <p v-if="pending" class="break-all text-xs text-slate-500">요청 식별자 · {{ pending.body.operationId }}</p>
                <div class="flex flex-wrap gap-2">
                    <BaseButton :disabled="!confirmed || Boolean(pending)" :loading="busy" @click="submit">확인 후 학습 시작</BaseButton
                    ><BaseButton v-if="pending" variant="secondary" :disabled="busy" @click="checkPending">기존 요청 상태 확인</BaseButton
                    ><BaseButton v-if="pending" variant="secondary" :disabled="busy" @click="submit">같은 요청 수동 재시도</BaseButton>
                </div>
            </div>
        </BaseModal>
    </section>
</template>
