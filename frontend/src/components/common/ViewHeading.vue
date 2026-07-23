<script setup>
defineProps({
    title: {
        type: String,
        required: true,
    },
    description: {
        type: String,
        default: '',
    },
})
</script>

<template>
    <div class="mb-6 flex items-start justify-between gap-5">
        <div>
            <h1 class="text-[26px] font-extrabold tracking-[-0.035em] text-slate-900 dark:text-slate-100">{{ title }}</h1>
            <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">{{ description }}</p>
        </div>
        <div>
            <slot name="actions"></slot>
        </div>
    </div>
</template>
