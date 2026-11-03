<script setup>
defineProps({
    modelValue: { type: Boolean, default: true },
    thinkingEffort: { type: String, default: 'medium' },
    disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'update:thinkingEffort'])
</script>

<template>
    <div
        class="grid gap-4 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900 sm:grid-cols-[1fr_auto] sm:items-start"
        :class="{ 'opacity-60': disabled }"
    >
        <label class="flex cursor-pointer items-start gap-3" :class="{ 'cursor-not-allowed': disabled }">
            <input
                type="checkbox"
                class="mt-1 h-4 w-4 shrink-0 accent-blue-600"
                :checked="modelValue"
                :disabled="disabled"
                @change="emit('update:modelValue', $event.target.checked)"
            />
            <span>
                <strong class="block text-sm text-slate-900 dark:text-slate-100"
                    >추론 모드 사용 <span class="font-normal text-slate-500">(기본 켜짐)</span></strong
                >
                <span class="mt-1 block text-xs leading-5 text-slate-600 dark:text-slate-400">지원하는 모델에서 선택한 강도로 추론합니다.</span>
            </span>
        </label>
        <div class="grid gap-2 sm:max-w-56">
            <label class="grid gap-2 text-sm font-medium text-slate-900 dark:text-slate-100">
                추론 강도
                <select
                    class="ui-input w-full disabled:cursor-not-allowed disabled:opacity-60"
                    :value="thinkingEffort"
                    :disabled="disabled || !modelValue"
                    @change="emit('update:thinkingEffort', $event.target.value)"
                >
                    <option value="low">낮음</option>
                    <option value="medium">보통 (기본)</option>
                    <option value="high">높음</option>
                </select>
            </label>
            <p class="text-xs leading-5 text-slate-600 dark:text-slate-400">높음은 응답 시간이 더 길어질 수 있습니다.</p>
        </div>
    </div>
</template>
