<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getChangeRequests } from '@/api/appApi'
import { isAnalysisDemoMode } from '@/api/analysisApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import LiveRequirementAnalysis from '@/components/requirement/LiveRequirementAnalysis.vue'
import { useClientTable } from '@/composables/useClientTable'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const requests = ref([])
const selectedStatus = ref('ALL')
const loading = ref(false)

const statusOptions = [
    { value: 'ALL', label: '전체' },
    { value: 'REQUESTED', label: '요청' },
    { value: 'REVIEW', label: '검토중' },
    { value: 'ANALYZED', label: '분석완료' },
]

const priorityClass = {
    HIGH: 'bg-rose-50 text-rose-700 dark:bg-rose-950/50 dark:text-rose-300',
    MEDIUM: 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
    LOW: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300',
}

const columns = [
    { key: 'id', label: '요청번호', width: '150px', sortable: true },
    { key: 'title', label: '프로젝트 / 요청내용', minWidth: '360px' },
    { key: 'requester', label: '요청부서', minWidth: '160px' },
    { key: 'priority', label: '우선순위', align: 'center', width: '120px' },
    { key: 'riskScore', label: '위험점수', align: 'center', width: '110px', sortable: true },
    { key: 'status', label: '상태', align: 'center', width: '120px' },
    { key: 'createdAt', label: '요청일', align: 'center', width: '130px', sortable: true },
]

const filteredRequests = computed(() => {
    if (selectedStatus.value === 'ALL') return requests.value
    return requests.value.filter((item) => item.status === selectedStatus.value)
})

const {
    page,
    pageSize,
    sortKey,
    sortDirection,
    totalCount,
    pagedRows,
    handleSort,
    resetPage,
} = useClientTable({
    rows: filteredRequests,
    defaultPageSize: 10,
    defaultSortKey: 'createdAt',
    defaultSortDirection: 'desc',
})

const fetchRequests = async () => {
    loading.value = true

    try {
        requests.value = await getChangeRequests()
    } finally {
        loading.value = false
    }
}

watch(selectedStatus, resetPage)

// 데모 모드만 샘플 목록을 사용한다; 실제 API 모드는 LiveRequirementAnalysis가 담당한다.
onMounted(() => {
    if (isAnalysisDemoMode) fetchRequests()
})
</script>

<template>
    <div v-if="isAnalysisDemoMode">
        <ViewHeading title="변경요청 관리" description="고객 또는 업무부서가 요청한 변경사항과 AI 분석 상태를 관리합니다.">
            <template #actions>
                <BaseButton v-if="authStore.hasPermission('CHANGE_REQUEST_CREATE')" disabled>
                    데모 변경요청
                </BaseButton>
            </template>
        </ViewHeading>

        <div class="mb-4 flex items-center justify-between gap-4">
            <div class="flex flex-wrap gap-2">
                <BaseButton
                    v-for="status in statusOptions"
                    :key="status.value"
                    size="small"
                    :variant="selectedStatus === status.value ? 'primary' : 'secondary'"
                    @click="selectedStatus = status.value"
                >
                    {{ status.label }}
                </BaseButton>
            </div>

            <div class="text-xs font-semibold text-slate-500 dark:text-slate-400">
                총 {{ totalCount }}건
            </div>
        </div>

        <BaseTable
            :columns="columns"
            :rows="pagedRows"
            :loading="loading"
            :sort-key="sortKey"
            :sort-direction="sortDirection"
            clickable
            @sort-change="handleSort"
        >
            <template #cell-id="{ value }">
                <span class="font-bold text-blue-700 dark:text-blue-300">{{ value }}</span>
            </template>

            <template #cell-title="{ row }">
                <div class="font-extrabold text-slate-900 dark:text-slate-100">{{ row.project }}</div>
                <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ row.title }}</div>
            </template>

            <template #cell-priority="{ value }">
                <span class="rounded-md px-2 py-1 text-xs font-extrabold" :class="priorityClass[value]">
                    {{ value }}
                </span>
            </template>

            <template #cell-riskScore="{ value }">
                <span class="font-extrabold">{{ value ?? '-' }}</span>
            </template>

            <template #cell-status="{ value }">
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
    <LiveRequirementAnalysis v-else />
</template>
