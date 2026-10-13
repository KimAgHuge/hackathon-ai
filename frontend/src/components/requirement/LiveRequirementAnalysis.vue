<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { startProjectAnalysis } from '@/api/analysisApi'
import { getProjects } from '@/api/projectApi'
import { createProjectRequirement, getProjectRequirements } from '@/api/requirementApi'
import BaseButton from '@/components/base/BaseButton.vue'
import BaseInput from '@/components/base/BaseInput.vue'
import BaseSelect from '@/components/base/BaseSelect.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { buildRequirementPayload } from '@/lib/requirementWorkflow'
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
const showCreateForm = ref(false)
const errorMessage = ref('')
const notice = ref('')

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
const selectedProject = computed(() => gitProjects.value.find((project) => project.id === selectedProjectId.value))
const canCreate = computed(() => authStore.hasPermission('CHANGE_REQUEST_CREATE'))
const canAnalyze = computed(() => authStore.hasPermission('PROJECT_UPDATE') && authStore.hasPermission('ANALYSIS_READ'))
const busy = computed(() => creating.value || Boolean(analyzingRequirementId.value))

const priorityOptions = [
    { value: 'LOW', label: '낮음' },
    { value: 'MEDIUM', label: '보통' },
    { value: 'HIGH', label: '높음' },
    { value: 'URGENT', label: '긴급' },
]

const getErrorMessage = (error) => error?.response?.data?.message || error?.response?.data?.detail || error?.message || '요청을 처리하지 못했습니다.'

const formatDate = (value) => (value ? new Date(value).toLocaleString('ko-KR') : '—')

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

let requirementsRequestId = 0
const loadRequirements = async () => {
    const requestId = ++requirementsRequestId
    const projectId = selectedProjectId.value
    requirements.value = []
    errorMessage.value = ''
    notice.value = ''
    if (!projectId) {
        requirementsLoading.value = false
        return
    }

    requirementsLoading.value = true
    try {
        const loaded = await getProjectRequirements(projectId)
        if (requestId === requirementsRequestId) requirements.value = loaded
    } catch (error) {
        if (requestId === requirementsRequestId) errorMessage.value = `요구사항을 불러오지 못했습니다. ${getErrorMessage(error)}`
    } finally {
        if (requestId === requirementsRequestId) requirementsLoading.value = false
    }
}

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
        Object.assign(form, initialForm())
        showCreateForm.value = false
        notice.value = '요구사항과 첫 번째 버전을 저장했습니다. 목록에서 영향 분석을 시작할 수 있습니다.'
    } catch (error) {
        errorMessage.value = `요구사항을 저장하지 못했습니다. ${getErrorMessage(error)}`
    } finally {
        creating.value = false
    }
}

const analyzeRequirement = async (requirement) => {
    const versionId = requirement.currentVersion?.id
    if (!versionId) {
        errorMessage.value = '현재 요구사항 버전을 찾을 수 없습니다.'
        return
    }

    errorMessage.value = ''
    notice.value = ''
    analyzingRequirementId.value = requirement.id
    let createdJobId = ''
    try {
        const job = await startProjectAnalysis(selectedProjectId.value, versionId)
        createdJobId = job.jobId
        await router.push({
            name: 'analysis-progress',
            params: { projectId: selectedProjectId.value, analysisJobId: createdJobId },
        })
    } catch (error) {
        errorMessage.value = createdJobId
            ? `분석 작업 ${createdJobId}은 시작됐지만 결과 화면으로 이동하지 못했습니다. ${getErrorMessage(error)}`
            : `영향 분석을 시작하지 못했습니다. ${getErrorMessage(error)}`
    } finally {
        analyzingRequirementId.value = ''
    }
}

watch(selectedProjectId, () => {
    showCreateForm.value = false
    loadRequirements()
})
onMounted(loadProjects)
</script>

