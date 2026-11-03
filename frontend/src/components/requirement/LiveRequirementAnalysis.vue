<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { startProjectAnalysis } from '@/api/analysisApi'
import { getAnalysisHistory } from '@/api/appApi'
import { getProjects } from '@/api/projectApi'
import { createProjectRequirement, getAllRequirements } from '@/api/requirementApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseDatePicker from '@/components/base/BaseDatePicker.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BasePagination from '@/components/base/BasePagination.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import BaseTable from '@/components/base/BaseTable.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import AnalysisThinkingControl from '@/components/analysis/AnalysisThinkingControl.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { useClientTable } from '@/composables/useClientTable'
import { matchesCreatedDateRange } from '@/lib/changeRequestFilters'
import { evidenceWarningLabel } from '@/lib/evidenceReuse'
import { buildRequirementPayload, EVIDENCE_SELECTION_OPTIONS } from '@/lib/requirementWorkflow'
import { analysisThinkingError } from '@/lib/analysisThinking'
import { useAuthStore } from '@/stores/authStore'

const router = useRouter()
const authStore = useAuthStore()
const projects = ref([])
const selectedProjectId = ref('')
const requirements = ref([])
const projectsLoading = ref(false)
const requirementsLoading = ref(false)
const creating = ref(false)
const analyzingRequirementId = ref('')
const analysisTarget = ref(null)
const analysisPanel = ref(null)
const snapshotOptions = ref([])
const snapshotLoading = ref(false)
const snapshotError = ref('')
const snapshotLoaded = ref(false)
const snapshotRecords = ref([])
const snapshotPage = ref(0)
const snapshotTotal = ref(0)
const snapshotPageSize = 20
const snapshotAppendError = ref(false)
const analysisError = ref('')
let snapshotsRequestId = 0
const evidenceMode = ref('VERIFY_CURRENT')
const selectedSnapshotId = ref('')
const confirmedUnchanged = ref(false)
const thinkingEnabled = ref(true)
const thinkingEffort = ref('medium')
const evidenceSelection = ref('AUTO')
const showCreateForm = ref(false)
const errorMessage = ref('')
const notice = ref('')
const searchKeyword = ref('')
const filterProjectId = ref('')
const filterStatus = ref('')
const filterPriority = ref('')
const filterStartDate = ref('')
const filterEndDate = ref('')
const selectedRequirementId = ref('')

const initialForm = () => ({
    requirementKey: '',
    title: '',
    priority: 'MEDIUM',
    originalContent: '',
    refinedContent: '',
    acceptanceCriteriaText: '',
})
const form = reactive(initialForm())

