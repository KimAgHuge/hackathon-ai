<script setup>
import { onMounted, ref } from 'vue'
import { getAnalysisHistory } from '@/api/appApi'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { useClientTable } from '@/composables/useClientTable'

const history = ref([])
const loading = ref(false)

const riskClass = {
    HIGH: 'text-rose-700 dark:text-rose-300',
    MEDIUM: 'text-amber-700 dark:text-amber-300',
    LOW: 'text-emerald-700 dark:text-emerald-300',
}

const columns = [
    { key: 'id', label: '분석번호', width: '150px', sortable: true },
    { key: 'project', label: '프로젝트', minWidth: '230px', sortable: true },
    { key: 'model', label: '적용 모델', minWidth: '220px' },
    { key: 'risk', label: '위험도', align: 'center', width: '130px', sortable: true },
    { key: 'duration', label: '소요시간', align: 'center', width: '110px' },
    { key: 'reviewer', label: '검토자', align: 'center', width: '110px' },
    { key: 'result', label: '결과', align: 'center', width: '120px' },
    { key: 'analyzedAt', label: '분석일시', align: 'center', width: '170px', sortable: true },
]

const {
    page,
    pageSize,
    sortKey,
    sortDirection,
    totalCount,
    pagedRows,
    handleSort,
} = useClientTable({
    rows: history,
    defaultPageSize: 10,
    defaultSortKey: 'analyzedAt',
    defaultSortDirection: 'desc',
})

const fetchHistory = async () => {
    loading.value = true

    try {
        history.value = await getAnalysisHistory()
    } finally {
        loading.value = false
    }
}

onMounted(fetchHistory)
</script>

<template>
    <div>
        <ViewHeading title="분석 이력" description="AI 분석 결과, 적용 모델, 검토 및 승인 이력을 확인합니다." />

        <BaseTable
            :columns="columns"
            :rows="pagedRows"
            :loading="loading"
            :sort-key="sortKey"
            :sort-direction="sortDirection"
            @sort-change="handleSort"
        >
            <template #cell-id="{ value }">
                <span class="font-bold text-blue-700 dark:text-blue-300">{{ value }}</span>
            </template>

            <template #cell-project="{ value }">
                <span class="font-extrabold text-slate-900 dark:text-slate-100">{{ value }}</span>
            </template>

            <template #cell-model="{ value }">
                <code class="rounded bg-slate-100 px-2 py-1 text-[11px] dark:bg-slate-800">{{ value }}</code>
            </template>

            <template #cell-risk="{ row }">
                <span class="font-extrabold" :class="riskClass[row.risk]">
                    {{ row.risk }} · {{ row.score }}
                </span>
            </template>

            <template #cell-result="{ value }">
                <StatusBadge :status="value" />
            </template>

            <template #footer>
                <BasePagination
                    v-model:page="page"
                    :page-size="pageSize"
                    :total-count="totalCount"
                />
            </template>
        </BaseTable>
    </div>
</template>
