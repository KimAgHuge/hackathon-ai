<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import PageTitle from '@/components/common/PageTitle.vue'
import StepHeader from '@/components/common/StepHeader.vue'
import ProjectConnectionStep from '@/components/project/connection/ProjectConnectionStep.vue'
import { startProjectAnalysis } from '@/api/analysisApi'
import { createOrganization, getOrganizations } from '@/api/organizationApi'
import { saveGitRepositoryConnection, saveProjectConnection, testDatabaseConnection, testGitRepositoryConnection } from '@/api/projectConnectionApi'
import { createProject, updateProject } from '@/api/projectApi'
import { useAuthStore } from '@/stores/authStore'
import { useProjectWizardStore } from '@/stores/projectWizardStore'

const router = useRouter()
const authStore = useAuthStore()
const wizardStore = useProjectWizardStore()
const {
    projectId,
    repositoryStatus,
    repositoryMessage,
    repositoryBranch,
    repositoryCommitSha,
    connectionStatus,
    connectionMessage,
    databaseProduct,
    databaseVersion,
} = storeToRefs(wizardStore)

const saving = ref(false)
const testingRepository = ref(false)
const testingConnection = ref(false)
const startingAnalysis = ref(false)
const notice = ref('')
const noticeType = ref('success')
const organizations = ref([])
const organizationsLoading = ref(false)
const organizationLoadError = ref('')
const creatingOrganization = ref(false)
const organizationCreateError = ref('')

const organizationOptions = computed(() =>
    organizations.value
        .filter(({ status }) => status === 'ACTIVE')
        .map((organization) => ({
            label: organization.memberRole ? `${organization.name} (${organization.memberRole})` : organization.name,
            value: organization.id,
        }))
)

const canCreateOrganization = computed(() => authStore.hasPermission('ORGANIZATION_MANAGE'))

const project = reactive({
    organizationId: '',
    name: 'doenaai',
    description: '소스코드와 데이터베이스 구조를 함께 분석하는 프로젝트',
    sourceType: 'GIT',
    repositoryUrl: 'https://github.com/example/doenaai.git',
    defaultBranch: 'main',
})

const repositoryConnection = reactive({
    providerType: 'AUTO',
    authType: 'NONE',
    username: '',
    secret: '',
    sshPrivateKey: '',
    sshPassphrase: '',
    knownHostsEntry: '',
})

const databaseSource = reactive({
    connectionMode: 'DB_CONNECTION',
    dbType: 'ORACLE',
    host: '127.0.0.1',
    port: 1521,
    databaseName: 'ORCL',
    schemaName: 'DOENAAI',
    username: 'doenaai_reader',
    password: '',
})

watch(
    databaseSource,
    () => {
        if (connectionStatus.value !== 'IDLE') {
            wizardStore.invalidateConnection()
        }
    },
    { deep: true }
)

watch(
    [() => [project.organizationId, project.sourceType, project.repositoryUrl, project.defaultBranch], repositoryConnection],
    () => {
        if (repositoryStatus.value !== 'IDLE') {
            wizardStore.invalidateRepository()
        }
    },
    { deep: true }
)

const showNotice = (message, type = 'success') => {
    notice.value = message
    noticeType.value = type
}

const getErrorMessage = (error) => error?.response?.data?.message || error?.response?.data?.detail || error?.message || '요청을 처리하지 못했습니다.'

const updateProjectForm = (value) => {
    Object.assign(project, value)
}

const updateDatabaseSourceForm = (value) => {
    Object.assign(databaseSource, value)
}

const updateRepositoryConnectionForm = (value) => {
    Object.assign(repositoryConnection, value)
}

const loadOrganizations = async () => {
    organizationsLoading.value = true
    organizationLoadError.value = ''

    try {
        organizations.value = await getOrganizations()

        const activeOrganizations = organizations.value.filter(({ status }) => status === 'ACTIVE')

        if (!activeOrganizations.some(({ id }) => id === project.organizationId)) {
            project.organizationId = activeOrganizations.length === 1 ? activeOrganizations[0].id : ''
        }
    } catch (error) {
        organizationLoadError.value = getErrorMessage(error)
        showNotice(`조직 목록을 불러오지 못했습니다. ${organizationLoadError.value}`, 'error')
    } finally {
        organizationsLoading.value = false
    }
}

const handleCreateOrganization = async (payload) => {
    creatingOrganization.value = true
    organizationCreateError.value = ''

    try {
        const createdOrganization = await createOrganization(payload)
        organizations.value = [createdOrganization, ...organizations.value]
        project.organizationId = createdOrganization.id
        showNotice(`조직 '${createdOrganization.name}'을 생성하고 선택했습니다.`)
    } catch (error) {
        organizationCreateError.value = getErrorMessage(error)
    } finally {
        creatingOrganization.value = false
    }
}

const buildProjectPayload = () => ({
    organizationId: project.organizationId,
    name: project.name.trim(),
    description: project.description.trim() || null,
    sourceType: project.sourceType,
    repositoryUrl: project.sourceType === 'GIT' ? project.repositoryUrl.trim() : null,
    defaultBranch: project.sourceType === 'GIT' ? project.defaultBranch.trim() : 'main',
})

const ensureProject = async () => {
    const payload = buildProjectPayload()
    let savedProject

    if (projectId.value) {
        savedProject = await updateProject(projectId.value, {
            ...payload,
            status: 'ACTIVE',
        })
    } else {
        savedProject = await createProject(payload)
    }

    wizardStore.setProject(savedProject)
    return savedProject
}

