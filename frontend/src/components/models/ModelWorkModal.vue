<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getInstalledModels } from '@/api/modelApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseModal from '@/components/base/BaseModal.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import ModelRuntimeControl from '@/components/models/ModelRuntimeControl.vue'
import ModelTrainingPanel from '@/components/models/ModelTrainingPanel.vue'
import ModelAdapterPanel from '@/components/models/ModelAdapterPanel.vue'
import { operationErrorMessage } from '@/lib/modelOperations'

const props = defineProps({ modelValue: Boolean, canDeploy: Boolean, canRollback: Boolean, canTrain: Boolean, refreshKey: { type: Number, default: 0 } })
const emit = defineEmits(['update:modelValue', 'changed', 'show-operations'])
const installed = ref(null)
const selectedName = ref('')
const activeAction = ref('inference')
const loaded = ref(false)
const loading = ref(false)
const error = ref('')
const runtimeLocked = ref(false)
const trainingLocked = ref(false)
const adapterLocked = ref(false)
const runtimeBusy = ref(false)
const trainingBusy = ref(false)
const adapterBusy = ref(false)
const locked = computed(() => runtimeLocked.value || trainingLocked.value || adapterLocked.value)
const busy = computed(() => runtimeBusy.value || trainingBusy.value || adapterBusy.value)
let generation = 0
let disposed = false
const choices = computed(() => (Array.isArray(installed.value?.models) ? installed.value.models : []))
const trainingModelName = computed(() => choices.value.find((model) => model.name === selectedName.value)?.sourceModel || selectedName.value)
const options = computed(() => [
    { value: '', label: '작업할 모델을 선택하세요' },
    ...choices.value.map((model) => ({
        value: model.name,
        label: `${model.name}${model.adapterId ? ' · 학습 결과' : ''}${model.name === installed.value?.model ? ' · 현재 사용 중' : ''}${model.selectable ? '' : ' · 선택 불가'}`,
        disabled: !model.selectable,
    })),
])
const actions = [
    { key: 'inference', label: '분석에 사용', hint: '응답 검증과 모델 변경' },
    { key: 'training', label: '선택 모델 학습', hint: '승인 자료로 시험 학습' },
    { key: 'adapter', label: '학습 결과 적용', hint: '완료한 어댑터 적용' },
]
const load = async () => {
    const current = ++generation
    loading.value = true
    error.value = ''
    try {
        const result = await getInstalledModels()
        if (disposed || current !== generation) return
        installed.value = result
        loaded.value = true
        if (!result.models?.some((model) => model.name === selectedName.value && model.selectable)) {
            selectedName.value = result.models?.some((model) => model.name === result.model && model.selectable) ? result.model : ''
        }
    } catch (failure) {
        if (disposed || current !== generation) return
        loaded.value = false
        error.value = operationErrorMessage(failure, 'AI 서버의 설치 모델 목록을 불러오지 못했습니다.')
    } finally {
        if (!disposed && current === generation) loading.value = false
    }
}
watch(
    () => props.modelValue,
    (open) => {
        if (open && !locked.value) load()
    },
    { immediate: true }
)
watch(
    () => props.refreshKey,
    () => {
        if (props.modelValue && !locked.value) load()
    }
)
const showOperations = () => {
    if (locked.value) return
    emit('update:modelValue', false)
    emit('show-operations')
}
onBeforeUnmount(() => {
    disposed = true
    generation += 1
})
</script>

