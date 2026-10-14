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
    <div class="mb-6 flex min-w-0 flex-col gap-4 lg:flex-row lg:items-center lg:justify-between lg:gap-5">
        <div class="min-w-0">
            <h1 class="text-[26px] font-extrabold tracking-[-0.035em] text-slate-900 dark:text-slate-100">{{ title }}</h1>
            <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">{{ description }}</p>
        </div>
        <div class="flex w-full shrink-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center lg:w-auto lg:justify-end">
            <slot name="actions"></slot>
        </div>
    </div>
</template>