const gitProjects = computed(() => projects.value.filter((project) => project.sourceType === 'GIT'))
const projectOptions = computed(() =>
    gitProjects.value.map((project) => ({
        value: project.id,
        label: `${project.name} (${project.id.slice(0, 8)})`,
    }))
)
const filterProjectOptions = computed(() => [
    { value: '', label: '전체 프로젝트' },
    ...projects.value.map((project) => ({ value: project.id, label: `${project.name} (${project.id.slice(0, 8)})` })),
])
const statusOptions = [
    { value: '', label: '전체 상태' },
    { value: 'DRAFT', label: '초안' },
    { value: 'SUBMITTED', label: '접수' },
    { value: 'REFINING', label: '정리 중' },
    { value: 'READY', label: '분석 준비' },
    { value: 'ANALYZING', label: '분석 중' },
    { value: 'REVIEWING', label: '검토 중' },
    { value: 'ESTIMATING', label: '견적 중' },
    { value: 'APPROVED', label: '승인' },
    { value: 'REJECTED', label: '반려' },
    { value: 'CANCELLED', label: '취소' },
]
const priorityOptions = [
    { value: 'LOW', label: '낮음' },
    { value: 'MEDIUM', label: '보통' },
    { value: 'HIGH', label: '높음' },
    { value: 'URGENT', label: '긴급' },
]
const filterPriorityOptions = [{ value: '', label: '전체 우선순위' }, ...priorityOptions]
const dateRangeError = computed(() =>
    filterStartDate.value && filterEndDate.value && filterStartDate.value > filterEndDate.value ? '종료일은 시작일 이후여야 합니다.' : ''
)
const hasActiveFilters = computed(() =>
    Boolean(searchKeyword.value || filterProjectId.value || filterStatus.value || filterPriority.value || filterStartDate.value || filterEndDate.value)
)
const selectedProject = computed(() => gitProjects.value.find((project) => project.id === selectedProjectId.value))
const canCreate = computed(() => authStore.hasPermission('CHANGE_REQUEST_CREATE'))
const canAnalyze = computed(() => authStore.hasPermission('PROJECT_UPDATE') && authStore.hasPermission('ANALYSIS_READ'))
const busy = computed(() => creating.value || Boolean(analyzingRequirementId.value))
const selectedSnapshot = computed(() => snapshotRecords.value.find((item) => item.jobId === selectedSnapshotId.value && item.reusableEvidence))
const selectedSnapshotWarnings = computed(() => selectedSnapshot.value?.evidenceWarnings || [])
const hasMoreSnapshots = computed(() => (snapshotPage.value + 1) * snapshotPageSize < snapshotTotal.value)
const savedAnalysisBlocked = computed(() => snapshotLoading.value || !selectedSnapshot.value || !confirmedUnchanged.value)
const projectById = computed(() => new Map(projects.value.map((project) => [project.id, project])))
const tableRows = computed(() =>
    requirements.value.map((requirement) => ({
        ...requirement,
        projectName: projectById.value.get(requirement.projectId)?.name || requirement.projectId,
    }))
)
const filteredRequirements = computed(() => {
    const keyword = searchKeyword.value.trim().toLocaleLowerCase('ko-KR')
    if (dateRangeError.value) return []
    return tableRows.value.filter((requirement) => {
        if (filterProjectId.value && requirement.projectId !== filterProjectId.value) return false
        if (filterStatus.value && requirement.status !== filterStatus.value) return false
        if (filterPriority.value && requirement.priority !== filterPriority.value) return false
        if (!matchesCreatedDateRange(requirement.createdAt, filterStartDate.value, filterEndDate.value)) return false
        return (
            !keyword ||
            [
                requirement.requirementKey,
                requirement.projectName,
                requirement.title,
                requirement.currentVersion?.originalContent,
                requirement.currentVersion?.refinedContent,
            ].some((value) =>
                String(value || '')
                    .toLocaleLowerCase('ko-KR')
                    .includes(keyword)
            )
        )
    })
})
const selectedRequirement = computed(() => tableRows.value.find((requirement) => requirement.id === selectedRequirementId.value))
const columns = [
    { key: 'requirementKey', label: '요청 키', width: '130px', sortable: true },
    { key: 'projectName', label: '프로젝트', minWidth: '170px', sortable: true },
    { key: 'title', label: '변경요청', minWidth: '300px', sortable: true },
    { key: 'priority', label: '우선순위', width: '100px' },
    { key: 'status', label: '상태', width: '110px' },
    { key: 'currentVersion', label: '버전', width: '75px' },
    { key: 'createdAt', label: '등록일', width: '170px', sortable: true },
    {
        key: 'actions',
        label: '관리',
        width: '120px',
        minWidth: '120px',
        headerClass: 'sticky right-0 z-20 border-l border-slate-200 bg-slate-50 dark:border-slate-700 dark:bg-slate-800',
        cellClass: 'sticky right-0 z-10 border-l border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900',
    },
]
const { page, pageSize, sortKey, sortDirection, totalCount, pagedRows, handleSort, resetPage } = useClientTable({
    rows: filteredRequirements,
    defaultPageSize: 10,
    defaultSortKey: 'createdAt',
    defaultSortDirection: 'desc',
})

const resetFilters = () => {
    searchKeyword.value = ''
    filterProjectId.value = ''
    filterStatus.value = ''
    filterPriority.value = ''
    filterStartDate.value = ''
    filterEndDate.value = ''
}

const getErrorMessage = (error) =>
    analysisThinkingError(error) || error?.response?.data?.message || error?.response?.data?.detail || error?.message || '요청을 처리하지 못했습니다.'

const formatDate = (value) => (value ? new Date(value).toLocaleString('ko-KR') : '—')

// 프로젝트 이름 표시와 신규 요청 등록에 필요한 프로젝트 목록을 불러온다.
const loadProjects = async () => {
    projectsLoading.value = true
    errorMessage.value = ''
    try {
        projects.value = await getProjects()
        if (gitProjects.value.length === 1) selectedProjectId.value = gitProjects.value[0].id
    } catch (error) {
        errorMessage.value = `프로젝트 목록을 불러오지 못했습니다. ${getErrorMessage(error)}`
    } finally {
        projectsLoading.value = false
    }
}

