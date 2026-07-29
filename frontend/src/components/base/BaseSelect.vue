<script setup>
import { computed } from 'vue'

defineOptions({
    inheritAttrs: false,
})

const props = defineProps({
    modelValue: {
        type: [String, Number, Boolean],
        default: '',
    },
    options: {
        type: Array,
        default: () => [],
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
    optionLabel: {
        type: String,
        default: 'label',
    },
    optionValue: {
        type: String,
        default: 'value',
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
})

const emit = defineEmits(['update:modelValue'])

const controlId = computed(() => props.id || props.name || undefined)

const getOptionLabel = (option) => {
    if (typeof option === 'object') return option[props.optionLabel]
    return option
}

const getOptionValue = (option) => {
    if (typeof option === 'object') return option[props.optionValue]
    return option
}

const handleChange = (event) => {
    const rawValue = event.target.value
    const value = typeof props.modelValue === 'number' ? Number(rawValue) : rawValue

    emit('update:modelValue', value)
}
</script>

<template>
    <div class="grid gap-2">
        <label v-if="label" :for="controlId" class="ui-label">
            {{ label }}
            <span v-if="required" class="text-rose-500">*</span>
        </label>

        <select
            v-bind="$attrs"
            :id="controlId"
            :name="name || undefined"
            :value="modelValue"
            class="ui-input"
            :class="{ 'border-rose-500 focus:border-rose-500': error }"
            :aria-invalid="Boolean(error)"
            @change="handleChange"
        >
            <slot name="before-options" />
            <option v-for="option in options" :key="String(getOptionValue(option))" :value="getOptionValue(option)" :disabled="Boolean(option?.disabled)">
                {{ getOptionLabel(option) }}
            </option>
        </select>

        <p v-if="error" class="text-xs font-medium text-rose-600 dark:text-rose-400">
            {{ error }}
        </p>
        <p v-else-if="hint" class="text-xs text-slate-400">
            {{ hint }}
        </p>
    </div>
</template>
