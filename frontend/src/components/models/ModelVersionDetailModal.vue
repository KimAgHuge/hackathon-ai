<script setup>
import { computed, reactive, ref, watch } from 'vue'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import ModelStateBadge from '@/components/models/ModelStateBadge.vue'

const props = defineProps({
    modelValue: { type: Boolean, default: false },
    version: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    error: { type: String, default: '' },
    busy: { type: Boolean, default: false },
    evaluationError: { type: String, default: '' },
    evaluationSuccess: { type: String, default: '' },
    canEvaluate: { type: Boolean, default: false },
    canDeploy: { type: Boolean, default: false },
    canRollback: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'reload', 'submit-evaluation'])
const tab = ref('info')
const showEvaluationForm = ref(false)
const validation = ref('')
const emptyEvaluation = () => ({
    status: '',
    overallScore: '',
    notes: '',
    jsonCompliance: '',
    databaseJudgment: '',
    koreanQuality: '',
    averageResponseSeconds: '',
    sampleCount: '',
})
const form = reactive(emptyEvaluation())
const metricFields = [
    { key: 'jsonCompliance', label: 'JSON 형식 준수', unit: '점', max: 100 },
    { key: 'databaseJudgment', label: 'DB 변경 판단', unit: '점', max: 100 },
    { key: 'koreanQuality', label: '한국어 응답 품질', unit: '점', max: 100 },
    { key: 'averageResponseSeconds', label: '평균 응답 시간', unit: '초' },
    { key: 'sampleCount', label: '평가 표본 수', unit: '개', max: 2147483647 },
]
const evaluations = computed(() => (Array.isArray(props.version?.evaluations) ? props.version.evaluations : []))
const deployments = computed(() => (Array.isArray(props.version?.deployments) ? props.version.deployments : []))
const checks = computed(() => (Array.isArray(props.version?.readiness?.checks) ? props.version.readiness.checks : []))
const automaticallyRecorded = computed(() => ['TRAINED_MODEL_IMPORT', 'RUNTIME_SELECTION'].includes(props.version?.historySource))
const verifiedRegistration = computed(() => props.version?.registrationVerified === true)
const artifactLabel = (value) => ({ BASE: '기반 모델', LORA: 'LoRA 어댑터', MERGED: '병합 모델', EXTERNAL: '외부 모델' })[value] || '기록 없음'
const basicFields = computed(() => [
    ['모델 이름', props.version?.modelName],
    ['버전', props.version?.version],
    ['기반 모델 식별자', props.version?.baseModelRef],
    ['파일 유형', artifactLabel(props.version?.artifactType)],
    ['학습 데이터셋', props.version?.datasetName],
    ['등록 일시', dateLabel(props.version?.createdAt)],
    ['등록 경로', automaticallyRecorded.value ? '모델 작업 자동 기록' : '직접 등록'],
    ...(props.version?.runtimeModel ? [['등록된 실행 모델', props.version.runtimeModel]] : []),
    ...(props.version?.runtimeDigest ? [['실행 모델 식별값', props.version.runtimeDigest]] : []),
    ...(props.version?.trainingJobId ? [['학습 작업 식별자', props.version.trainingJobId]] : []),
    ['모델 파일 위치', props.version?.artifactUri],
    ['어댑터 파일 위치', props.version?.adapterUri],
    ['SHA-256 해시', props.version?.checksum],
    ['버전 식별자', props.version?.id],
])
const dateLabel = (value) => {
    if (!value) return '기록 없음'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '시각 확인 불가' : date.toLocaleString('ko-KR')
}
const measured = (value, unit = '점') => (value === null || value === undefined || value === '' ? '미측정' : `${value}${unit}`)
const resetEvaluation = () => {
    Object.assign(form, emptyEvaluation())
    validation.value = ''
    showEvaluationForm.value = false
}
watch(
    () => props.modelValue,
    (open) => {
        if (!open) return
        tab.value = 'info'
        resetEvaluation()
    }
)
watch(
    () => props.evaluationSuccess,
    (message) => {
        if (message) resetEvaluation()
    }
)
const submitEvaluation = () => {
    if (props.busy || !props.canEvaluate) return
    if (!['PASSED', 'FAILED'].includes(form.status) || !form.notes.trim()) {
        validation.value = '평가 판단과 판단 근거를 입력해 주세요.'
        return
    }
    const metrics = {}
    for (const field of metricFields) {
        if (form[field.key] === '') continue
        const value = Number(form[field.key])
        if (!Number.isFinite(value) || value < 0 || (field.max !== undefined && value > field.max)) {
            validation.value = `${field.label} 값을 허용 범위 안에서 입력해 주세요.`
            return
        }
        if (field.key === 'sampleCount' && (!Number.isInteger(value) || value <= 0)) {
            validation.value = '평가 표본 수에는 1 이상의 정수를 입력해 주세요.'
            return
        }
        metrics[field.key] = value
    }
    const overallScore = form.overallScore === '' ? null : Number(form.overallScore)
    if (overallScore !== null && (!Number.isFinite(overallScore) || overallScore < 0 || overallScore > 100)) {
        validation.value = '종합 점수는 0부터 100 사이로 입력해 주세요.'
        return
    }
    validation.value = ''
    emit('submit-evaluation', { status: form.status, notes: form.notes.trim(), overallScore, metrics })
}
</script>

