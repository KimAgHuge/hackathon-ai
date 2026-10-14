<script setup>
import { computed, useId } from 'vue'

defineOptions({
    inheritAttrs: false,
})

const props = defineProps({
    modelValue: {
        type: [String, Number],
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
    wrapperClass: {
        type: String,
        default: '',
    },
})

const emit = defineEmits(['update:modelValue'])

const generatedId = useId()
const controlId = computed(() => props.id || props.name || generatedId)
</script>

<template>
    <div class="grid min-w-0 gap-2" :class="wrapperClass">
        <label v-if="label" :for="controlId" class="ui-label">
            {{ label }}
            <span v-if="required" class="text-rose-500">*</span>
        </label>

        <input
            v-bind="$attrs"
            :id="controlId"
            :name="name || undefined"
            :value="modelValue"
            class="ui-input"
            :class="{ 'border-rose-500 focus:border-rose-500': error }"
            :aria-invalid="Boolean(error)"
            @input="emit('update:modelValue', $event.target.value)"
        />

        <p v-if="error" class="text-xs font-medium text-rose-600 dark:text-rose-400">
            {{ error }}
        </p>
        <p v-else-if="hint" class="text-xs text-slate-500 dark:text-slate-400">
            {{ hint }}
        </p>
    </div>
</template>