// 프로젝트를 빠르게 바꿨을 때 이전 조회 응답이 새 목록을 덮어쓰지 않도록 한다.
let requirementsRequestId = 0
// 현재 사용자가 볼 수 있는 모든 프로젝트의 변경요청을 읽는다.
const loadRequirements = async () => {
    const requestId = ++requirementsRequestId
    errorMessage.value = ''
    notice.value = ''

    requirementsLoading.value = true
    try {
        const loaded = await getAllRequirements()
        if (requestId === requirementsRequestId) {
            requirements.value = loaded
            if (!loaded.some((requirement) => requirement.id === selectedRequirementId.value)) {
                selectedRequirementId.value = ''
            }
        }
    } catch (error) {
        if (requestId === requirementsRequestId) {
            requirements.value = []
            selectedRequirementId.value = ''
            errorMessage.value = `요구사항을 불러오지 못했습니다. ${getErrorMessage(error)}`
        }
    } finally {
        if (requestId === requirementsRequestId) requirementsLoading.value = false
    }
}

// form을 생성 API 본문으로 변환해 첫 버전을 저장하고 목록에 추가한다.
const createRequirement = async () => {
    if (!selectedProjectId.value || requirementsLoading.value) return
    errorMessage.value = ''
    notice.value = ''
    let payload
    try {
        payload = buildRequirementPayload(form)
    } catch (error) {
        errorMessage.value = getErrorMessage(error)
        return
    }

    creating.value = true
    try {
        const created = await createProjectRequirement(selectedProjectId.value, payload)
        requirements.value = [created, ...requirements.value]
        resetFilters()
        selectedRequirementId.value = created.id
        Object.assign(form, initialForm())
        showCreateForm.value = false
        notice.value = '요구사항과 첫 번째 버전을 저장했습니다. 목록에서 영향 분석을 시작할 수 있습니다.'
    } catch (error) {
        errorMessage.value = `요구사항을 저장하지 못했습니다. ${getErrorMessage(error)}`
    } finally {
        creating.value = false
    }
}

const loadSavedEvidence = async ({ append = false } = {}) => {
    const target = analysisTarget.value
    if (!target || snapshotLoading.value || busy.value) return
    const requestId = ++snapshotsRequestId
    const nextPage = append ? snapshotPage.value + 1 : 0
    snapshotError.value = ''
    snapshotAppendError.value = false
    snapshotLoading.value = true
    analysisError.value = ''
    if (!append) {
        snapshotLoaded.value = false
        snapshotRecords.value = []
        snapshotOptions.value = []
        snapshotPage.value = 0
        snapshotTotal.value = 0
        selectedSnapshotId.value = ''
        confirmedUnchanged.value = false
    }
    try {
        const response = await getAnalysisHistory({ projectId: target.projectId, page: nextPage, size: snapshotPageSize, reusableEvidenceOnly: true })
        if (requestId !== snapshotsRequestId || analysisTarget.value?.id !== target.id) return
        const items = (response.items || []).filter((item) => item.reusableEvidence)
        snapshotRecords.value = append ? [...new Map([...snapshotRecords.value, ...items].map((item) => [item.jobId, item])).values()] : items
        snapshotOptions.value = snapshotRecords.value.map((item) => ({
            value: item.jobId,
            label: `${formatDate(item.createdAt)} · ${item.requirementTitle || '프로젝트 구조 분석'} · ${item.sourceCommit?.slice(0, 8) || '커밋 정보 없음'}`,
            warnings: item.evidenceWarnings || [],
        }))
        snapshotPage.value = nextPage
        snapshotTotal.value = response.total ?? snapshotRecords.value.length
        if (!selectedSnapshot.value) selectedSnapshotId.value = snapshotOptions.value[0]?.value || ''
        snapshotLoaded.value = true
    } catch (error) {
        if (requestId === snapshotsRequestId && analysisTarget.value?.id === target.id) {
            snapshotError.value = `저장된 분석 기준을 불러오지 못했습니다. ${getErrorMessage(error)}`
            snapshotAppendError.value = append
        }
    } finally {
        if (requestId === snapshotsRequestId) snapshotLoading.value = false
    }
}

