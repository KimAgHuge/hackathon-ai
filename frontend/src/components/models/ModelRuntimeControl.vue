<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { probeInstalledModel, activateInstalledModel, rollbackInstalledModel, getModelOperation } from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import { operationErrorMessage, operationStatusLabel, probeMatchesSelection, uncertainRequestError } from '@/lib/modelOperations'

const props = defineProps({
    snapshot: { type: Object, default: null },
    modelName: { type: String, default: '' },
    available: Boolean,
    adapterMode: Boolean,
    trainedMode: Boolean,
    canDeploy: Boolean,
    canRollback: Boolean,
    reload: { type: Function, required: true },
})
const emit = defineEmits(['changed', 'locked', 'busy'])
const installed = computed(() => props.snapshot)
const loaded = computed(() => props.available)
const selectedName = computed(() => props.modelName)
const probe = ref(null)
const probeRequest = ref(null)
const pending = ref(null)
const busy = ref('')
const error = ref('')
const message = ref('')
const confirmMode = ref('')
const confirmed = ref(false)
const now = ref(Date.now())
let disposed = false
let clock = null
const choices = computed(() => (Array.isArray(installed.value?.models) ? installed.value.models : []))
const selected = computed(() => choices.value.find((model) => model.name === selectedName.value) || null)
const probeValid = computed(() => loaded.value && probeMatchesSelection(probe.value, selected.value, installed.value, now.value))
const load = () => props.reload()
watch(
    () => Boolean(busy.value || pending.value || confirmMode.value),
    (value) => emit('locked', value)
)
watch(
    () => Boolean(busy.value),
    (value) => emit('busy', value)
)
watch(selectedName, () => {
    probe.value = null
    probeRequest.value = null
    message.value = ''
    error.value = ''
})
const verify = async () => {
    if (!props.canDeploy || !selected.value?.selectable || busy.value || pending.value || !loaded.value) return
    const model = selected.value
    const request = probeRequest.value || { operationId: crypto.randomUUID(), model: model.name, digest: model.digest }
    probeRequest.value = request
    busy.value = 'probe'
    error.value = ''
    probe.value = null
    try {
        const result = await probeInstalledModel(request)
        if (disposed) return
        probe.value = result
        probeRequest.value = null
        message.value =
            result.status === 'PASSED' && !result.probeToken
                ? '기존 검증 기록은 확인했지만 전환용 시험 토큰은 다시 발급되지 않습니다. 새 JSON 응답 검증을 실행해 주세요.'
                : result.status === 'PASSED'
                  ? '짧은 JSON 응답 검증을 통과했습니다. 업무 정답 품질이나 운영 승인을 보증하지 않습니다.'
                  : result.message || '모델 검증을 통과하지 못했습니다.'
        if (result.status !== 'PASSED') {
            error.value = `${message.value}${result.errorCode ? ` · ${result.errorCode}` : ''}`
            message.value = ''
        }
    } catch (failure) {
        if (disposed) return
        error.value = operationErrorMessage(failure, '검증 응답을 확인하지 못했습니다. 같은 요청으로 수동 재시도할 수 있습니다.')
        if (!uncertainRequestError(failure)) probeRequest.value = null
    } finally {
        if (!disposed) busy.value = ''
    }
}
const openConfirm = (mode) => {
    if (busy.value || pending.value || !loaded.value) return
    if (mode === 'activate' && (!props.canDeploy || !probeValid.value)) return
    if (mode === 'rollback' && (!props.canRollback || !installed.value?.canRollback)) return
    confirmMode.value = mode
    confirmed.value = false
    error.value = ''
}
const acceptResult = async (result) => {
    const status = result.operation?.status || result.status
    message.value = result.message || result.operation?.message || `작업 상태: ${operationStatusLabel(status)}`
    if (status === 'FAILED') {
        error.value = `${message.value}${result.errorCode ? ` · ${result.errorCode}` : ''}`
        message.value = ''
    }
    if (['APPLIED', 'ROLLED_BACK', 'FAILED', 'CANCELLED'].includes(status)) {
        pending.value = null
        confirmMode.value = ''
        probe.value = null
        await load()
        emit('changed')
    } else {
        error.value = '작업 결과가 아직 확정되지 않았습니다. 새 전환을 요청하지 말고 작업 상태를 다시 확인해 주세요.'
    }
}
const apply = async () => {
    if (busy.value || (!pending.value && !confirmed.value)) return
    const mode = pending.value?.mode || confirmMode.value
    if (!['activate', 'rollback'].includes(mode)) return
    if ((mode === 'activate' && !props.canDeploy) || (mode === 'rollback' && !props.canRollback)) return
    if (!pending.value) {
        if (!loaded.value || !Number.isSafeInteger(installed.value?.currentRevision) || installed.value.currentRevision < 0) return
        if (mode === 'rollback' && installed.value?.canRollback !== true) return
        if (mode === 'activate' && !probeMatchesSelection(probe.value, selected.value, installed.value)) {
            error.value = '검증 결과가 만료되었거나 모델 상태가 바뀌었습니다. 다시 검증해 주세요.'
            return
        }
        pending.value = {
            mode,
            body:
                mode === 'activate'
                    ? {
                          operationId: crypto.randomUUID(),
                          model: selected.value.name,
                          digest: selected.value.digest,
                          probeToken: probe.value.probeToken,
                          expectedRevision: installed.value.currentRevision,
                      }
                    : { operationId: crypto.randomUUID(), expectedRevision: installed.value.currentRevision },
        }
    }
    busy.value = mode
    error.value = ''
    try {
        const result = mode === 'activate' ? await activateInstalledModel(pending.value.body) : await rollbackInstalledModel(pending.value.body)
        if (!disposed) await acceptResult(result)
    } catch (failure) {
        if (disposed) return
        error.value = operationErrorMessage(failure, '응답을 확인하지 못했습니다. 실제 적용 여부가 불명확하므로 작업 상태를 확인해 주세요.')
        if (!uncertainRequestError(failure)) {
            pending.value = null
            probe.value = null
            confirmMode.value = ''
            await load()
        }
    } finally {
        if (!disposed) busy.value = ''
    }
}
const checkPending = async () => {
    if (!pending.value || busy.value) return
    busy.value = 'status'
    error.value = ''
    try {
        const result = await getModelOperation(pending.value.body.operationId)
        if (!disposed) await acceptResult(result)
    } catch (failure) {
        if (!disposed) error.value = operationErrorMessage(failure, '작업 기록을 확인하지 못했습니다. 아래 재시도는 기존 요청 식별자를 그대로 사용합니다.')
    } finally {
        if (!disposed) busy.value = ''
    }
}
onMounted(() => {
    clock = setInterval(() => {
        now.value = Date.now()
    }, 1000)
})
onBeforeUnmount(() => {
    disposed = true
    clearInterval(clock)
    emit('locked', false)
    emit('busy', false)
})
</script>

