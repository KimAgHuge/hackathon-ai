<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { calendarDays, parseDateKey, toDateKey } from '@/lib/calendarDates'

const props = defineProps({
    modelValue: { type: String, default: '' },
    id: { type: String, required: true },
    label: { type: String, required: true },
    error: { type: String, default: '' },
    min: { type: String, default: '' },
    max: { type: String, default: '' },
    align: { type: String, default: 'left' },
})
const emit = defineEmits(['update:modelValue'])

const root = ref(null)
const trigger = ref(null)
const open = ref(false)
const openAbove = ref(false)
const initialDate = parseDateKey(props.modelValue) || new Date()
const visibleYear = ref(initialDate.getFullYear())
const visibleMonth = ref(initialDate.getMonth() + 1)
const selectedDate = computed(() => parseDateKey(props.modelValue))
const todayKey = toDateKey(new Date())
const days = computed(() => calendarDays(visibleYear.value, visibleMonth.value))
const monthLabel = computed(() => `${visibleYear.value}년 ${visibleMonth.value}월`)
const displayValue = computed(() => selectedDate.value
    ? new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' }).format(selectedDate.value)
    : '날짜 선택')
const weekdays = ['월', '화', '수', '목', '금', '토', '일']

const isDisabled = (key) => Boolean((props.min && key < props.min) || (props.max && key > props.max))
const moveMonth = (offset) => {
    const next = new Date(visibleYear.value, visibleMonth.value - 1 + offset, 1)
    visibleYear.value = next.getFullYear()
    visibleMonth.value = next.getMonth() + 1
}
const close = (restoreFocus = false) => {
    open.value = false
    if (restoreFocus) trigger.value?.focus()
}
const selectDate = (key) => {
    if (isDisabled(key)) return
    emit('update:modelValue', key)
    close(true)
}
const toggle = () => {
    if (!open.value) {
        const date = selectedDate.value || new Date()
        visibleYear.value = date.getFullYear()
        visibleMonth.value = date.getMonth() + 1
        const bounds = trigger.value?.getBoundingClientRect()
        openAbove.value = Boolean(bounds && window.innerHeight - bounds.bottom < 340 && bounds.top > window.innerHeight - bounds.bottom)
    }
    open.value = !open.value
}
const handleOutside = (event) => {
    if (open.value && !root.value?.contains(event.target)) close()
}

watch(() => props.modelValue, (value) => {
    const date = parseDateKey(value)
    if (date) {
        visibleYear.value = date.getFullYear()
        visibleMonth.value = date.getMonth() + 1
    }
})
onMounted(() => document.addEventListener('pointerdown', handleOutside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', handleOutside))
</script>

<template>
    <div ref="root" class="relative min-w-0" @keydown.esc.stop.prevent="close(true)">
        <label :for="id" class="ui-label mb-2 block">{{ label }}</label>
        <button
            :id="id"
            ref="trigger"
            type="button"
            class="flex h-10 w-full items-center justify-between gap-2 rounded-lg border bg-white px-3 text-left text-sm transition hover:border-brand-500 focus-visible:border-brand-500 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-brand-500/15 dark:bg-slate-950"
            :class="error ? 'border-rose-500' : 'border-slate-300 dark:border-slate-700'"
            :aria-expanded="open"
            aria-haspopup="dialog"
            :aria-invalid="Boolean(error)"
            @click="toggle"
        >
            <span class="truncate" :class="selectedDate ? 'text-slate-900 dark:text-slate-100' : 'text-slate-400'">{{ displayValue }}</span>
            <svg class="h-4 w-4 shrink-0 text-brand-600 dark:text-brand-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <rect x="3" y="5" width="18" height="16" rx="2" />
                <path d="M7 3v4M17 3v4M3 10h18" />
            </svg>
        </button>
        <p v-if="error" class="mt-2 text-xs font-medium text-rose-600 dark:text-rose-400">{{ error }}</p>

        <div
            v-if="open"
            class="absolute z-50 w-80 max-h-[calc(100dvh-2rem)] max-w-[calc(100vw-2rem)] overflow-y-auto rounded-2xl border border-slate-200 bg-white p-4 shadow-2xl shadow-slate-900/15 dark:border-slate-700 dark:bg-slate-900"
            :class="[align === 'right' ? 'right-0' : 'left-0', openAbove ? 'bottom-full mb-2' : 'top-full mt-2']"
            role="dialog"
            :aria-label="`${label} 달력`"
        >
            <div class="mb-3 flex items-center justify-between">
                <button type="button" class="grid h-8 w-8 place-items-center rounded-lg text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800" aria-label="이전 달" @click="moveMonth(-1)">‹</button>
                <strong class="text-sm text-slate-900 dark:text-slate-100">{{ monthLabel }}</strong>
                <button type="button" class="grid h-8 w-8 place-items-center rounded-lg text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800" aria-label="다음 달" @click="moveMonth(1)">›</button>
            </div>
            <div class="calendar-days mb-1">
                <span v-for="weekday in weekdays" :key="weekday" class="py-1 text-center text-[11px] font-semibold text-slate-500 dark:text-slate-400">{{ weekday }}</span>
            </div>
            <div class="calendar-days">
                <button
                    v-for="day in days"
                    :key="day.key"
                    type="button"
                    class="grid h-9 w-9 place-items-center rounded-lg text-xs font-medium transition hover:bg-brand-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:hover:bg-brand-950/50"
                    :class="[
                        day.key === modelValue ? 'bg-brand-700 font-bold text-white hover:bg-brand-800 dark:bg-brand-600' : day.key === todayKey ? 'border border-brand-500 text-brand-700 dark:text-brand-300' : day.currentMonth ? 'text-slate-700 dark:text-slate-200' : 'text-slate-400',
                        isDisabled(day.key) ? 'cursor-not-allowed opacity-30' : '',
                    ]"
                    :disabled="isDisabled(day.key)"
                    :aria-label="day.key"
                    :aria-pressed="day.key === modelValue"
                    @click="selectDate(day.key)"
                >{{ day.day }}</button>
            </div>
            <div class="mt-3 flex items-center justify-between border-t border-slate-100 pt-3 dark:border-slate-700">
                <button type="button" class="rounded-lg px-2 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800" @click="emit('update:modelValue', ''); close(true)">초기화</button>
                <button type="button" class="rounded-lg px-3 py-1.5 text-xs font-bold text-brand-700 hover:bg-brand-50 disabled:opacity-40 dark:text-brand-300 dark:hover:bg-brand-950/50" :disabled="isDisabled(todayKey)" @click="selectDate(todayKey)">오늘</button>
            </div>
        </div>
    </div>
</template>

<style scoped>
.calendar-days {
    display: grid;
    grid-template-columns: repeat(7, minmax(0, 1fr));
    justify-items: center;
    gap: 4px;
}
</style>
