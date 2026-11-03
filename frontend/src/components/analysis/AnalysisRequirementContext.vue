<script setup>
import { computed, ref, useId, watch } from 'vue'
import BaseModal from '@/components/base/BaseModal.vue'

const props = defineProps({
    context: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    error: { type: String, default: '' },
})
const emit = defineEmits(['retry'])
const headingId = useId()
const detailsOpen = ref(false)
const text = (value) => (typeof value === 'string' ? value.trim() : '')
const original = computed(() => text(props.context?.originalContent))
const refined = computed(() => text(props.context?.refinedContent))
const latestQuestion = computed(() => text(props.context?.latestFollowUpQuestion))
const title = computed(() => text(props.context?.title))
const requirementKey = computed(() => text(props.context?.requirementKey))
const versionLabel = computed(() => {
    const version = props.context?.versionNumber
    return (typeof version === 'number' && Number.isInteger(version) && version > 0) || (typeof version === 'string' && /^[1-9]\d*$/.test(version))
        ? `v${version}`
        : ''
})

const criterionValues = (value) => {
    if (typeof value === 'string') return text(value) ? [text(value)] : []
    if (typeof value === 'number' && Number.isFinite(value)) return [String(value)]
    if (typeof value === 'boolean') return [value ? '예' : '아니요']
    if (Array.isArray(value)) return value.flatMap(criterionValues)
    if (value && typeof value === 'object') return Object.values(value).flatMap(criterionValues)
    return []
}
const criteria = computed(() =>
    (Array.isArray(props.context?.acceptanceCriteria) ? props.context.acceptanceCriteria : []).map((item) => criterionValues(item).join(' · ')).filter(Boolean)
)
const hasContent = computed(() => Boolean(title.value || original.value || refined.value || latestQuestion.value || criteria.value.length))

watch(
    () => props.context?.versionId,
    () => (detailsOpen.value = false)
)
</script>

<template>
    <div
        class="col-span-full min-w-0 rounded-xl border border-l-4 border-blue-200 border-l-blue-500 bg-blue-50/70 p-4 dark:border-blue-900 dark:border-l-blue-400 dark:bg-blue-950/30"
        :aria-busy="loading"
    >
        <dt :id="headingId" class="text-xs font-semibold text-blue-700 dark:text-blue-300">변경 요청 내용</dt>
        <dd class="m-0 mt-1.5 min-w-0" :aria-labelledby="headingId">
            <button
                v-if="hasContent"
                type="button"
                class="request-context-title max-w-full break-words text-left text-slate-900 underline-offset-4 [overflow-wrap:anywhere] hover:text-blue-700 hover:underline focus-visible:rounded focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-blue-500 dark:text-slate-100 dark:hover:text-blue-300"
                aria-haspopup="dialog"
                title="분석 요청 상세 보기"
                @click="detailsOpen = true"
            >
                {{ title || '제목이 없는 분석 요청' }}
            </button>
            <p v-else-if="loading" role="status" aria-live="polite" class="min-w-0 flex-1 text-sm text-slate-600 dark:text-slate-400">
                요청 정보를 불러오는 중입니다.
            </p>
            <p v-else class="min-w-0 flex-1 text-sm text-slate-600 dark:text-slate-400">저장된 요청 정보를 확인할 수 없습니다.</p>
            <button
                v-if="error || (!loading && !hasContent)"
                type="button"
                class="mt-1 text-xs text-blue-700 hover:underline dark:text-blue-300"
                :disabled="loading"
                @click="emit('retry')"
            >
                다시 불러오기
            </button>
            <p v-if="error" role="alert" class="mt-1 break-words text-xs leading-5 text-rose-700 [overflow-wrap:anywhere] dark:text-rose-300">{{ error }}</p>
        </dd>
    </div>

    <BaseModal v-model="detailsOpen" title="분석 요청 상세" footer-text="이 창을 닫아도 진행 중인 분석에는 영향이 없습니다.">
        <div class="grid min-w-0 gap-6">
            <div class="min-w-0">
                <p v-if="requirementKey || versionLabel" class="mb-2 flex min-w-0 flex-wrap gap-2 text-xs text-slate-500 dark:text-slate-400">
                    <span v-if="requirementKey" class="break-words [overflow-wrap:anywhere]">{{ requirementKey }}</span>
                    <span v-if="versionLabel">{{ versionLabel }}</span>
                </p>
                <h3 class="break-words text-lg font-bold leading-7 text-slate-900 [overflow-wrap:anywhere] dark:text-slate-100">
                    {{ title || '제목이 없는 분석 요청' }}
                </h3>
                <p class="mt-2 text-xs leading-5 text-slate-500 dark:text-slate-400">이 분석 작업에 연결된 요구사항 버전을 기준으로 표시합니다.</p>
            </div>
            <div v-if="latestQuestion" class="min-w-0 rounded-lg border border-blue-200 bg-blue-50 p-4 dark:border-blue-900 dark:bg-blue-950/30">
                <h4 class="text-sm font-semibold text-blue-900 dark:text-blue-200">최신 추가 질문</h4>
                <p class="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-blue-950 [overflow-wrap:anywhere] dark:text-blue-100">
                    {{ latestQuestion }}
                </p>
            </div>
            <div class="min-w-0">
                <h4 class="text-sm font-semibold text-slate-800 dark:text-slate-200">요청 원문</h4>
                <p class="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-slate-700 [overflow-wrap:anywhere] dark:text-slate-300">
                    {{ original || '저장된 요청 원문이 없습니다.' }}
                </p>
            </div>
            <div class="min-w-0">
                <h4 class="text-sm font-semibold text-slate-800 dark:text-slate-200">정리한 변경 내용</h4>
                <p class="mt-2 whitespace-pre-wrap break-words text-sm leading-6 text-slate-700 [overflow-wrap:anywhere] dark:text-slate-300">
                    {{ refined || '저장된 정리 내용이 없습니다.' }}
                </p>
            </div>
            <div class="min-w-0">
                <h4 class="text-sm font-semibold text-slate-800 dark:text-slate-200">인수 조건</h4>
                <ol v-if="criteria.length" class="mt-2 list-decimal space-y-2 pl-5 text-sm leading-6 text-slate-700 dark:text-slate-300">
                    <li v-for="(criterion, index) in criteria" :key="index" :value="index + 1" class="whitespace-pre-wrap break-words [overflow-wrap:anywhere]">
                        {{ criterion }}
                    </li>
                </ol>
                <p v-else class="mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">저장된 인수 조건이 없습니다.</p>
            </div>
        </div>
    </BaseModal>
</template>

<style scoped>
.request-context-title {
    display: -webkit-box;
    overflow: hidden;
    font-size: 16px;
    font-weight: 800;
    line-height: 1.55;
    cursor: pointer;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
}
@media (min-width: 640px) {
    .request-context-title {
        font-size: 18px;
    }
}
</style>
