<script setup>
import { computed, ref } from 'vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import { readTrainingDraft, trainingDraftIssues, trainingDraftValue, updateTrainingDraftField } from '@/lib/trainingDraftCompletion'

const props = defineProps({
    modelValue: { type: String, required: true },
    autoFilled: { type: Array, default: () => [] },
    busy: { type: Boolean, default: false },
    notice: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'complete'])
const editError = ref('')
const parsed = computed(() => {
    try {
        return readTrainingDraft(props.modelValue)
    } catch {
        return null
    }
})
const issues = computed(() => trainingDraftIssues(props.modelValue))
const field = (path) =>
    computed({
        get: () => trainingDraftValue(parsed.value, path) || '',
        set: (value) => {
            try {
                emit('update:modelValue', JSON.stringify(updateTrainingDraftField(props.modelValue, path, value), null, 2))
                editError.value = ''
            } catch (error) {
                editError.value = error.message
            }
        },
    })
const capturedAt = field('snapshot.databaseCapturedAt')
const relevance = field('answers.general.databaseRelevance')
const relevanceReason = field('answers.general.databaseRelevanceReason')
const options = [
    { value: '', label: 'DB 관련성 선택' },
    { value: 'RELATED', label: '관련 있음' },
    { value: 'UNRELATED', label: '관련 없음' },
    { value: 'UNCERTAIN', label: '판단 유보 · 추가 확인 필요' },
]
</script>

<template>
    <section
        class="grid min-w-0 gap-4 rounded-xl border border-blue-200 bg-blue-50/50 p-4 dark:border-blue-900 dark:bg-blue-950/20"
        aria-label="학습 사례 누락 정보 보완"
    >
        <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div class="min-w-0">
                <h4 class="font-semibold text-slate-900 dark:text-slate-100">누락 정보 보완</h4>
                <p class="mt-1 text-sm text-slate-600 dark:text-slate-300">
                    저장된 기록으로 확인되는 값만 채웁니다. 기존 편집값은 덮어쓰지 않으며 AI를 다시 호출하지 않습니다.
                </p>
            </div>
            <button type="button" class="button secondary w-full shrink-0 sm:w-auto" :disabled="busy" @click="emit('complete')">
                저장된 근거로 빈 항목 채우기
            </button>
        </div>
        <div v-if="autoFilled.length" class="rounded-lg bg-white/80 p-3 text-sm text-slate-700 dark:bg-slate-900/70 dark:text-slate-200">
            <p class="mb-2 font-medium">자동 보완 출처 · 확인이 필요합니다</p>
            <ul class="list-disc space-y-1 break-words pl-5">
                <li v-for="item in autoFilled" :key="item">{{ item }}</li>
            </ul>
        </div>
        <p v-if="notice" class="break-words text-sm text-blue-700 dark:text-blue-300" role="status">{{ notice }}</p>
        <div class="grid min-w-0 gap-4 lg:grid-cols-2">
            <BaseInput
                v-model="capturedAt"
                label="DB 구조 수집 시각"
                :disabled="busy || !parsed"
                placeholder="당시 수집 기록의 시간대 포함 시각"
                hint="예: 2026-10-16T04:50:10Z. 현재 시각이나 승인 시각으로 대신 채우지 마세요."
            />
            <BaseSelect
                v-model="relevance"
                label="DB 관련성"
                :options="options"
                :disabled="busy || !parsed"
                hint="자동 후보도 실제 요구사항과 승인된 DB 판단에 맞는지 확인해 주세요."
            />
        </div>
        <BaseTextarea
            v-model="relevanceReason"
            label="DB 관련성 판단 이유"
            rows="3"
            :disabled="busy || !parsed"
            placeholder="실제 요구사항과 수집된 DB 근거를 바탕으로 판단 이유를 적어 주세요."
        />
        <div
            v-if="issues.length"
            class="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200"
            role="status"
        >
            <p class="mb-2 font-medium">전송 전 보완할 항목 {{ issues.length }}개</p>
            <ul class="list-disc space-y-1 pl-5">
                <li v-for="item in issues" :key="item">{{ item }}</li>
            </ul>
        </div>
        <p v-else class="text-sm text-emerald-700 dark:text-emerald-300" role="status">
            주요 누락값 확인을 통과했습니다. 전체 정답·근거와 비식별화 검토 후 확인란을 선택해 주세요. 최종 형식은 서버에서 검사합니다.
        </p>
        <p v-if="editError" class="text-sm text-rose-700 dark:text-rose-300" role="alert">{{ editError }}</p>
    </section>
</template>