<template>
    <section class="min-w-0">
        <div class="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <h3 class="text-base font-extrabold">{{ trainedMode ? '새 학습 모델 적용' : adapterMode ? '준비한 학습 결과 적용' : '분석 모델로 사용' }}</h3>
            <p class="mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">선택한 모델의 JSON 응답을 검증한 뒤 새 분석에 적용합니다.</p>
            <div class="mt-5 flex flex-wrap gap-3">
                <BaseButton
                    variant="secondary"
                    :disabled="!canDeploy || !selected?.selectable || !loaded || Boolean(busy) || Boolean(pending)"
                    :loading="busy === 'probe'"
                    @click="verify"
                    >{{
                        probeRequest ? '같은 검증 재시도' : trainedMode ? '새 모델 응답 검증' : adapterMode ? '학습 결과 응답 검증' : 'JSON 응답 검증'
                    }}</BaseButton
                ><BaseButton :disabled="!canDeploy || !probeValid || Boolean(busy) || Boolean(pending)" @click="openConfirm('activate')">{{
                    trainedMode ? '새 학습 모델 적용' : adapterMode ? '학습 결과 적용' : '분석 모델로 사용'
                }}</BaseButton>
            </div>
            <p v-if="busy === 'probe'" role="status" class="mt-3 text-sm leading-6 text-blue-700 dark:text-blue-300">
                AI 서버에서 모델을 불러와 실제 JSON 응답을 시험하고 있습니다. GPU·메모리를 사용하며 응답을 기다리는 동안 중복 요청을 보내지 마세요.
            </p>
            <p v-if="selected" class="mt-2 break-all text-xs text-slate-500 dark:text-slate-400">
                {{ selected.reason || '설치 파일 식별자를 확인한 뒤 검증과 전환을 진행합니다.' }} · {{ selected.digest }}
            </p>
            <p v-if="probe && !probeValid" class="mt-2 text-xs text-amber-700 dark:text-amber-300">
                검증 결과가 만료되었거나 현재 선택·설정과 다릅니다. 다시 검증해 주세요.
            </p>
            <div v-if="choices.some((model) => !model.selectable)" class="mt-3 grid gap-2">
                <p v-for="model in choices.filter((item) => !item.selectable)" :key="model.name" class="break-all text-xs text-slate-500 dark:text-slate-400">
                    선택 불가 · {{ model.name }} · {{ model.reason || '이 모델은 전환할 수 없습니다.' }}
                </p>
            </div>
            <p v-if="loaded && !choices.length" class="mt-4 text-sm text-slate-500 dark:text-slate-400">AI 서버에서 확인한 설치 모델이 없습니다.</p>
            <p v-if="!canDeploy" class="mt-3 text-xs text-slate-500 dark:text-slate-400">검증과 전환에는 MODEL_DEPLOY 권한이 필요합니다.</p>
            <p v-if="message" role="status" class="mt-4 rounded-lg bg-blue-50 p-3 text-sm leading-6 text-blue-800 dark:bg-blue-950/40 dark:text-blue-200">
                {{ message }}
            </p>
            <p v-if="error" role="alert" class="mt-4 rounded-lg bg-rose-50 p-3 text-sm leading-6 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
                {{ error }}
            </p>
            <div v-if="pending" class="mt-4 rounded-lg border border-amber-200 p-4 dark:border-amber-800">
                <p class="break-all text-xs leading-6">확인 중인 요청 · {{ pending.body.operationId }}</p>
                <div class="mt-3 flex flex-wrap gap-2">
                    <BaseButton size="small" variant="secondary" :disabled="Boolean(busy)" @click="checkPending">작업 상태 확인</BaseButton
                    ><BaseButton size="small" variant="secondary" :disabled="Boolean(busy)" @click="apply">같은 요청 수동 재시도</BaseButton>
                </div>
            </div>
            <div v-if="!adapterMode && !trainedMode" class="mt-4 flex flex-wrap items-center gap-3">
                <BaseButton
                    variant="secondary"
                    :disabled="!canRollback || !loaded || !installed?.canRollback || Boolean(busy) || Boolean(pending)"
                    @click="openConfirm('rollback')"
                    >이전 모델로 복구</BaseButton
                ><span class="text-xs text-slate-500 dark:text-slate-400">{{
                    canRollback ? '서버가 확인한 이전 설정 모델로만 복구합니다.' : '복구에는 MODEL_ROLLBACK 권한이 필요합니다.'
                }}</span>
            </div>
            <p class="mt-4 text-xs leading-6 text-slate-500 dark:text-slate-400">
                변경은 새 분석부터 적용합니다. 완료한 학습으로 새 모델을 만드는 작업은 ‘학습 결과 적용’에서 진행합니다.
            </p>
            <BaseModal
                :model-value="Boolean(confirmMode)"
                :title="
                    confirmMode === 'rollback'
                        ? '이전 모델 복구 확인'
                        : trainedMode
                          ? '새 학습 모델 적용 확인'
                          : adapterMode
                            ? '학습 결과 적용 확인'
                            : '설치 모델 전환 확인'
                "
                :busy="Boolean(busy)"
                footer-text="명시적으로 확인한 모델 설정만 변경합니다. 진행 중인 분석은 시작할 때의 모델을 유지합니다."
                @update:model-value="
                    (open) => {
                        if (!open && !busy) confirmMode = ''
                    }
                "
            >
                <div class="grid gap-4 text-sm leading-7">
                    <p class="break-all font-bold">
                        {{ installed?.model || '미확인' }} → {{ confirmMode === 'rollback' ? installed?.previousModel : selected?.name }}
                    </p>
                    <p>
                        새 분석 요청의 모델 설정을 변경합니다. 짧은 JSON 검증은 업무 정답 품질과 운영 승인을 보증하지 않습니다. 진행 중인 분석의 모델은 바꾸지
                        않습니다.
                    </p>
                    <p v-if="trainedMode || selected?.trainedModel" class="rounded-lg bg-amber-50 p-4 text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
                        학습 결과를 합쳐 만든 새 모델입니다. 분석 서버의 기존 모델 실행 환경에서 실행하며 학습 서버는 추론에 사용하지 않습니다. 독립 생성 평가와
                        업무 품질 평가는 아직 수행하지 않았습니다. 적용 뒤 새 분석부터 이 버전을 사용합니다.
                    </p>
                    <p
                        v-else-if="adapterMode || selected?.adapterId"
                        class="rounded-lg bg-amber-50 p-4 text-amber-900 dark:bg-amber-950/40 dark:text-amber-200"
                    >
                        업무 품질을 평가하지 않은 시험 학습 어댑터입니다. AI 분석/추론 서버의 MLX 실행 환경에서 기반 모델과 함께 불러오며, 학습 서버는 추론에
                        사용하지 않습니다. 적용 뒤 새 분석부터 이 학습 결과를 사용합니다.
                    </p>
                    <label class="flex items-start gap-3"
                        ><input v-model="confirmed" type="checkbox" class="mt-1.5 h-4 w-4 shrink-0" :disabled="Boolean(busy) || Boolean(pending)" /><span
                            >대상 모델과 새 요청부터 적용되는 범위를 확인했습니다.</span
                        ></label
                    >
                    <p v-if="error" role="alert" class="text-rose-700 dark:text-rose-300">{{ error }}</p>
                    <div class="flex flex-wrap gap-2">
                        <BaseButton :disabled="!confirmed || Boolean(pending)" :loading="Boolean(busy)" @click="apply">{{
                            confirmMode === 'rollback'
                                ? '확인 후 복구'
                                : trainedMode
                                  ? '확인 후 새 모델 적용'
                                  : adapterMode
                                    ? '확인 후 학습 결과 적용'
                                    : '확인 후 전환'
                        }}</BaseButton
                        ><BaseButton v-if="pending" variant="secondary" :disabled="Boolean(busy)" @click="checkPending">기존 요청 상태 확인</BaseButton>
                    </div>
                </div>
            </BaseModal>
        </div>
    </section>
</template>