const prepareAnalysis = async (requirement) => {
    snapshotsRequestId += 1
    analysisTarget.value = requirement
    evidenceMode.value = 'VERIFY_CURRENT'
    thinkingEnabled.value = true
    thinkingEffort.value = 'medium'
    evidenceSelection.value = 'AUTO'
    selectedSnapshotId.value = ''
    confirmedUnchanged.value = false
    snapshotRecords.value = []
    snapshotOptions.value = []
    snapshotError.value = ''
    snapshotLoaded.value = false
    snapshotPage.value = 0
    snapshotTotal.value = 0
    snapshotAppendError.value = false
    snapshotLoading.value = false
    analysisError.value = ''
    await nextTick()
    analysisPanel.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

watch(evidenceMode, (mode) => {
    analysisError.value = ''
    if (mode === 'SAVED' && !snapshotLoaded.value && !snapshotLoading.value) loadSavedEvidence()
})
watch(selectedSnapshotId, () => {
    confirmedUnchanged.value = false
    analysisError.value = ''
})

// 선택한 근거 방식으로 새 AI 판단을 수행한다. 저장 근거 모드는 Git/DB 재확인을 생략한다.
const analyzeRequirement = async () => {
    const requirement = analysisTarget.value
    if (!requirement) return
    const versionId = requirement.currentVersion?.id
    if (!versionId) {
        analysisError.value = '현재 요구사항 버전을 찾을 수 없습니다.'
        return
    }
    if (evidenceMode.value === 'SAVED') {
        if (snapshotLoading.value) {
            analysisError.value = '저장된 분석 기준을 불러오는 중입니다. 조회가 끝난 뒤 다시 시도해 주세요.'
            return
        }
        if (snapshotError.value && !selectedSnapshot.value) {
            analysisError.value = '저장된 분석 기준을 다시 불러온 뒤 선택해 주세요.'
            return
        }
        if (!selectedSnapshot.value) {
            analysisError.value = '저장된 분석 기준을 선택해 주세요. 최신 Git·DB 확인으로 자동 전환하지 않습니다.'
            return
        }
        if (!confirmedUnchanged.value) {
            analysisError.value = '선택한 과거 시점을 기준으로 분석한다는 확인란을 선택해 주세요.'
            return
        }
    }

    analysisError.value = ''
    errorMessage.value = ''
    notice.value = ''
    analyzingRequirementId.value = requirement.id
    let createdJobId = ''
    try {
        const job = await startProjectAnalysis(
            requirement.projectId,
            versionId,
            evidenceMode.value === 'SAVED' ? selectedSnapshotId.value : null,
            thinkingEnabled.value,
            thinkingEffort.value,
            evidenceSelection.value
        )
        createdJobId = job.jobId
        await router.push({
            name: 'analysis-progress',
            params: { projectId: requirement.projectId, analysisJobId: createdJobId },
        })
    } catch (error) {
        analysisError.value = createdJobId
            ? `분석 작업 ${createdJobId}은 시작됐지만 결과 화면으로 이동하지 못했습니다. ${getErrorMessage(error)}`
            : `영향 분석을 시작하지 못했습니다. ${getErrorMessage(error)}`
    } finally {
        analyzingRequirementId.value = ''
    }
}

watch([searchKeyword, filterProjectId, filterStatus, filterPriority, filterStartDate, filterEndDate], resetPage)
onMounted(() => {
    loadProjects()
    loadRequirements()
})
</script>

<template>
    <div>
        <ViewHeading title="변경요청 관리" description="프로젝트별 변경요청을 한눈에 검색하고, 필요한 요청의 현재 버전을 분석합니다.">
            <template #actions>
                <BaseButton
                    v-if="canCreate"
                    class="w-full whitespace-nowrap lg:w-auto"
                    :disabled="busy || projectsLoading || !gitProjects.length"
                    @click="showCreateForm = !showCreateForm"
                >
                    {{ showCreateForm ? '등록 닫기' : '+ 변경요청 등록' }}
                </BaseButton>
            </template>
        </ViewHeading>

        <p v-if="errorMessage" role="alert" class="mb-4 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">{{ errorMessage }}</p>
        <p v-if="notice" role="status" class="mb-4 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-700">{{ notice }}</p>

        <section class="mb-6 rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <div class="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between sm:gap-3">
                <BaseInput
                    v-model="searchKeyword"
                    id="requirement-search"
                    wrapper-class="min-w-0 flex-1"
                    label="변경요청 검색"
                    placeholder="제목·요청 키 검색"
                    hint="프로젝트명, 요청 키, 제목, 내용으로 검색할 수 있습니다."
                />
                <span class="shrink-0 text-sm text-slate-500 dark:text-slate-400 sm:pb-3">검색 결과 {{ totalCount.toLocaleString() }}건</span>
            </div>
            <div class="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-5">
                <BaseSelect v-model="filterProjectId" id="filter-project" label="프로젝트" :options="filterProjectOptions" />
                <BaseSelect v-model="filterStatus" id="filter-status" label="상태" :options="statusOptions" />
                <BaseSelect v-model="filterPriority" id="filter-priority" label="우선순위" :options="filterPriorityOptions" />
                <BaseDatePicker v-model="filterStartDate" id="filter-start-date" label="등록 시작일" :max="filterEndDate" />
                <BaseDatePicker v-model="filterEndDate" id="filter-end-date" label="등록 종료일" :min="filterStartDate" :error="dateRangeError" align="right" />
            </div>
            <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">선택한 조건은 모두 함께 적용됩니다.</p>
            <div class="mt-3 flex justify-end">
                <BaseButton class="w-full sm:w-auto" variant="secondary" size="small" :disabled="!hasActiveFilters" @click="resetFilters"
                    >검색 조건 초기화</BaseButton
                >
            </div>
            <p v-if="projectsLoading" class="mt-3 text-sm text-slate-500 dark:text-slate-400">프로젝트 목록을 불러오는 중입니다.</p>
            <p v-else-if="!projects.length" class="mt-3 text-sm text-slate-500 dark:text-slate-400">
                조회할 프로젝트가 없습니다.
                <RouterLink to="/projects/new" class="font-semibold text-blue-700 underline dark:text-blue-300">프로젝트를 먼저 등록하세요.</RouterLink>
            </p>
        </section>

        <form
            v-if="showCreateForm"
            class="mb-6 grid gap-4 rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900"
            @submit.prevent="createRequirement"
        >
            <h2 class="text-lg font-bold">변경요청 등록</h2>
            <p class="text-sm text-slate-500 dark:text-slate-400">접속정보·비밀번호·토큰은 요구사항에 입력하지 마세요.</p>
            <BaseSelect
                v-model="selectedProjectId"
                id="requirement-project"
                label="등록할 프로젝트"
                :options="projectOptions"
                wrapper-class="max-w-xl"
                required
            >
                <template #before-options><option value="">프로젝트를 선택하세요</option></template>
            </BaseSelect>
            <p v-if="selectedProject" class="text-xs text-slate-500 dark:text-slate-400">{{ selectedProject.name }}에 변경요청을 저장합니다.</p>
            <div class="grid gap-4 md:grid-cols-2">
                <BaseInput
                    v-model="form.requirementKey"
                    id="requirement-key"
                    label="요구사항 키"
                    placeholder="예: REQ-001 (선택)"
                    maxlength="50"
                    pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,49}"
                />
                <BaseSelect v-model="form.priority" id="requirement-priority" label="우선순위" :options="priorityOptions" />
            </div>
            <BaseInput v-model="form.title" id="requirement-title" label="제목" placeholder="무엇을 변경할지 짧게 적어주세요" maxlength="300" required />
            <BaseTextarea
                id="requirement-original"
                v-model="form.originalContent"
                label="요청 원문"
                required
                placeholder="고객이나 담당자가 전달한 내용을 적어주세요."
            />
            <BaseTextarea
                id="requirement-refined"
                v-model="form.refinedContent"
                label="정리한 변경 내용"
                required
                placeholder="변경할 동작·데이터·제외 범위를 구체적으로 적어주세요."
            />
            <BaseTextarea id="requirement-criteria" v-model="form.acceptanceCriteriaText" label="인수 조건 (선택)" placeholder="한 줄에 하나씩 입력하세요." />
            <div class="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                <BaseButton class="w-full sm:w-auto" type="submit" :loading="creating" :disabled="requirementsLoading || (busy && !creating)"
                    >요구사항 저장</BaseButton
                >
                <BaseButton class="w-full sm:w-auto" variant="secondary" :disabled="busy" @click="showCreateForm = false">취소</BaseButton>
            </div>
        </form>

        <section class="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                    <h2 class="text-lg font-bold">등록된 변경요청</h2>
                    <p class="text-sm text-slate-500 dark:text-slate-400">요청을 열어 내용을 확인하고, 필요한 버전만 새 분석을 실행하세요.</p>
                </div>
                <BaseButton class="w-full sm:w-auto" variant="secondary" size="small" :disabled="requirementsLoading || busy" @click="loadRequirements"
                    >새로고침</BaseButton
                >
            </div>
            <p v-if="!canAnalyze" class="mb-4 text-sm text-amber-700 dark:text-amber-300">분석 시작에는 프로젝트 수정 및 분석 조회 권한이 필요합니다.</p>
            <BaseTable
                :columns="columns"
                :rows="pagedRows"
                row-key="id"
                :loading="requirementsLoading"
                :sort-key="sortKey"
                :sort-direction="sortDirection"
                :empty-text="hasActiveFilters ? '검색 조건에 맞는 변경요청이 없습니다.' : '등록된 변경요청이 없습니다.'"
                sticky-header
                max-height="620px"
                @sort-change="handleSort"
            >
                <template #cell-requirementKey="{ row }">
                    <span class="font-semibold text-blue-700 dark:text-blue-300">{{ row.requirementKey || row.id.slice(0, 8) }}</span>
                </template>
                <template #cell-title="{ row }">
                    <div class="font-semibold text-slate-900 dark:text-slate-100">{{ row.title }}</div>
                    <div class="mt-1 max-w-md truncate text-xs text-slate-500 dark:text-slate-400">
                        {{ row.currentVersion?.refinedContent || '정리한 변경 내용 없음' }}
                    </div>
                </template>
                <template #cell-priority="{ value }">{{ priorityOptions.find((option) => option.value === value)?.label || value }}</template>
                <template #cell-status="{ value }"><StatusBadge :status="value" /></template>
                <template #cell-currentVersion="{ value }">v{{ value?.versionNumber ?? '—' }}</template>
                <template #cell-createdAt="{ value }">{{ formatDate(value) }}</template>
                <template #cell-actions="{ row }">
                    <div class="flex flex-col gap-1">
                        <BaseButton
                            class="w-full whitespace-nowrap"
                            size="small"
                            variant="secondary"
                            @click="selectedRequirementId = selectedRequirementId === row.id ? '' : row.id"
                            >상세</BaseButton
                        >
                        <BaseButton
                            v-if="canAnalyze && projectById.get(row.projectId)?.sourceType === 'GIT'"
                            class="w-full whitespace-nowrap"
                            size="small"
                            :loading="analyzingRequirementId === row.id"
                            :disabled="busy || !row.currentVersion?.id"
                            @click="prepareAnalysis(row)"
                            >새 분석</BaseButton
                        >
                    </div>
                </template>
                <template #footer>
                    <BasePagination v-model:page="page" :page-size="pageSize" :total-count="totalCount" />
                </template>
            </BaseTable>

            <section
                v-if="analysisTarget"
                ref="analysisPanel"
                class="mt-5 rounded-xl border border-blue-200 bg-blue-50/50 p-5 dark:border-blue-900 dark:bg-blue-950/20"
            >
                <div class="flex flex-wrap items-start justify-between gap-3">
                    <div>
                        <h3 class="text-base font-bold text-slate-900 dark:text-slate-100">{{ analysisTarget.title }} · 분석 설정</h3>
                        <p class="mt-1 text-sm text-slate-600 dark:text-slate-300">
                            같은 요구사항도 새로 분석합니다. Git·DB 수집 방식, AI에 보낼 근거와 추론 모드·강도를 선택하세요.
                        </p>
                    </div>
                    <BaseButton size="small" variant="secondary" @click="analysisTarget = null">닫기</BaseButton>
                </div>
                <div class="mt-4 grid gap-3">
                    <label
                        class="flex cursor-pointer items-start gap-3 rounded-lg border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-900"
                    >
                        <input v-model="evidenceMode" type="radio" value="VERIFY_CURRENT" class="mt-1" :disabled="busy" />
                        <span
                            ><strong class="block text-sm">최신 Git·DB 확인</strong
                            ><span class="text-xs text-slate-600 dark:text-slate-400"
                                >현재 커밋과 DB 구조를 확인합니다. 변경이 없다면 저장된 소스 근거를 자동 재사용합니다.</span
                            ></span
                        >
                    </label>
                    <label
                        class="flex cursor-pointer items-start gap-3 rounded-lg border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-900"
                    >
                        <input v-model="evidenceMode" type="radio" value="SAVED" class="mt-1" :disabled="busy" />
                        <span
                            ><strong class="block text-sm">저장된 근거로 바로 분석</strong
                            ><span class="text-xs text-slate-600 dark:text-slate-400"
                                >Git·DB에 다시 접속하지 않습니다. 선택한 시점 이후 변경은 분석에 반영되지 않습니다.</span
                            ></span
                        >
                    </label>
                </div>
                <div v-if="evidenceMode === 'SAVED'" class="mt-4 rounded-lg border border-blue-100 bg-white p-4 dark:border-slate-700 dark:bg-slate-900">
                    <div class="flex flex-wrap items-center justify-between gap-2">
                        <h4 class="text-sm font-semibold text-slate-900 dark:text-slate-100">저장된 분석 기준 선택</h4>
                        <BaseButton size="small" variant="secondary" :disabled="busy || snapshotLoading" @click="loadSavedEvidence()">
                            {{ snapshotLoading ? '불러오는 중…' : '다시 불러오기' }}
                        </BaseButton>
                    </div>
                    <p v-if="snapshotLoading" role="status" class="mt-3 text-sm text-slate-600 dark:text-slate-400">
                        이 프로젝트의 저장된 분석 기준을 불러오는 중입니다.
                    </p>
                    <div v-if="snapshotError" role="alert" class="mt-3 space-y-2 text-sm text-rose-700 dark:text-rose-300">
                        <p>{{ snapshotError }}</p>
                        <BaseButton
                            size="small"
                            variant="secondary"
                            :disabled="busy || snapshotLoading"
                            @click="loadSavedEvidence({ append: snapshotAppendError })"
                            >조회 다시 시도</BaseButton
                        >
                    </div>
                    <div
                        v-if="snapshotLoaded && !snapshotLoading && !snapshotError && !snapshotOptions.length"
                        class="mt-3 space-y-3 text-sm text-slate-600 dark:text-slate-400"
                    >
                        <p>이 프로젝트에 선택할 수 있는 저장 근거가 없습니다. 최신 Git·DB 확인으로 먼저 한 번 분석하면 이 프로젝트의 저장 근거가 생깁니다.</p>
                        <BaseButton size="small" variant="secondary" :disabled="busy" @click="evidenceMode = 'VERIFY_CURRENT'"
                            >최신 Git·DB 확인으로 분석하기</BaseButton
                        >
                    </div>
                    <div v-if="snapshotOptions.length" class="mt-4 grid gap-3">
                        <BaseSelect
                            v-model="selectedSnapshotId"
                            label="분석 기준 시점 · Git 커밋"
                            :options="snapshotOptions"
                            :disabled="busy || snapshotLoading"
                        />
                        <div class="flex flex-wrap items-center justify-between gap-2">
                            <p class="text-xs text-slate-600 dark:text-slate-400">저장된 기준 {{ snapshotTotal }}건 중 {{ snapshotRecords.length }}건 표시</p>
                            <BaseButton
                                v-if="hasMoreSnapshots"
                                size="small"
                                variant="secondary"
                                :disabled="busy || snapshotLoading"
                                @click="loadSavedEvidence({ append: true })"
                                >이전 기준 더 보기</BaseButton
                            >
                        </div>
                        <div
                            v-if="selectedSnapshotWarnings.length"
                            class="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/30"
                        >
                            <p class="font-semibold text-amber-900 dark:text-amber-200">이 기준에 저장된 자료의 범위</p>
                            <ul class="mt-2 list-disc space-y-1 pl-5 text-amber-900 dark:text-amber-200">
                                <li v-for="warning in selectedSnapshotWarnings" :key="warning">{{ evidenceWarningLabel(warning) }}</li>
                            </ul>
                        </div>
                        <p class="text-xs leading-5 text-slate-600 dark:text-slate-400">
                            선택한 시점에 저장한 소스·DB 자료로 분석합니다. 그 이후 변경은 이번 분석에 포함되지 않습니다.
                        </p>
                        <label class="flex items-start gap-2 text-sm text-slate-700 dark:text-slate-300">
                            <input v-model="confirmedUnchanged" type="checkbox" class="mt-1" :disabled="busy || snapshotLoading" />
                            최신 상태와 다를 수 있음을 이해하고 선택한 과거 시점을 기준으로 분석합니다.
                        </label>
                    </div>
                </div>
                <div class="mt-4 grid gap-1">
                    <BaseSelect
                        v-model="evidenceSelection"
                        id="evidence-selection"
                        label="AI에 보낼 근거"
                        :options="EVIDENCE_SELECTION_OPTIONS"
                        :disabled="busy"
                    />
                    <p class="text-xs text-slate-600 dark:text-slate-400">
                        자동은 근거가 많을 때만 요구사항과 관련된 것을 골라 보냅니다. 작은 프로젝트에서도 골라 보내려면 두 번째를, 항상 전체를 보내려면 세
                        번째를 선택하세요.
                    </p>
                </div>
                <AnalysisThinkingControl v-model="thinkingEnabled" v-model:thinking-effort="thinkingEffort" class="mt-4" :disabled="busy" />
                <p
                    v-if="analysisError"
                    role="alert"
                    class="mt-4 rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300"
                >
                    {{ analysisError }}
                </p>
                <p
                    v-else-if="evidenceMode === 'SAVED' && !snapshotLoading && !snapshotError && !selectedSnapshot"
                    class="mt-4 text-sm text-slate-600 dark:text-slate-400"
                >
                    저장된 분석 기준을 선택해야 분석을 시작할 수 있습니다.
                </p>
                <div class="mt-4 flex flex-col gap-2 sm:flex-row">
                    <BaseButton
                        class="w-full sm:w-auto"
                        :loading="Boolean(analyzingRequirementId)"
                        :disabled="busy || (evidenceMode === 'SAVED' && savedAnalysisBlocked)"
                        @click="analyzeRequirement"
                        >분석 시작</BaseButton
                    >
                    <BaseButton class="w-full sm:w-auto" variant="secondary" :disabled="busy" @click="analysisTarget = null">취소</BaseButton>
                </div>
            </section>

            <section v-if="selectedRequirement" class="mt-5 rounded-lg border border-blue-200 bg-blue-50/50 p-5 dark:border-blue-900 dark:bg-blue-950/20">
                <div class="mb-3 flex flex-wrap items-start justify-between gap-3">
                    <div>
                        <p class="text-xs font-semibold text-blue-700 dark:text-blue-300">
                            {{ selectedRequirement.projectName }} · {{ selectedRequirement.requirementKey || selectedRequirement.id.slice(0, 8) }}
                        </p>
                        <h3 class="mt-1 text-lg font-bold text-slate-900 dark:text-slate-100">{{ selectedRequirement.title }}</h3>
                    </div>
                    <BaseButton size="small" variant="secondary" @click="selectedRequirementId = ''">닫기</BaseButton>
                </div>
                <p class="text-xs text-slate-500 dark:text-slate-400">
                    현재 버전 v{{ selectedRequirement.currentVersion?.versionNumber ?? '—' }} · 등록 {{ formatDate(selectedRequirement.createdAt) }}
                </p>
                <h4 class="mt-4 text-sm font-bold">요청 원문</h4>
                <p class="mt-1 whitespace-pre-wrap text-sm text-slate-700 dark:text-slate-300">
                    {{ selectedRequirement.currentVersion?.originalContent || '—' }}
                </p>
                <h4 class="mt-4 text-sm font-bold">정리한 변경 내용</h4>
                <p class="mt-1 whitespace-pre-wrap text-sm text-slate-700 dark:text-slate-300">
                    {{ selectedRequirement.currentVersion?.refinedContent || '—' }}
                </p>
                <BaseButton
                    v-if="authStore.hasPermission('ANALYSIS_READ')"
                    class="mt-4"
                    size="small"
                    variant="secondary"
                    :to="{ name: 'analysis-history', query: { projectId: selectedRequirement.projectId } }"
                    >해당 프로젝트 분석 이력 보기</BaseButton
                >
            </section>
        </section>
    </div>
</template>