<template>
    <BaseModal
        :model-value="modelValue"
        title="모델 버전 상세"
        :busy="busy"
        footer-text="모델의 등록·평가·적용 기록을 조회합니다. 모델 전환과 학습은 모델 작업에서 진행합니다."
        @update:model-value="emit('update:modelValue', $event)"
    >
        <div v-if="loading" role="status" class="py-16 text-center text-sm text-slate-500 dark:text-slate-400">버전 상세를 불러오고 있습니다.</div>
        <div
            v-else-if="error && !version"
            role="alert"
            class="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-700 dark:border-rose-800 dark:bg-rose-950/40 dark:text-rose-300"
        >
            <p>{{ error }}</p>
            <BaseButton class="mt-3" variant="secondary" @click="emit('reload')">다시 불러오기</BaseButton>
        </div>
        <div v-else-if="version" class="min-w-0">
            <div class="flex flex-wrap items-start justify-between gap-3">
                <div class="min-w-0">
                    <p class="break-all text-lg font-extrabold text-slate-900 dark:text-slate-100">{{ version.modelName }} · {{ version.version }}</p>
                    <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">
                        {{
                            verifiedRegistration
                                ? '실행 엔진 등록을 확인한 모델입니다. 실제 분석에 적용한 기록은 배포 이력에서 확인합니다.'
                                : automaticallyRecorded
                                  ? '모델 적용 과정에서 자동으로 저장된 기록입니다. 현재 실행 상태는 추론 서버 확인에서 확인합니다.'
                                  : '직접 저장한 관리 정보입니다. 파일 검증과 실제 적용 여부는 별도로 확인해야 합니다.'
                        }}
                    </p>
                </div>
                <ModelStateBadge :status="version.status" />
            </div>
            <div v-if="error" role="alert" class="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-800 dark:bg-amber-950/40 dark:text-amber-200">
                <p>{{ error }}</p>
                <BaseButton class="mt-3" variant="secondary" size="small" :disabled="busy" @click="emit('reload')">상세 다시 불러오기</BaseButton>
            </div>
            <p
                v-if="evaluationSuccess"
                role="status"
                class="mt-4 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-200"
            >
                {{ evaluationSuccess }}
            </p>
            <div class="my-5 flex flex-wrap gap-2 border-b border-slate-200 pb-3 dark:border-slate-700" aria-label="버전 상세 보기">
                <button
                    v-for="item in [
                        { key: 'info', label: '기본 정보' },
                        { key: 'evaluation', label: '평가 결과' },
                        { key: 'readiness', label: '배포 준비' },
                    ]"
                    :key="item.key"
                    type="button"
                    class="rounded-lg px-4 py-2 text-sm font-bold transition-colors"
                    :class="
                        tab === item.key
                            ? 'bg-blue-600 text-white'
                            : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700'
                    "
                    :aria-pressed="tab === item.key"
                    :disabled="busy"
                    @click="tab = item.key"
                >
                    {{ item.label }}
                </button>
            </div>

            <section v-if="tab === 'info'">
                <dl class="grid gap-4 sm:grid-cols-2">
                    <div v-for="[label, value] in basicFields" :key="label" class="min-w-0 rounded-lg bg-slate-50 p-4 dark:bg-slate-800/60">
                        <dt class="text-xs font-semibold text-slate-500 dark:text-slate-400">{{ label }}</dt>
                        <dd class="mt-2 break-all text-sm font-bold text-slate-800 dark:text-slate-100">{{ value || '기록 없음' }}</dd>
                    </div>
                </dl>
                <div
                    v-for="item in [
                        { label: '모델 설명', value: version.description },
                        { label: '등록 메모', value: version.notes },
                    ]"
                    :key="item.label"
                    class="mt-5"
                >
                    <h3 class="text-sm font-bold text-slate-900 dark:text-slate-100">{{ item.label }}</h3>
                    <p class="mt-2 whitespace-pre-wrap break-all text-sm leading-6 text-slate-600 dark:text-slate-300">{{ item.value || '기록 없음' }}</p>
                </div>
            </section>

            <section v-else-if="tab === 'evaluation'" class="grid gap-4">
                <div class="flex flex-wrap items-center justify-between gap-3">
                    <div>
                        <h3 class="text-sm font-bold text-slate-900 dark:text-slate-100">저장된 평가 기록</h3>
                        <p class="mt-1 text-xs leading-5 text-slate-500 dark:text-slate-400">
                            수동 통과 기록은 자동 평가 실행이나 배포 승인을 대신하지 않습니다.
                        </p>
                    </div>
                    <BaseButton v-if="canEvaluate && !showEvaluationForm" variant="secondary" size="small" @click="showEvaluationForm = true"
                        >수동 평가 기록</BaseButton
                    >
                </div>
                <p v-if="!canEvaluate" class="text-xs text-slate-500 dark:text-slate-400">평가 기록을 추가하려면 MODEL_EVALUATE 권한이 필요합니다.</p>
                <p v-if="version.detailTruncated?.evaluations" class="text-xs text-amber-700 dark:text-amber-300">
                    평가 기록이 많아 서버가 반환한 최근 항목만 표시합니다.
                </p>
                <form
                    v-if="showEvaluationForm && canEvaluate"
                    class="rounded-xl border border-blue-200 bg-blue-50/50 p-4 dark:border-blue-800 dark:bg-blue-950/20"
                    @submit.prevent="submitEvaluation"
                >
                    <p class="mb-4 text-sm leading-6 text-blue-800 dark:text-blue-200">
                        별도로 수행한 평가의 결과와 근거만 저장합니다. 이 화면에서 자동 평가를 실행하지 않으며, 미측정 항목은 비워 두세요.
                    </p>
                    <fieldset class="grid gap-4" :disabled="busy">
                        <div class="grid gap-4 sm:grid-cols-2">
                            <BaseSelect
                                v-model="form.status"
                                label="수동 평가 판단"
                                required
                                :options="[
                                    { value: '', label: '판단 선택' },
                                    { value: 'PASSED', label: '통과 기록' },
                                    { value: 'FAILED', label: '실패 기록' },
                                ]"
                            />
                            <BaseInput v-model="form.overallScore" label="종합 점수" type="number" min="0" max="100" step="any" placeholder="미측정" />
                            <BaseInput
                                v-for="field in metricFields"
                                :key="field.key"
                                v-model="form[field.key]"
                                :label="`${field.label} (${field.unit})`"
                                type="number"
                                :min="field.key === 'sampleCount' ? 1 : 0"
                                :max="field.max"
                                :step="field.key === 'sampleCount' ? 1 : 'any'"
                                placeholder="미측정"
                            />
                        </div>
                        <BaseTextarea
                            v-model="form.notes"
                            label="판단 근거"
                            required
                            maxlength="4000"
                            rows="3"
                            :disabled="busy"
                            placeholder="평가 방법, 표본, 판단 이유와 한계를 기록해 주세요. 비밀값은 입력하지 마세요."
                        />
                    </fieldset>
                    <p v-if="validation || evaluationError" role="alert" class="mt-4 text-sm text-rose-700 dark:text-rose-300">
                        {{ validation || evaluationError }}
                    </p>
                    <div class="mt-4 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                        <BaseButton variant="secondary" :disabled="busy" @click="showEvaluationForm = false">입력 닫기</BaseButton>
                        <BaseButton type="submit" :loading="busy">수동 평가 기록 저장</BaseButton>
                    </div>
                </form>
                <div
                    v-if="!evaluations.length"
                    class="rounded-xl border border-dashed border-slate-300 py-10 text-center text-sm text-slate-500 dark:border-slate-700 dark:text-slate-400"
                >
                    등록된 평가 기록이 없습니다. 점수는 미측정 상태입니다.
                </div>
                <article v-for="evaluation in evaluations" :key="evaluation.id" class="min-w-0 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
                    <div class="flex flex-wrap items-center justify-between gap-3">
                        <div>
                            <span class="text-sm font-bold text-slate-900 dark:text-slate-100">{{
                                evaluation.evaluationType === 'MANUAL' ? '수동 평가 기록' : '평가 기록'
                            }}</span>
                            <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ dateLabel(evaluation.evaluatedAt) }}</p>
                        </div>
                        <ModelStateBadge :status="evaluation.status" />
                    </div>
                    <dl class="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3">
                        <div class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800/60">
                            <dt class="text-xs text-slate-500 dark:text-slate-400">종합 점수</dt>
                            <dd class="mt-1 text-sm font-bold">{{ measured(evaluation.overallScore) }}</dd>
                        </div>
                        <div v-for="field in metricFields" :key="field.key" class="rounded-lg bg-slate-50 p-3 dark:bg-slate-800/60">
                            <dt class="text-xs text-slate-500 dark:text-slate-400">{{ field.label }}</dt>
                            <dd class="mt-1 break-all text-sm font-bold">{{ measured(evaluation.metrics?.[field.key], field.unit) }}</dd>
                        </div>
                    </dl>
                    <p class="mt-4 whitespace-pre-wrap break-all text-sm leading-6 text-slate-600 dark:text-slate-300">
                        {{ evaluation.notes || '판단 근거 기록 없음' }}
                    </p>
                </article>
            </section>

            <section v-else class="grid gap-4">
                <div
                    class="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200"
                >
                    <h3 class="font-bold">{{ verifiedRegistration ? '실행 엔진에 등록된 모델입니다' : '실제 적용은 모델 작업에서 진행합니다' }}</h3>
                    <p class="mt-1">{{ version.readiness?.reason || '서버에서 실행 준비 상태를 확인하지 못했습니다.' }}</p>
                </div>
                <ul v-if="checks.length" class="grid gap-3 sm:grid-cols-2">
                    <li v-for="check in checks" :key="check.key" class="rounded-xl border border-slate-200 p-4 dark:border-slate-700">
                        <div class="flex flex-wrap items-center justify-between gap-2">
                            <span class="text-sm font-bold text-slate-900 dark:text-slate-100">{{ check.label }}</span>
                            <ModelStateBadge :status="check.status" />
                        </div>
                        <p class="mt-2 text-sm leading-6 text-slate-600 dark:text-slate-300">{{ check.message }}</p>
                    </li>
                </ul>
                <div class="flex flex-col gap-3 rounded-xl bg-slate-50 p-4 dark:bg-slate-800/60">
                    <p class="text-xs leading-5 text-slate-500 dark:text-slate-400">
                        모델 작업에서 응답 검증 후 적용하거나 이전 모델로 복구할 수 있습니다. 적용·복구가 성공하면 배포 이력이 자동으로 저장됩니다.
                    </p>
                </div>
                <h3 class="mt-2 text-sm font-bold text-slate-900 dark:text-slate-100">이 버전의 배포 기록</h3>
                <p class="text-xs text-slate-500 dark:text-slate-400">저장된 이력이며 현재 추론 서버의 실행 모델을 보증하지 않습니다.</p>
                <p v-if="version.detailTruncated?.deployments" class="text-xs text-amber-700 dark:text-amber-300">
                    배포 기록이 많아 서버가 반환한 최근 항목만 표시합니다.
                </p>
                <p v-if="!deployments.length" class="py-6 text-center text-sm text-slate-500 dark:text-slate-400">이 버전의 배포 기록이 없습니다.</p>
                <article v-for="deployment in deployments" :key="deployment.id" class="rounded-lg border border-slate-200 p-4 dark:border-slate-700">
                    <div class="flex flex-wrap items-center justify-between gap-2">
                        <p class="min-w-0 break-all text-sm font-bold">{{ deployment.serverName || '서버 기록 없음' }}</p>
                        <ModelStateBadge :status="deployment.status" />
                    </div>
                    <p class="mt-2 break-all text-xs text-slate-500 dark:text-slate-400">
                        {{ deployment.deploymentRole || '역할 기록 없음' }} · 적용 확인 일시 {{ dateLabel(deployment.deployedAt) }}
                    </p>
                </article>
            </section>
        </div>
    </BaseModal>
</template>
