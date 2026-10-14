<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getChangeRequests } from '@/api/appApi'
import { isAnalysisDemoMode } from '@/api/analysisApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseDatePicker from '@/components/base/BaseDatePicker.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import LiveRequirementAnalysis from '@/components/requirement/LiveRequirementAnalysis.vue'
import { useClientTable } from '@/composables/useClientTable'
import { matchesCreatedDateRange } from '@/lib/changeRequestFilters'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const requests = ref([])
const keyword = ref('')
const selectedProject = ref('')
const selectedStatus = ref('ALL')
const selectedPriority = ref('')
const startDate = ref('')
const endDate = ref('')
const loading = ref(false)

const statusOptions = [
    { value: 'ALL', label: '전체' },
    { value: 'REQUESTED', label: '요청' },
    { value: 'REVIEW', label: '검토중' },
    { value: 'ANALYZED', label: '분석완료' },
]
const projectOptions = computed(() => [
    { value: '', label: '전체 프로젝트' },
    ...[...new Set(requests.value.map((item) => item.project))].sort().map((project) => ({ value: project, label: project })),
])
const priorityOptions = [
    { value: '', label: '전체 우선순위' },
    { value: 'LOW', label: '낮음' },
    { value: 'MEDIUM', label: '보통' },
    { value: 'HIGH', label: '높음' },
]
const dateRangeError = computed(() => (startDate.value && endDate.value && startDate.value > endDate.value ? '종료일은 시작일 이후여야 합니다.' : ''))
const hasActiveFilters = computed(() =>
    Boolean(keyword.value || selectedProject.value || selectedStatus.value !== 'ALL' || selectedPriority.value || startDate.value || endDate.value)
)
const resetFilters = () => {
    keyword.value = ''
    selectedProject.value = ''
    selectedStatus.value = 'ALL'
    selectedPriority.value = ''
    startDate.value = ''
    endDate.value = ''
}

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
    const search = keyword.value.trim().toLocaleLowerCase('ko-KR')
    if (dateRangeError.value) return []
    return requests.value.filter((item) => {
        if (selectedProject.value && item.project !== selectedProject.value) return false
        if (selectedStatus.value !== 'ALL' && item.status !== selectedStatus.value) return false
        if (selectedPriority.value && item.priority !== selectedPriority.value) return false
        if (!matchesCreatedDateRange(item.createdAt, startDate.value, endDate.value)) return false
        if (!search) return true
        return [item.id, item.project, item.title, item.requester].some((value) =>
            String(value || '')
                .toLocaleLowerCase('ko-KR')
                .includes(search)
        )
    })
})

const { page, pageSize, sortKey, sortDirection, totalCount, pagedRows, handleSort, resetPage } = useClientTable({
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

watch([keyword, selectedProject, selectedStatus, selectedPriority, startDate, endDate], resetPage)

// 데모 모드만 샘플 목록을 사용한다; 실제 API 모드는 LiveRequirementAnalysis가 담당한다.
onMounted(() => {
    if (isAnalysisDemoMode) fetchRequests()
})
</script>

<template>
    <div v-if="isAnalysisDemoMode">
        <ViewHeading title="변경요청 관리" description="고객 또는 업무부서가 요청한 변경사항과 AI 분석 상태를 관리합니다.">
            <template #actions>
                <BaseButton v-if="authStore.hasPermission('CHANGE_REQUEST_CREATE')" class="w-full lg:w-auto" disabled> 데모 변경요청 </BaseButton>
            </template>
        </ViewHeading>

        <section class="mb-4 rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <BaseInput v-model="keyword" label="변경요청 검색" placeholder="프로젝트명, 요청번호, 제목, 요청부서 검색" />
            <div class="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <BaseSelect v-model="selectedProject" id="demo-filter-project" label="프로젝트" :options="projectOptions" />
                <BaseSelect v-model="selectedPriority" id="demo-filter-priority" label="우선순위" :options="priorityOptions" />
                <BaseDatePicker v-model="startDate" id="demo-filter-start-date" label="요청 시작일" :max="endDate" />
                <BaseDatePicker v-model="endDate" id="demo-filter-end-date" label="요청 종료일" :min="startDate" :error="dateRangeError" align="right" />
            </div>
            <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">선택한 조건은 모두 함께 적용됩니다.</p>
            <div class="mt-3 flex justify-end">
                <BaseButton class="w-full sm:w-auto" variant="secondary" size="small" :disabled="!hasActiveFilters" @click="resetFilters"
                    >검색 조건 초기화</BaseButton
                >
            </div>
        </section>

        <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
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

            <div class="text-xs font-semibold text-slate-500 dark:text-slate-400">총 {{ totalCount }}건</div>
        </div>

        <BaseTable
            :columns="columns"
            :rows="pagedRows"
            :loading="loading"
            :sort-key="sortKey"
            :sort-direction="sortDirection"
            :empty-text="hasActiveFilters ? '검색 조건에 맞는 변경요청이 없습니다.' : '등록된 변경요청이 없습니다.'"
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
                <BasePagination v-model:page="page" :page-size="pageSize" :total-count="totalCount" />
            </template>
        </BaseTable>
    </div>
    <LiveRequirementAnalysis v-else />
</template>
