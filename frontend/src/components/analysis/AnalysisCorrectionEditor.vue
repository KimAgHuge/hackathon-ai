<script setup>
import { computed, ref } from 'vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import AnalysisJsonGuide from './AnalysisJsonGuide.vue'
import {
    analysisCorrectionIssues,
    correctionFields,
    parseAnalysisCorrection,
    updateAnalysisCorrection,
    updateAnalysisCorrectionItem,
} from '@/lib/analysisCorrection'

const props = defineProps({ modelValue: { type: String, required: true }, disabled: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue'])
const selected = ref('recommendations')
const editError = ref('')
const field = computed(() => correctionFields.find((item) => item.value === selected.value))
const options = correctionFields.map((item) => ({ value: item.value, label: item.label }))
const parsed = computed(() => {
    try {
        return parseAnalysisCorrection(props.modelValue)
    } catch {
        return null
    }
})
const issues = computed(() => analysisCorrectionIssues(props.modelValue))
const items = computed(() => (Array.isArray(parsed.value?.[selected.value]) ? parsed.value[selected.value] : []))
const fieldAvailable = computed(
    () =>
        parsed.value &&
        Object.hasOwn(parsed.value, selected.value) &&
        (field.value.kind === 'list'
            ? Array.isArray(parsed.value[selected.value]) && parsed.value[selected.value].every((item) => typeof item === 'string')
            : typeof parsed.value[selected.value] === 'string')
)
const update = (value, index = null) => {
    if (props.disabled) return
    editError.value = ''
    try {
        emit(
            'update:modelValue',
            index === null
                ? updateAnalysisCorrection(props.modelValue, selected.value, value)
                : updateAnalysisCorrectionItem(props.modelValue, selected.value, index, value)
        )
    } catch (error) {
        editError.value = error.message
    }
}
</script>

<template>
    <section class="grid min-w-0 gap-5" aria-label="분석 결과 항목별 수정">
        <div class="rounded-xl border border-blue-100 bg-blue-50/60 p-4 dark:border-blue-900 dark:bg-blue-950/20">
            <h4 class="text-sm font-semibold text-slate-900 dark:text-slate-100">수정할 항목을 선택하세요</h4>
            <p class="mt-2 text-sm leading-relaxed text-slate-700 dark:text-slate-300">
                수정할 항목을 고른 뒤 잘못된 문장만 바꾸세요. 입력한 내용은 전체 JSON에 자동 반영되며, 나머지 항목은 유지됩니다. 새로 전부 작성할 필요는
                없습니다.
            </p>
            <p class="mt-2 text-xs leading-relaxed text-slate-600 dark:text-slate-300">
                예: 권장 대응 방안의 두 번째 문장은 ‘권장 대응 방안 2’에서 수정합니다. JSON 위치는 recommendations[1]입니다.
            </p>
        </div>
        <AnalysisJsonGuide />
        <BaseSelect v-model="selected" label="수정할 항목" :options="options" :disabled="disabled" />
        <p class="-mt-2 text-sm leading-relaxed text-slate-600 dark:text-slate-300">{{ field.hint }}</p>
        <template v-if="fieldAvailable">
            <template v-if="field.kind === 'list'">
                <div
                    v-for="(item, index) in items"
                    :key="`${selected}:${index}`"
                    class="grid min-w-0 gap-2 rounded-xl border border-slate-200 p-4 dark:border-slate-700"
                >
                    <BaseTextarea
                        :model-value="item"
                        :label="`${field.label} ${index + 1}`"
                        rows="3"
                        maxlength="10000"
                        :disabled="disabled"
                        :hint="`JSON 위치: ${selected}[${index}]`"
                        @update:model-value="update($event, index)"
                    />
                    <button
                        type="button"
                        class="button secondary w-full justify-self-end sm:w-auto"
                        :disabled="disabled"
                        :aria-label="`${field.label} ${index + 1} 삭제`"
                        @click="update(items.filter((_, position) => position !== index))"
                    >
                        이 항목 삭제
                    </button>
                </div>
                <p v-if="!items.length" class="text-sm text-slate-500 dark:text-slate-400">등록된 항목이 없습니다. 필요한 경우에만 항목을 추가하세요.</p>
                <button type="button" class="button secondary w-full justify-self-start sm:w-auto" :disabled="disabled" @click="update([...items, ''])">
                    {{ field.label }} 추가
                </button>
            </template>
            <BaseTextarea
                v-else
                :model-value="parsed[selected]"
                :label="field.label"
                rows="3"
                :maxlength="field.maxLength"
                :disabled="disabled"
                :hint="`JSON 위치: ${selected}`"
                @update:model-value="update($event)"
            />
        </template>
        <p v-else class="rounded-lg bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
            현재 데이터에서 이 항목을 읽을 수 없습니다. 아래 전체 JSON에서 문법과 항목 형식을 확인해 주세요.
        </p>
        <p v-if="editError" role="alert" class="text-sm text-rose-700 dark:text-rose-300">{{ editError }}</p>
        <div
            v-if="issues.length"
            class="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-200"
            role="status"
        >
            <p class="font-semibold">승인 전 확인할 항목</p>
            <ul class="mt-2 list-disc space-y-1 pl-5">
                <li v-for="issue in issues" :key="issue">{{ issue }}</li>
            </ul>
        </div>
        <details :open="!parsed" class="min-w-0 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
            <summary class="cursor-pointer text-sm font-semibold text-slate-800 dark:text-slate-100">전체 JSON 직접 수정 (고급)</summary>
            <p class="my-3 text-xs leading-relaxed text-slate-600 dark:text-slate-300">
                위험도·DB 판단·분석 근거처럼 연결된 항목은 작성 안내를 확인하고 함께 검토하세요. 이 영역과 항목별 입력은 같은 데이터를 편집합니다.
            </p>
            <BaseTextarea
                id="review-corrected-result"
                :model-value="modelValue"
                label="수정 결과 JSON"
                mode="code"
                rows="15"
                maxlength="200000"
                spellcheck="false"
                :disabled="disabled"
                @update:model-value="emit('update:modelValue', $event)"
            />
        </details>
    </section>
</template>
