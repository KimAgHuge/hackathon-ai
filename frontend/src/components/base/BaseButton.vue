<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

const props = defineProps({
    variant: {
        type: String,
        default: 'primary',
        validator: (value) => ['primary', 'secondary', 'danger', 'ghost'].includes(value),
    },
    size: {
        type: String,
        default: 'medium',
        validator: (value) => ['small', 'medium', 'large'].includes(value),
    },
    type: {
        type: String,
        default: 'button',
    },
    loading: {
        type: Boolean,
        default: false,
    },
    disabled: {
        type: Boolean,
        default: false,
    },
    block: {
        type: Boolean,
        default: false,
    },
    to: {
        type: [String, Object],
        default: null,
    },
    href: {
        type: String,
        default: '',
    },
})

const componentType = computed(() => {
    if (props.to) return RouterLink
    if (props.href) return 'a'
    return 'button'
})

const variantClass = computed(() => {
    const variants = {
        primary: 'ui-button-primary',
        secondary: 'ui-button-secondary',
        danger: 'ui-button-danger',
        ghost: 'ui-button-ghost',
    }

    return variants[props.variant]
})

const sizeClass = computed(() => {
    const sizes = {
        small: 'min-h-8 px-3 text-xs',
        medium: 'min-h-10 px-4 text-sm',
        large: 'min-h-12 px-5 text-sm',
    }

    return sizes[props.size]
})
</script>

<template>
    <component
        :is="componentType"
        :to="to"
        :href="href || undefined"
        :type="componentType === 'button' ? type : undefined"
        :disabled="componentType === 'button' ? disabled || loading : undefined"
        class="ui-button max-w-full whitespace-nowrap text-center"
        :class="[
            variantClass,
            sizeClass,
            {
                'w-full': block,
                'pointer-events-none opacity-60': componentType !== 'button' && (disabled || loading),
            },
        ]"
    >
        <span
            v-if="loading"
            class="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent"
            aria-hidden="true"
        ></span>

        <slot />
    </component>
</template>
