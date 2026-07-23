<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getProjects } from '@/api/appApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { useClientTable } from '@/composables/useClientTable'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const authStore = useAuthStore()
const projects = ref([])
const keyword = ref('')
const loading = ref(false)
const selectedProjectIds = ref([])

const columns = computed(() => [
    {
        key: 'name',
        label: '프로젝트',
        minWidth: '260px',
        sortable: true,
    },
    {
        key: 'stack',
        label: '기술 스택',
        minWidth: '240px',
    },
    {
        key: 'source',
        label: '소스 / 브랜치',
        minWidth: '180px',
    },
    {
        key: 'status',
        label: '상태',
        align: 'center',
        width: '120px',
    },
    {
        key: 'updatedAt',
        label: '최근 변경',
        align: 'center',
        width: '170px',
        sortable: true,
    },
    {
        key: 'actions',
        label: '관리',
        align: 'center',
        width: '110px',
        visible: authStore.hasPermission('PROJECT_UPDATE'),
    },
])

const filteredProjects = computed(() => {
    const value = keyword.value.trim().toLowerCase()

    if (!value) return projects.value

    return projects.value.filter((project) => {
        return [project.id, project.name, project.stack, project.source, project.branch]
            .some((item) => String(item).toLowerCase().includes(value))
    })
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
    rows: filteredProjects,
    defaultPageSize: 5,
    defaultSortKey: 'updatedAt',
    defaultSortDirection: 'desc',
})

const pageSizeOptions = [
    { label: '5개씩', value: 5 },
    { label: '10개씩', value: 10 },
    { label: '20개씩', value: 20 },
]

const fetchProjects = async () => {
    loading.value = true

    try {
        projects.value = await getProjects()
    } finally {
        loading.value = false
    }
}

const openProject = (project) => {
    console.log('프로젝트 상세', project)
}

const editProject = (project) => {
    console.log('프로젝트 수정', project)
}

watch(keyword, resetPage)
watch(pageSize, resetPage)

onMounted(fetchProjects)
</script>

<template>
    <div>
        <ViewHeading title="프로젝트 관리" description="분석 대상 프로젝트의 소스코드와 DB 연결 정보를 관리합니다.">
            <template #actions>
                <BaseButton
                    v-if="authStore.hasPermission('PROJECT_CREATE')"
                    to="/projects/new"
                >
                    + 프로젝트 등록
                </BaseButton>
            </template>
        </ViewHeading>

        <div class="mb-4 flex items-end justify-between gap-4">
            <BaseInput
                v-model="keyword"
                class="max-w-md"
                label="프로젝트 검색"
                placeholder="프로젝트명, 코드, 기술 스택 검색"
            />

            <BaseSelect
                v-model="pageSize"
                class="w-32"
                label="표시 개수"
                :options="pageSizeOptions"
            />
        </div>

        <BaseTable
            v-model:selected-keys="selectedProjectIds"
            :columns="columns"
            :rows="pagedRows"
            :loading="loading"
            :sort-key="sortKey"
            :sort-direction="sortDirection"
            selectable
            clickable
            sticky-header
            max-height="620px"
            empty-text="조건에 맞는 프로젝트가 없습니다."
            @row-click="openProject"
            @sort-change="handleSort"
        >
            <template #cell-name="{ row }">
                <div>
                    <div class="font-extrabold text-slate-900 dark:text-slate-100">
                        {{ row.name }}
                    </div>
                    <div class="mt-1 text-xs text-slate-400">{{ row.id }}</div>
                </div>
            </template>

            <template #cell-stack="{ value }">
                <span class="text-xs text-slate-600 dark:text-slate-400">{{ value }}</span>
            </template>

            <template #cell-source="{ row }">
                <div class="font-semibold text-slate-700 dark:text-slate-300">{{ row.source }}</div>
                <div class="mt-1 text-xs text-slate-400">{{ row.branch }}</div>
            </template>

            <template #cell-status="{ value }">
                <StatusBadge :status="value" />
            </template>

            <template #cell-updatedAt="{ value }">
                <span class="text-xs text-slate-500 dark:text-slate-400">{{ value }}</span>
            </template>

            <template #cell-actions="{ row }">
                <BaseButton size="small" variant="secondary" @click="editProject(row)">
                    수정
                </BaseButton>
            </template>

            <template #footer>
                <BasePagination
                    v-model:page="page"
                    :page-size="Number(pageSize)"
                    :total-count="totalCount"
                />
            </template>
        </BaseTable>
    </div>
</template>
