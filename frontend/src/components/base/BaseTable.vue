<script setup>
import { computed } from 'vue'

const props = defineProps({
    columns: {
        type: Array,
        required: true,
    },
    rows: {
        type: Array,
        default: () => [],
    },
    rowKey: {
        type: [String, Function],
        default: 'id',
    },
    rowClass: {
        type: [String, Function],
        default: '',
    },
    loading: {
        type: Boolean,
        default: false,
    },
    emptyText: {
        type: String,
        default: '조회된 데이터가 없습니다.',
    },
    clickable: {
        type: Boolean,
        default: false,
    },
    selectable: {
        type: Boolean,
        default: false,
    },
    selectedKeys: {
        type: Array,
        default: () => [],
    },
    maxHeight: {
        type: String,
        default: '',
    },
    stickyHeader: {
        type: Boolean,
        default: false,
    },
    sortKey: {
        type: String,
        default: '',
    },
    sortDirection: {
        type: String,
        default: 'asc',
        validator: (value) => ['asc', 'desc'].includes(value),
    },
})

const emit = defineEmits([
    'row-click',
    'sort-change',
    'update:selectedKeys',
])

const visibleColumns = computed(() => {
    return props.columns.filter((column) => column.visible !== false)
})

const columnCount = computed(() => {
    return visibleColumns.value.length + (props.selectable ? 1 : 0)
})

const getRowKey = (row) => {
    if (typeof props.rowKey === 'function') return props.rowKey(row)
    return row[props.rowKey]
}

const getCellValue = (row, column) => {
    if (typeof column.value === 'function') return column.value(row)
    return row[column.key]
}

const getAlignmentClass = (align) => {
    const alignments = {
        left: 'text-left',
        center: 'text-center',
        right: 'text-right',
    }

    return alignments[align] || alignments.left
}

const getRowClass = (row) => {
    if (typeof props.rowClass === 'function') return props.rowClass(row)
    return props.rowClass
}

const isSelected = (row) => {
    return props.selectedKeys.includes(getRowKey(row))
}

const allRowsSelected = computed(() => {
    return props.rows.length > 0 && props.rows.every((row) => isSelected(row))
})

const toggleRow = (row) => {
    const key = getRowKey(row)
    const nextKeys = isSelected(row)
        ? props.selectedKeys.filter((item) => item !== key)
        : [...props.selectedKeys, key]

    emit('update:selectedKeys', nextKeys)
}

const toggleAll = () => {
    const currentRowKeys = props.rows.map(getRowKey)

    if (allRowsSelected.value) {
        emit(
            'update:selectedKeys',
            props.selectedKeys.filter((key) => !currentRowKeys.includes(key))
        )
        return
    }

    emit('update:selectedKeys', [...new Set([...props.selectedKeys, ...currentRowKeys])])
}

const handleRowClick = (row, event) => {
    if (!props.clickable) return

    const interactiveElement = event.target.closest('button, a, input, select, textarea, [role="button"]')
    if (interactiveElement) return

    emit('row-click', row)
}

const handleSort = (column) => {
    if (!column.sortable) return

    const nextDirection = props.sortKey === column.key && props.sortDirection === 'asc'
        ? 'desc'
        : 'asc'

    emit('sort-change', {
        key: column.key,
        direction: nextDirection,
    })
}
</script>

<template>
    <section class="ui-table-container">
        <div
            class="overflow-auto"
            :style="maxHeight ? { maxHeight } : undefined"
        >
            <table class="w-full border-collapse text-left">
                <thead
                    class="bg-slate-50 text-slate-500 dark:bg-slate-800/80 dark:text-slate-400"
                    :class="{ 'sticky top-0 z-10': stickyHeader }"
                >
                    <tr class="border-b border-slate-200 dark:border-slate-700">
                        <th v-if="selectable" class="w-12 px-4 py-3 text-center">
                            <input
                                type="checkbox"
                                class="h-4 w-4 rounded border-slate-300 text-blue-600"
                                :checked="allRowsSelected"
                                :disabled="rows.length === 0"
                                aria-label="현재 페이지 전체 선택"
                                @change="toggleAll"
                            />
                        </th>

                        <th
                            v-for="column in visibleColumns"
                            :key="column.key"
                            class="whitespace-nowrap px-5 py-3 text-xs font-bold"
                            :class="[
                                getAlignmentClass(column.align),
                                column.headerClass,
                            ]"
                            :style="{
                                width: column.width,
                                minWidth: column.minWidth || column.width,
                                maxWidth: column.maxWidth,
                            }"
                        >
                            <slot
                                :name="`header-${column.key}`"
                                :column="column"
                            >
                                <button
                                    v-if="column.sortable"
                                    type="button"
                                    class="inline-flex items-center gap-1 hover:text-slate-800 dark:hover:text-slate-200"
                                    @click="handleSort(column)"
                                >
                                    {{ column.label }}
                                    <span class="text-[10px]">
                                        {{ sortKey === column.key ? (sortDirection === 'asc' ? '▲' : '▼') : '↕' }}
                                    </span>
                                </button>
                                <template v-else>
                                    {{ column.label }}
                                </template>
                            </slot>
                        </th>
                    </tr>
                </thead>

                <tbody>
                    <tr v-if="loading">
                        <td :colspan="columnCount" class="px-5 py-16 text-center">
                            <div class="inline-flex items-center gap-3 text-sm text-slate-500 dark:text-slate-400">
                                <span class="h-5 w-5 animate-spin rounded-full border-2 border-blue-600 border-r-transparent"></span>
                                데이터를 불러오고 있습니다.
                            </div>
                        </td>
                    </tr>

                    <tr v-else-if="rows.length === 0">
                        <td :colspan="columnCount" class="px-5 py-16 text-center text-sm text-slate-500 dark:text-slate-400">
                            <slot name="empty">
                                {{ emptyText }}
                            </slot>
                        </td>
                    </tr>

                    <template v-else>
                        <tr
                            v-for="row in rows"
                            :key="getRowKey(row)"
                            class="border-b border-slate-100 transition-colors last:border-b-0 dark:border-slate-800"
                            :class="[
                                getRowClass(row),
                                {
                                    'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/60': clickable,
                                    'bg-blue-50 dark:bg-blue-950/30': isSelected(row),
                                },
                            ]"
                            @click="handleRowClick(row, $event)"
                        >
                            <td v-if="selectable" class="px-4 py-4 text-center">
                                <input
                                    type="checkbox"
                                    class="h-4 w-4 rounded border-slate-300 text-blue-600"
                                    :checked="isSelected(row)"
                                    :aria-label="`${getRowKey(row)} 선택`"
                                    @change="toggleRow(row)"
                                />
                            </td>

                            <td
                                v-for="column in visibleColumns"
                                :key="column.key"
                                class="px-5 py-4 text-sm text-slate-700 dark:text-slate-300"
                                :class="[
                                    getAlignmentClass(column.align),
                                    column.cellClass,
                                ]"
                            >
                                <slot
                                    :name="`cell-${column.key}`"
                                    :row="row"
                                    :column="column"
                                    :value="getCellValue(row, column)"
                                >
                                    {{ getCellValue(row, column) }}
                                </slot>
                            </td>
                        </tr>
                    </template>
                </tbody>
            </table>
        </div>

        <footer
            v-if="$slots.footer"
            class="border-t border-slate-200 px-5 py-4 dark:border-slate-700"
        >
            <slot name="footer" />
        </footer>
    </section>
</template>