<template>
    <BaseModal
        :model-value="modelValue"
        title="모델 작업"
        :busy="busy"
        footer-text="모델을 선택하고 실행할 작업을 확인하세요. 학습 진행률과 결과, 취소는 실행 작업 이력에서 확인합니다."
        @update:model-value="emit('update:modelValue', $event)"
    >
        <div class="grid gap-6">
            <section class="rounded-2xl border border-blue-200 bg-blue-50/60 p-5 dark:border-blue-900 dark:bg-blue-950/30">
                <div class="flex flex-wrap items-start justify-between gap-4">
                    <div class="min-w-0 flex-1">
                        <p class="text-xs font-bold text-blue-700 dark:text-blue-300">작업 대상</p>
                        <h3 class="mt-2 text-lg font-extrabold">어떤 모델로 작업할까요?</h3>
                        <p class="mt-2 text-xs leading-6 text-slate-500 dark:text-slate-400">
                            연결된 AI 분석/추론 서버의 모델 실행 환경에 설치된 모델입니다. DB에 등록한 모델 버전과는 별도입니다.
                        </p>
                    </div>
                    <BaseButton variant="secondary" size="small" :loading="loading" :disabled="locked" @click="load">설치 목록 새로고침</BaseButton>
                </div>
                <BaseSelect wrapper-class="mt-5" v-model="selectedName" label="작업할 설치 모델" :options="options" :disabled="!loaded || loading || locked" />
                <dl class="mt-4 grid gap-4 border-t border-blue-200 pt-4 text-xs dark:border-blue-900 sm:grid-cols-2">
                    <div class="min-w-0">
                        <dt class="text-slate-500 dark:text-slate-400">현재 분석 모델</dt>
                        <dd class="mt-2 break-all font-bold">{{ installed?.model || '미확인' }}</dd>
                    </div>
                    <div class="min-w-0">
                        <dt class="text-slate-500 dark:text-slate-400">이전 분석 모델</dt>
                        <dd class="mt-2 break-all font-bold">{{ installed?.previousModel || '복구 기록 없음' }}</dd>
                    </div>
                </dl>
                <p v-if="loaded && !choices.length" class="mt-4 text-sm text-slate-500">AI 서버에서 확인한 설치 모델이 없습니다.</p>
                <p v-if="error" role="alert" class="mt-4 rounded-lg bg-rose-50 p-3 text-sm leading-6 text-rose-700 dark:bg-rose-950/40 dark:text-rose-300">
                    {{ error }}
                </p>
            </section>
            <div class="grid gap-2 sm:grid-cols-3" aria-label="모델 작업 선택">
                <button
                    v-for="action in actions"
                    :key="action.key"
                    type="button"
                    :disabled="locked || loading"
                    class="rounded-xl border p-4 text-left transition disabled:cursor-not-allowed disabled:opacity-60"
                    :class="
                        activeAction === action.key
                            ? 'border-blue-500 bg-blue-50 text-blue-900 dark:border-blue-500 dark:bg-blue-950/50 dark:text-blue-100'
                            : 'border-slate-200 bg-white text-slate-700 hover:border-blue-300 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300'
                    "
                    :aria-pressed="activeAction === action.key"
                    @click="activeAction = action.key"
                >
                    <span class="block text-sm font-extrabold">{{ action.label }}</span
                    ><span class="mt-2 block text-xs">{{ action.hint }}</span>
                </button>
            </div>
            <ModelRuntimeControl
                v-show="activeAction === 'inference'"
                :snapshot="installed"
                :model-name="selectedName"
                :available="loaded && !loading"
                :can-deploy="canDeploy"
                :can-rollback="canRollback"
                :reload="load"
                @changed="emit('changed')"
                @locked="runtimeLocked = $event"
                @busy="runtimeBusy = $event"
            />
            <ModelTrainingPanel
                v-show="activeAction === 'training'"
                :model-name="trainingModelName"
                :visible="modelValue && activeAction === 'training' && loaded && !loading"
                :can-train="canTrain && loaded && !loading"
                :refresh-key="refreshKey"
                @changed="emit('changed')"
                @locked="trainingLocked = $event"
                @busy="trainingBusy = $event"
            />
            <ModelAdapterPanel
                v-show="activeAction === 'adapter'"
                :model-name="trainingModelName"
                :visible="modelValue && activeAction === 'adapter'"
                :available="loaded && !loading"
                :can-deploy="canDeploy"
                :snapshot="installed"
                :reload="load"
                :refresh-key="refreshKey"
                @changed="emit('changed')"
                @locked="adapterLocked = $event"
                @busy="adapterBusy = $event"
            />
            <div v-if="activeAction === 'training'" class="border-t border-slate-200 pt-5 dark:border-slate-700">
                <BaseButton variant="secondary" :disabled="locked" @click="showOperations">실행 작업 이력 보기</BaseButton>
            </div>
        </div>
    </BaseModal>
</template>
