<script setup>
import { computed } from 'vue'
import BaseButton from '@/components/base/BaseButton.vue'

const props = defineProps({
    page: {
        type: Number,
        default: 0,
    },
    pageSize: {
        type: Number,
        default: 20,
    },
    totalCount: {
        type: Number,
        default: 0,
    },
})

const emit = defineEmits(['update:page', 'change'])

const pageCount = computed(() => {
    return Math.max(1, Math.ceil(props.totalCount / props.pageSize))
})

const currentDisplayPage = computed(() => Math.min(props.page + 1, pageCount.value))

const move = (nextPage) => {
    const normalized = Math.min(Math.max(nextPage, 0), pageCount.value - 1)

    if (normalized === props.page) return

    emit('update:page', normalized)
    emit('change', normalized)
}
</script>

<template>
    <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
        <div class="text-xs text-slate-500 dark:text-slate-400">총 {{ totalCount.toLocaleString() }}건</div>

        <div class="flex flex-wrap items-center gap-2">
            <BaseButton size="small" variant="secondary" :disabled="page <= 0" @click="move(page - 1)"> 이전 </BaseButton>

            <span class="min-w-20 text-center text-xs font-bold text-slate-600 dark:text-slate-300"> {{ currentDisplayPage }} / {{ pageCount }} </span>

            <BaseButton size="small" variant="secondary" :disabled="page >= pageCount - 1" @click="move(page + 1)"> 다음 </BaseButton>
        </div>
    </div>
</template>
