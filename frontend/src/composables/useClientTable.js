import { computed, ref, unref, watch } from 'vue'

export const useClientTable = ({
    rows,
    defaultPageSize = 5,
    defaultSortKey = '',
    defaultSortDirection = 'asc',
} = {}) => {
    const page = ref(0)
    const pageSize = ref(defaultPageSize)
    const sortKey = ref(defaultSortKey)
    const sortDirection = ref(defaultSortDirection)

    const sourceRows = computed(() => unref(rows) || [])

    const sortedRows = computed(() => {
        const list = [...sourceRows.value]

        if (!sortKey.value) return list

        return list.sort((left, right) => {
            const leftValue = left?.[sortKey.value]
            const rightValue = right?.[sortKey.value]

            if (leftValue === rightValue) return 0
            if (leftValue == null) return 1
            if (rightValue == null) return -1

            const result = String(leftValue).localeCompare(String(rightValue), 'ko', {
                numeric: true,
                sensitivity: 'base',
            })

            return sortDirection.value === 'asc' ? result : -result
        })
    })

    const totalCount = computed(() => sortedRows.value.length)

    const pageCount = computed(() => {
        return Math.max(1, Math.ceil(totalCount.value / pageSize.value))
    })

    const pagedRows = computed(() => {
        const start = page.value * pageSize.value
        return sortedRows.value.slice(start, start + pageSize.value)
    })

    const handleSort = ({ key, direction }) => {
        sortKey.value = key
        sortDirection.value = direction
        page.value = 0
    }

    const resetPage = () => {
        page.value = 0
    }

    watch([totalCount, pageSize], () => {
        if (page.value >= pageCount.value) {
            page.value = pageCount.value - 1
        }
    })

    return {
        page,
        pageSize,
        sortKey,
        sortDirection,
        totalCount,
        pageCount,
        pagedRows,
        handleSort,
        resetPage,
    }
}