const buildRepositoryConnectionPayload = () => ({
    repositoryUrl: project.repositoryUrl.trim(),
    branch: project.defaultBranch.trim(),
    providerType: repositoryConnection.providerType,
    authType: repositoryConnection.authType,
    username: ['HTTPS_TOKEN', 'HTTPS_BASIC'].includes(repositoryConnection.authType) ? repositoryConnection.username.trim() || null : null,
    secret: ['HTTPS_TOKEN', 'HTTPS_BASIC'].includes(repositoryConnection.authType) ? repositoryConnection.secret || null : null,
    sshPrivateKey: repositoryConnection.authType === 'SSH_KEY' ? repositoryConnection.sshPrivateKey || null : null,
    sshPassphrase: repositoryConnection.authType === 'SSH_KEY' ? repositoryConnection.sshPassphrase || null : null,
    knownHostsEntry: repositoryConnection.authType === 'SSH_KEY' ? repositoryConnection.knownHostsEntry || null : null,
})

const handleSave = async () => {
    saving.value = true
    notice.value = ''

    try {
        await ensureProject()
        showNotice('프로젝트 기본정보를 저장했습니다.')
    } catch (error) {
        showNotice(getErrorMessage(error), 'error')
    } finally {
        saving.value = false
    }
}

const handleTestConnection = async () => {
    testingConnection.value = true
    notice.value = ''
    wizardStore.setConnectionTesting()

    try {
        const savedProject = await ensureProject()
        const result = await testDatabaseConnection(savedProject.id, { ...databaseSource })

        wizardStore.setConnectionResult(result)

        if (!result.success) {
            showNotice(result.message, 'error')
        }
    } catch (error) {
        const message = getErrorMessage(error)
        wizardStore.setConnectionError(message)
        showNotice(message, 'error')
    } finally {
        testingConnection.value = false
    }
}

const handleTestRepository = async () => {
    testingRepository.value = true
    notice.value = ''
    wizardStore.setRepositoryTesting()

    try {
        const savedProject = await ensureProject()
        const result = await testGitRepositoryConnection(savedProject.id, buildRepositoryConnectionPayload())

        wizardStore.setRepositoryResult(result)

        if (!result.success) {
            showNotice(result.message, 'error')
        }
    } catch (error) {
        const message = getErrorMessage(error)
        wizardStore.setRepositoryError(message)
        showNotice(message, 'error')
    } finally {
        testingRepository.value = false
    }
}

const handleNext = async () => {
    if (repositoryStatus.value !== 'SUCCESS' || connectionStatus.value !== 'SUCCESS') {
        return
    }

    startingAnalysis.value = true
    notice.value = ''

    try {
        const savedProject = await ensureProject()
        await saveGitRepositoryConnection(savedProject.id, buildRepositoryConnectionPayload())
        await saveProjectConnection(savedProject.id, { ...databaseSource })

        const analysisJob = await startProjectAnalysis(savedProject.id)
        wizardStore.setAnalysisJob(analysisJob)

        await router.push({
            name: 'analysis-progress',
            params: {
                projectId: savedProject.id,
                analysisJobId: analysisJob.jobId,
            },
        })
    } catch (error) {
        showNotice(getErrorMessage(error), 'error')
    } finally {
        startingAnalysis.value = false
    }
}

onMounted(loadOrganizations)
</script>

<template>
    <div>
        <PageTitle eyebrow="STEP 1" title="프로젝트와 데이터베이스 연결" description="Git 저장소와 읽기 전용 DB 연결정보를 등록하고 실제 연결을 확인합니다." />

        <StepHeader :current="1" />

        <div
            v-if="notice"
            class="mb-5 rounded-xl border px-4 py-3 text-sm"
            :class="
                noticeType === 'error'
                    ? 'border-rose-200 bg-rose-50 text-rose-700 dark:border-rose-900/50 dark:bg-rose-950/30 dark:text-rose-300'
                    : 'border-emerald-200 bg-emerald-50 text-emerald-700 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-300'
            "
            role="status"
        >
            {{ notice }}
        </div>

        <ProjectConnectionStep
            :project="project"
            :organization-options="organizationOptions"
            :organizations-loading="organizationsLoading"
            :organization-load-error="organizationLoadError"
            :can-create-organization="canCreateOrganization"
            :creating-organization="creatingOrganization"
            :organization-create-error="organizationCreateError"
            :repository-connection="repositoryConnection"
            :database-source="databaseSource"
            :connection-status="connectionStatus"
            :connection-message="connectionMessage"
            :database-product="databaseProduct"
            :database-version="databaseVersion"
            :repository-status="repositoryStatus"
            :repository-message="repositoryMessage"
            :repository-branch="repositoryBranch"
            :repository-commit-sha="repositoryCommitSha"
            :saving="saving"
            :testing-repository="testingRepository"
            :testing-connection="testingConnection"
            :starting-analysis="startingAnalysis"
            @update:project="updateProjectForm"
            @create-organization="handleCreateOrganization"
            @update:repository-connection="updateRepositoryConnectionForm"
            @update:database-source="updateDatabaseSourceForm"
            @save="handleSave"
            @test-repository="handleTestRepository"
            @test-connection="handleTestConnection"
            @next="handleNext"
        />
    </div>
</template>