<template>
    <div>
        <ViewHeading title="변경요청 관리" description="프로젝트의 변경 요구사항을 등록하고 현재 버전을 소스·DB와 함께 분석합니다.">
            <template #actions>
                <BaseButton v-if="canCreate && selectedProjectId" :disabled="busy || requirementsLoading" @click="showCreateForm = !showCreateForm">
                    {{ showCreateForm ? '등록 닫기' : '+ 변경요청 등록' }}
                </BaseButton>
            </template>
        </ViewHeading>

        <p v-if="errorMessage" role="alert" class="mb-4 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700">{{ errorMessage }}</p>
        <p v-if="notice" role="status" class="mb-4 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-700">{{ notice }}</p>

        <section class="mb-6 rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <BaseSelect
                v-model="selectedProjectId"
                id="requirement-project"
                label="분석할 프로젝트"
                :options="projectOptions"
                :disabled="projectsLoading || busy"
                class="max-w-xl"
            >
                <template #before-options><option value="">프로젝트를 선택하세요</option></template>
            </BaseSelect>
            <p v-if="projectsLoading" class="mt-3 text-sm text-slate-500">프로젝트 목록을 불러오는 중입니다.</p>
            <p v-else-if="!gitProjects.length" class="mt-3 text-sm text-slate-500">
                분석할 Git 프로젝트가 없습니다.
                <RouterLink to="/projects/new" class="font-semibold text-blue-600 underline">프로젝트를 먼저 등록하세요.</RouterLink>
            </p>
            <p v-else-if="selectedProject" class="mt-3 text-sm text-slate-500">
                {{ selectedProject.name }}의 저장된 Git 연결을 재검증한 후, 선택한 요구사항 버전으로 분석합니다.
            </p>
        </section>

        <form
            v-if="showCreateForm && selectedProjectId"
            class="mb-6 grid gap-4 rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900"
            @submit.prevent="createRequirement"
        >
            <h2 class="text-lg font-bold">변경요청 등록</h2>
            <p class="text-sm text-slate-500">접속정보·비밀번호·토큰은 요구사항에 입력하지 마세요.</p>
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
            <div class="grid gap-2">
                <label for="requirement-original" class="ui-label">요청 원문 <span class="text-rose-500">*</span></label>
                <textarea
                    id="requirement-original"
                    v-model="form.originalContent"
                    class="ui-input min-h-28"
                    required
                    placeholder="고객이나 담당자가 전달한 내용을 적어주세요."
                ></textarea>
            </div>
            <div class="grid gap-2">
                <label for="requirement-refined" class="ui-label">정리한 변경 내용 <span class="text-rose-500">*</span></label>
                <textarea
                    id="requirement-refined"
                    v-model="form.refinedContent"
                    class="ui-input min-h-28"
                    required
                    placeholder="변경할 동작·데이터·제외 범위를 구체적으로 적어주세요."
                ></textarea>
            </div>
            <div class="grid gap-2">
                <label for="requirement-criteria" class="ui-label">인수 조건 <span class="text-slate-400">(선택)</span></label>
                <textarea
                    id="requirement-criteria"
                    v-model="form.acceptanceCriteriaText"
                    class="ui-input min-h-24"
                    placeholder="한 줄에 하나씩 입력하세요."
                ></textarea>
            </div>
            <div class="flex flex-wrap gap-2">
                <BaseButton type="submit" :loading="creating" :disabled="requirementsLoading || (busy && !creating)">요구사항 저장</BaseButton>
                <BaseButton variant="secondary" :disabled="busy" @click="showCreateForm = false">취소</BaseButton>
            </div>
        </form>

        <section v-if="selectedProjectId" class="rounded-xl border border-slate-200 bg-white p-5 dark:border-slate-700 dark:bg-slate-900">
            <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
                <div>
                    <h2 class="text-lg font-bold">등록된 요구사항</h2>
                    <p class="text-sm text-slate-500">현재 버전을 선택해 변경 영향 분석을 시작합니다.</p>
                </div>
                <BaseButton variant="secondary" size="small" :disabled="requirementsLoading || busy" @click="loadRequirements">새로고침</BaseButton>
            </div>
            <p v-if="!canAnalyze" class="mb-4 text-sm text-amber-700">분석 시작에는 프로젝트 수정 및 분석 조회 권한이 필요합니다.</p>
            <p v-if="requirementsLoading" class="text-sm text-slate-500">요구사항을 불러오는 중입니다.</p>
            <p v-else-if="!requirements.length" class="text-sm text-slate-500">등록된 요구사항이 없습니다. 변경요청을 먼저 등록하세요.</p>
            <div v-else class="grid gap-3">
                <article v-for="requirement in requirements" :key="requirement.id" class="rounded-lg border border-slate-200 p-4 dark:border-slate-700">
                    <div class="flex flex-wrap items-start justify-between gap-3">
                        <div class="min-w-0 flex-1">
                            <div class="mb-2 flex flex-wrap items-center gap-2">
                                <span class="text-xs font-bold text-slate-500">{{ requirement.requirementKey || requirement.id.slice(0, 8) }}</span>
                                <StatusBadge :status="requirement.status" />
                                <span class="text-xs text-slate-500">v{{ requirement.currentVersion?.versionNumber ?? '—' }}</span>
                            </div>
                            <h3 class="font-bold text-slate-900 dark:text-slate-100">{{ requirement.title }}</h3>
                            <p class="mt-2 whitespace-pre-wrap text-sm text-slate-600 dark:text-slate-300">
                                {{ requirement.currentVersion?.refinedContent || '정리한 변경 내용이 없습니다.' }}
                            </p>
                            <p class="mt-2 text-xs text-slate-400">등록 {{ formatDate(requirement.createdAt) }}</p>
                        </div>
                        <BaseButton
                            v-if="canAnalyze"
                            size="small"
                            :loading="analyzingRequirementId === requirement.id"
                            :disabled="busy || !requirement.currentVersion?.id"
                            @click="analyzeRequirement(requirement)"
                            >현재 버전 영향 분석</BaseButton
                        >
                    </div>
                </article>
            </div>
        </section>
    </div>
</template>
