<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { deleteProject, getProjects, restoreProject } from '@/api/appApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { useClientTable } from '@/composables/useClientTable'
import { useAuthStore } from '@/stores/authStore'

const authStore = useAuthStore()
const projects = ref([])
const keyword = ref('')
const loading = ref(false)
const selectedProjectIds = ref([])
const showDeleted = ref(false)
const projectToDelete = ref(null)
const confirmationName = ref('')
const busyProjectId = ref('')
const errorMessage = ref('')
const notice = ref('')
let fetchRequestId = 0

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
        width: '120px',
        minWidth: '120px',
        headerClass: 'sticky right-0 z-20 border-l border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800',
        cellClass: 'sticky right-0 z-10 border-l border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900',
        visible: authStore.hasPermission('PROJECT_UPDATE') || (!showDeleted.value && authStore.hasPermission('ANALYSIS_READ')),
    },
])

const filteredProjects = computed(() => {
    const value = keyword.value.trim().toLowerCase()

    if (!value) return projects.value

    return projects.value.filter((project) => {
        return [project.id, project.name, project.stack, project.source, project.branch].some((item) => String(item).toLowerCase().includes(value))
    })
})

const { page, pageSize, sortKey, sortDirection, totalCount, pagedRows, handleSort, resetPage } = useClientTable({
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
    const requestId = ++fetchRequestId
    loading.value = true
    errorMessage.value = ''

    try {
        const rows = await getProjects({ deleted: showDeleted.value })
        if (requestId !== fetchRequestId) return
        projects.value = rows
        selectedProjectIds.value = []
        resetPage()
    } catch (error) {
        if (requestId === fetchRequestId) {
            errorMessage.value = error?.response?.data?.message || error?.message || '프로젝트 목록을 불러오지 못했습니다.'
        }
    } finally {
        if (requestId === fetchRequestId) loading.value = false
    }
}

const requestDelete = (project) => {
    projectToDelete.value = project
    confirmationName.value = ''
    errorMessage.value = ''
    notice.value = ''
}

const cancelDelete = () => {
    projectToDelete.value = null
    confirmationName.value = ''
}

const confirmDelete = async () => {
    const project = projectToDelete.value
    if (!project || confirmationName.value !== project.name) return

    busyProjectId.value = project.id
    errorMessage.value = ''
    try {
        await deleteProject(project.id)
        cancelDelete()
        notice.value = '프로젝트를 목록에서 제외했습니다. 삭제된 프로젝트 보기에서 복구할 수 있습니다.'
        await fetchProjects()
    } catch (error) {
        errorMessage.value = error?.response?.data?.message || error?.message || '프로젝트를 삭제하지 못했습니다.'
    } finally {
        busyProjectId.value = ''
    }
}

const recoverProject = async (project) => {
    busyProjectId.value = project.id
    errorMessage.value = ''
    notice.value = ''
    try {
        await restoreProject(project.id)
        notice.value = '프로젝트가 복구되었습니다.'
        await fetchProjects()
    } catch (error) {
        errorMessage.value = error?.response?.data?.message || error?.message || '프로젝트를 복구하지 못했습니다.'
    } finally {
        busyProjectId.value = ''
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
watch(showDeleted, () => {
    cancelDelete()
    notice.value = ''
    fetchProjects()
})

onMounted(fetchProjects)
</script>

<template>
    <div>
        <ViewHeading title="프로젝트 관리" description="분석 대상 프로젝트의 소스코드와 DB 연결 정보를 관리합니다.">
            <template #actions>
                <BaseButton v-if="authStore.hasPermission('PROJECT_CREATE')" class="w-full lg:w-auto" to="/projects/new"> + 프로젝트 등록 </BaseButton>
            </template>
        </ViewHeading>

        <div class="mb-4 flex flex-col gap-3 md:flex-row md:items-end md:justify-between md:gap-4">
            <BaseInput v-model="keyword" class="w-full md:max-w-md md:flex-1" label="프로젝트 검색" placeholder="프로젝트명, 코드, 기술 스택 검색" />

            <div class="flex flex-col gap-3 sm:flex-row sm:items-end">
                <BaseButton class="w-full sm:w-auto" variant="secondary" :disabled="loading || Boolean(busyProjectId)" @click="showDeleted = !showDeleted">
                    {{ showDeleted ? '일반 프로젝트 보기' : '삭제된 프로젝트 보기' }}
                </BaseButton>
                <BaseSelect v-model="pageSize" class="w-full sm:w-32" label="표시 개수" :options="pageSizeOptions" />
            </div>
        </div>

        <p v-if="errorMessage" role="alert" class="mb-4 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">{{ errorMessage }}</p>
        <p v-if="notice" role="status" class="mb-4 rounded-lg border border-blue-200 bg-blue-50 p-4 text-sm text-blue-800">{{ notice }}</p>

        <section v-if="projectToDelete" class="mb-4 rounded-xl border border-rose-200 bg-rose-50 p-4" aria-label="프로젝트 삭제 확인">
            <p class="mb-2 text-sm font-semibold text-rose-800">{{ projectToDelete.name }} 프로젝트를 목록에서 제외할까요?</p>
            <p class="mb-3 text-xs text-rose-700">
                요구사항과 분석 이력은 보존되며, 복구 전에는 새 작업을 시작할 수 없습니다. 확인하려면 프로젝트 이름을 입력하세요.
            </p>
            <div class="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-end">
                <BaseInput v-model="confirmationName" class="min-w-0 flex-1 sm:min-w-64" label="프로젝트 이름 확인" :placeholder="projectToDelete.name" />
                <BaseButton
                    class="w-full sm:w-auto"
                    variant="danger"
                    :disabled="confirmationName !== projectToDelete.name || Boolean(busyProjectId)"
                    :loading="Boolean(busyProjectId)"
                    @click="confirmDelete"
                    >삭제</BaseButton
                >
                <BaseButton class="w-full sm:w-auto" variant="secondary" :disabled="Boolean(busyProjectId)" @click="cancelDelete">취소</BaseButton>
            </div>
        </section>

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
            :empty-text="showDeleted ? '삭제된 프로젝트가 없습니다.' : '조건에 맞는 프로젝트가 없습니다.'"
            @row-click="openProject"
            @sort-change="handleSort"
        >
            <template #cell-name="{ row }">
                <div>
                    <div class="font-extrabold text-slate-900 dark:text-slate-100">
                        {{ row.name }}
                    </div>
                    <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ row.id }}</div>
                </div>
            </template>

            <template #cell-stack="{ value }">
                <span class="text-xs text-slate-600 dark:text-slate-400">{{ value }}</span>
            </template>

            <template #cell-source="{ row }">
                <div class="font-semibold text-slate-700 dark:text-slate-300">{{ row.source || row.sourceType || '—' }}</div>
                <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">{{ row.branch || row.defaultBranch || '—' }}</div>
            </template>

            <template #cell-status="{ value }">
                <span v-if="showDeleted" class="text-xs font-semibold text-rose-700">삭제됨</span>
                <StatusBadge v-else :status="value" />
            </template>

            <template #cell-updatedAt="{ value }">
                <span class="text-xs text-slate-500 dark:text-slate-400">{{ value }}</span>
            </template>

            <template #cell-actions="{ row }">
                <BaseButton
                    v-if="showDeleted && authStore.hasPermission('PROJECT_UPDATE')"
                    class="w-full"
                    size="small"
                    variant="secondary"
                    :disabled="Boolean(busyProjectId)"
                    @click="recoverProject(row)"
                    >복구</BaseButton
                >
                <div v-else class="flex flex-col gap-1">
                    <BaseButton
                        v-if="!showDeleted && authStore.hasPermission('ANALYSIS_READ')"
                        class="w-full"
                        size="small"
                        variant="secondary"
                        :to="{ name: 'analysis-history', query: { projectId: row.id } }"
                        >이력</BaseButton
                    >
                    <BaseButton
                        v-if="!showDeleted && authStore.hasPermission('PROJECT_UPDATE')"
                        class="w-full"
                        size="small"
                        variant="secondary"
                        @click="editProject(row)"
                        >수정</BaseButton
                    >
                    <BaseButton
                        v-if="!showDeleted && authStore.hasPermission('PROJECT_UPDATE')"
                        class="w-full"
                        size="small"
                        variant="danger"
                        :disabled="Boolean(busyProjectId)"
                        @click="requestDelete(row)"
                        >삭제</BaseButton
                    >
                </div>
            </template>

            <template #footer>
                <BasePagination v-model:page="page" :page-size="Number(pageSize)" :total-count="totalCount" />
            </template>
        </BaseTable>
    </div>
</template>
