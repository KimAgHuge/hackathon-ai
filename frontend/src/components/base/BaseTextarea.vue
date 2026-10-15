<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'

defineOptions({
    inheritAttrs: false,
})

const props = defineProps({
    modelValue: {
        type: String,
        default: '',
    },
    label: {
        type: String,
        default: '',
    },
    id: {
        type: String,
        default: '',
    },
    name: {
        type: String,
        default: '',
    },
    hint: {
        type: String,
        default: '',
    },
    error: {
        type: String,
        default: '',
    },
    required: {
        type: Boolean,
        default: false,
    },
    disabled: {
        type: Boolean,
        default: false,
    },
    rows: {
        type: [String, Number],
        default: 3,
    },
    maxlength: {
        type: [String, Number],
        default: null,
    },
    mode: {
        type: String,
        default: 'text',
    },
})

const emit = defineEmits(['update:modelValue'])
const generatedId = useId()
const controlId = computed(() => props.id || props.name || generatedId)
const input = ref(null)
const expanded = ref(false)
const text = computed(() => String(props.modelValue ?? ''))
const lineCount = computed(() => (text.value ? text.value.split(/\r\n|\r|\n/).length : 0))
const maxLength = computed(() => {
    const value = Number(props.maxlength)
    return Number.isFinite(value) && value > 0 ? value : null
})

// 내용이 늘면 입력창을 키우되 긴 글은 내부 스크롤로 제한한다.
const resizeInput = () => {
    if (!input.value) return
    const baseHeight = Math.max(80, Number(props.rows || 3) * 24 + 28)
    const minimum = expanded.value ? Math.max(320, baseHeight + 160) : baseHeight
    const maximum = expanded.value ? 720 : Math.max(260, minimum)
    input.value.style.height = `${minimum}px`
    const contentHeight = input.value.scrollHeight
    input.value.style.height = `${Math.min(maximum, Math.max(minimum, contentHeight))}px`
    input.value.style.overflowY = contentHeight > maximum ? 'auto' : 'hidden'
}

const updateInput = (event) => {
    emit('update:modelValue', event.target.value)
    resizeInput()
}

watch(
    () => [props.modelValue, props.rows, expanded.value],
    () => nextTick(resizeInput)
)
onMounted(() => {
    nextTick(resizeInput)
    window.addEventListener('resize', resizeInput)
})
onBeforeUnmount(() => window.removeEventListener('resize', resizeInput))
</script>

<template>
    <div class="grid min-w-0 gap-2">
        <div class="editor-shell" :class="{ 'editor-error': error, 'editor-disabled': disabled }">
            <div class="editor-toolbar">
                <label :for="controlId" class="editor-label">
                    {{ label || '내용 입력' }}
                    <span v-if="required" class="text-rose-500">*</span>
                </label>
                <button
                    type="button"
                    class="editor-expand"
                    :aria-label="expanded ? '입력창 기본 크기로 보기' : '입력창 넓게 보기'"
                    :aria-expanded="expanded"
                    :aria-controls="controlId"
                    :disabled="disabled"
                    @click="expanded = !expanded"
                >
                    {{ expanded ? '기본 크기' : '넓게 보기' }}
                </button>
            </div>
            <textarea
                v-bind="$attrs"
                :id="controlId"
                ref="input"
                :name="name || undefined"
                :value="modelValue"
                :rows="rows"
                :maxlength="maxlength ?? undefined"
                :required="required"
                :disabled="disabled"
                class="editor-input"
                :class="{ 'editor-code': mode === 'code' }"
                :aria-invalid="Boolean(error)"
                @input="updateInput"
            ></textarea>
            <div class="editor-footer">
                <span>{{ lineCount }}줄</span>
                <span v-if="maxLength">{{ text.length.toLocaleString('ko-KR') }} / {{ maxLength.toLocaleString('ko-KR') }}자</span>
            </div>
        </div>

        <p v-if="error" class="text-xs font-medium text-rose-600 dark:text-rose-400">
            {{ error }}
        </p>
        <p v-else-if="hint" class="text-xs text-slate-500 dark:text-slate-400">
            {{ hint }}
        </p>
    </div>
</template>

<style scoped>
.editor-shell {
    min-width: 0;
    overflow: hidden;
    border: 1px solid #cbd5e1;
    border-radius: 14px;
    background: #fff;
    box-shadow: 0 2px 8px rgba(15, 23, 42, 0.04);
    transition:
        border-color 160ms ease,
        box-shadow 160ms ease;
}
.editor-shell:hover {
    border-color: #94a3b8;
}
.editor-shell:focus-within {
    border-color: #3b82f6;
    box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.14);
}
.editor-error {
    border-color: #f43f5e;
}
.editor-disabled {
    opacity: 0.65;
}
.editor-toolbar,
.editor-footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    background: #f8fafc;
}
.editor-toolbar {
    min-height: 40px;
    border-bottom: 1px solid #e2e8f0;
    padding: 6px 12px 6px 16px;
}
.editor-label {
    min-width: 0;
    overflow-wrap: anywhere;
    color: #334155;
    font-size: 13px;
    font-weight: 700;
}
.editor-expand {
    flex: 0 0 auto;
    border-radius: 8px;
    padding: 5px 9px;
    color: #3155a2;
    font-size: 12px;
    font-weight: 700;
}
.editor-expand:hover:not(:disabled),
.editor-expand:focus-visible {
    background: #e8efff;
    outline: none;
}
.editor-expand:disabled {
    cursor: not-allowed;
}
.editor-input {
    display: block;
    width: 100%;
    min-width: 0;
    resize: none;
    border: 0;
    background: transparent;
    padding: 14px 16px;
    color: #0f172a;
    font: inherit;
    font-size: 14px;
    line-height: 24px;
    outline: none;
    overflow-wrap: anywhere;
}
.editor-input::placeholder {
    color: #94a3b8;
}
.editor-input.editor-code {
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12px;
}
.editor-footer {
    min-height: 30px;
    border-top: 1px solid #e2e8f0;
    padding: 5px 16px;
    color: #64748b;
    font-size: 11px;
    font-variant-numeric: tabular-nums;
}
:global(html.dark .editor-shell) {
    border-color: #475569;
    background: #0f172a;
    box-shadow: none;
}
:global(html.dark .editor-shell:hover) {
    border-color: #64748b;
}
:global(html.dark .editor-shell:focus-within) {
    border-color: #60a5fa;
    box-shadow: 0 0 0 3px rgba(96, 165, 250, 0.18);
}
:global(html.dark .editor-toolbar),
:global(html.dark .editor-footer) {
    border-color: #334155;
    background: #1e293b;
}
:global(html.dark .editor-label) {
    color: #e2e8f0;
}
:global(html.dark .editor-expand) {
    color: #bfdbfe;
}
:global(html.dark .editor-expand:hover:not(:disabled)),
:global(html.dark .editor-expand:focus-visible) {
    background: #334155;
}
:global(html.dark .editor-input) {
    color: #f1f5f9;
}
:global(html.dark .editor-input::placeholder) {
    color: #94a3b8;
}
:global(html.dark .editor-footer) {
    color: #94a3b8;
}
@media (prefers-reduced-motion: reduce) {
    .editor-shell {
        transition: none;
    }
}
</style>
