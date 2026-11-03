<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAnalysisHistory, getProjects } from '@/api/appApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { riskLevelLabel } from '@/lib/riskLevel'
import { analysisThinkingLabel, analysisThinkingEffortLabel } from '@/lib/analysisThinking'

const route = useRoute()
const router = useRouter()
const history = ref([])
const loading = ref(false)
const error = ref('')
const projects = ref([])
const projectsLoading = ref(false)
const projectsLoaded = ref(false)
const projectsError = ref('')
const page = ref(0)
const pageSize = 10
const totalCount = ref(0)
let historyRequestId = 0

const selectedProjectId = computed(() => (typeof route.query.projectId === 'string' ? route.query.projectId : ''))
const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value))
const projectOptions = computed(() => [
    { value: '', label: '프로젝트를 선택하세요', disabled: true },
    ...projects.value.map((project) => ({
        value: project.id,
        label: `${project.name} (${project.id.slice(0, 8)})`,
    })),
])

const columns = [
    { key: 'requirementTitle', label: '분석 대상', minWidth: '190px' },
    { key: 'status', label: '상태', width: '110px' },
    { key: 'thinkingEnabled', label: '추론 모드', width: '115px' },
    { key: 'thinkingEffort', label: '추론 강도', width: '115px' },
    { key: 'riskLevel', label: '위험도', width: '110px' },
    { key: 'reviewStatus', label: '검토', width: '100px' },
    { key: 'evidenceReusedFromJobId', label: '근거', width: '100px' },
    { key: 'createdAt', label: '요청 시각', width: '180px' },
    {
        key: 'open',
        label: '분석 확인',
        width: '140px',
        headerClass: 'sticky right-0 z-20 border-l border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800',
        cellClass: 'sticky right-0 z-10 border-l border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900',
    },
]

const formatDate = (value) => {
    if (!value) return '—'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('ko-KR')
}

const fetchHistory = async () => {
    const requestId = ++historyRequestId
    if (!projectsLoaded.value || !selectedProject.value) {
        history.value = []
        totalCount.value = 0
        loading.value = false
        return
    }
    loading.value = true
    error.value = ''
    history.value = []
    totalCount.value = 0
    try {
        const result = await getAnalysisHistory({ page: page.value, size: pageSize, projectId: selectedProjectId.value })
        if (requestId !== historyRequestId) return
        history.value = result.items || []
        totalCount.value = result.total || 0
    } catch (failure) {
        if (requestId !== historyRequestId) return
        history.value = []
        totalCount.value = 0
        error.value = failure.response?.data?.message || failure.message || '분석 이력을 불러오지 못했습니다.'
    } finally {
        if (requestId === historyRequestId) loading.value = false
    }
}

const loadProjects = async () => {
    projectsLoading.value = true
    projectsError.value = ''
    try {
        projects.value = await getProjects()
        projectsLoaded.value = true
        if (!selectedProjectId.value && projects.value.length) {
            await router.replace({ name: 'analysis-history', query: { ...route.query, projectId: projects.value[0].id } })
        } else {
            fetchHistory()
        }
    } catch (failure) {
        projectsError.value = failure.response?.data?.message || failure.message || '프로젝트 목록을 불러오지 못했습니다.'
    } finally {
        projectsLoading.value = false
    }
}

const selectProject = (projectId) => {
    if (!projects.value.some((project) => project.id === projectId)) return
    router.push({ name: 'analysis-history', query: { ...route.query, projectId } })
}

watch(page, fetchHistory)
watch(
    () => route.query.projectId,
    () => {
        if (page.value === 0) fetchHistory()
        else page.value = 0
    }
)
onMounted(loadProjects)
</script>

<template>
    <div>
        <ViewHeading title="분석 이력" description="프로젝트별 저장 분석을 다시 엽니다. 조회만으로 AI 분석을 다시 실행하지 않습니다." />
        <div class="mb-4 max-w-lg">
            <BaseSelect
                :model-value="selectedProject ? selectedProjectId : ''"
                label="프로젝트 선택"
                :options="projectOptions"
                :disabled="projectsLoading || !projects.length"
                @update:model-value="selectProject"
            />
        </div>
        <p v-if="projectsError" role="alert" class="mb-4 text-sm text-red-700">
            {{ projectsError }}
            <button type="button" class="underline" @click="loadProjects">다시 시도</button>
        </p>
        <p v-else-if="projectsLoaded && !projects.length" class="mb-4 text-sm text-slate-500 dark:text-slate-400">조회할 수 있는 프로젝트가 없습니다.</p>
        <p v-else-if="projectsLoaded && !selectedProject" role="alert" class="mb-4 text-sm text-red-700">
            선택한 프로젝트에 접근할 수 없습니다. 다른 프로젝트를 선택해 주세요.
        </p>
        <p v-else-if="selectedProject" class="mb-4 text-sm text-slate-600 dark:text-slate-300">
            {{ selectedProject.name }} · 저장된 분석 {{ totalCount.toLocaleString() }}건
        </p>
        <p v-if="error" role="alert" class="mb-4 text-sm text-red-700">
            {{ error }}
            <button type="button" class="underline" @click="fetchHistory">다시 시도</button>
        </p>
        <BaseTable
            v-if="selectedProject"
            :columns="columns"
            :rows="history"
            row-key="jobId"
            :loading="loading"
            empty-text="이 프로젝트의 저장된 분석 작업이 없습니다."
        >
            <template #cell-requirementTitle="{ row }">{{ row.requirementTitle || '프로젝트 구조 분석' }}</template>
            <template #cell-riskLevel="{ value }">{{ riskLevelLabel(value) }}</template>
            <template #cell-thinkingEnabled="{ value }">{{ analysisThinkingLabel(value) }}</template>
            <template #cell-thinkingEffort="{ row }">{{ analysisThinkingEffortLabel(row.thinkingEffort, row.thinkingEnabled) }}</template>
            <template #cell-reviewStatus="{ row }">{{
                row.status === 'COMPLETED' ? { APPROVED: '승인', REJECTED: '반려' }[row.reviewStatus] || '검토 대기' : '—'
            }}</template>
            <template #cell-evidenceReusedFromJobId="{ row }">{{
                row.status === 'COMPLETED' ? (row.evidenceReusedFromJobId ? '재사용' : '새 수집') : '—'
            }}</template>
            <template #cell-createdAt="{ value }">{{ formatDate(value) }}</template>
            <template #cell-open="{ row }">
                <BaseButton
                    class="w-full"
                    size="small"
                    variant="secondary"
                    :to="{ name: 'analysis-progress', params: { projectId: row.projectId, analysisJobId: row.jobId } }"
                    >{{ row.status === 'COMPLETED' ? '결과 보기' : '상태 보기' }}</BaseButton
                >
            </template>
        </BaseTable>
        <div v-if="selectedProject" class="mt-4">
            <BasePagination v-model:page="page" :page-size="pageSize" :total-count="totalCount" />
        </div>
    </div>
</template>
