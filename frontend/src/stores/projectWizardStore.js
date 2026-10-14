import { defineStore } from 'pinia'
import { ref } from 'vue'

const CONNECTION_STATUS = {
    IDLE: 'IDLE',
    TESTING: 'TESTING',
    SUCCESS: 'SUCCESS',
    FAILED: 'FAILED',
}

export const useProjectWizardStore = defineStore('projectWizard', () => {
    // 등록 화면을 이동해도 프로젝트 ID·연결 시험 결과·분석 작업 ID를 유지한다.
    const currentStep = ref(1)
    const projectId = ref(null)
    const analysisJobId = ref(null)
    const projectName = ref('')
    const sourceType = ref('GIT')
    const repositoryStatus = ref(CONNECTION_STATUS.IDLE)
    const repositoryMessage = ref('')
    const repositoryBranch = ref('')
    const repositoryCommitSha = ref('')
    const connectionStatus = ref(CONNECTION_STATUS.IDLE)
    const connectionMessage = ref('')
    const databaseProduct = ref('')
    const databaseVersion = ref('')

    const setProject = (project) => {
        projectId.value = project.id
        projectName.value = project.name
        sourceType.value = project.sourceType || 'GIT'
    }

    const setConnectionTesting = () => {
        connectionStatus.value = CONNECTION_STATUS.TESTING
        connectionMessage.value = '데이터베이스 연결을 확인하고 있습니다.'
        databaseProduct.value = ''
        databaseVersion.value = ''
    }

    const setRepositoryTesting = () => {
        repositoryStatus.value = CONNECTION_STATUS.TESTING
        repositoryMessage.value = 'Git 저장소와 브랜치를 확인하고 있습니다.'
        repositoryBranch.value = ''
        repositoryCommitSha.value = ''
    }

    const setRepositoryResult = (result) => {
        repositoryStatus.value = result.success ? CONNECTION_STATUS.SUCCESS : CONNECTION_STATUS.FAILED
        repositoryMessage.value = result.message || ''
        repositoryBranch.value = result.branch || ''
        repositoryCommitSha.value = result.commitSha || ''
    }

    const setRepositoryError = (message) => {
        repositoryStatus.value = CONNECTION_STATUS.FAILED
        repositoryMessage.value = message
        repositoryBranch.value = ''
        repositoryCommitSha.value = ''
    }

    const invalidateRepository = () => {
        repositoryStatus.value = CONNECTION_STATUS.IDLE
        repositoryMessage.value = ''
        repositoryBranch.value = ''
        repositoryCommitSha.value = ''
    }

    const setConnectionResult = (result) => {
        connectionStatus.value = result.success ? CONNECTION_STATUS.SUCCESS : CONNECTION_STATUS.FAILED
        connectionMessage.value = result.message || ''
        databaseProduct.value = result.databaseProduct || ''
        databaseVersion.value = result.databaseVersion || ''
    }

    const setConnectionError = (message) => {
        connectionStatus.value = CONNECTION_STATUS.FAILED
        connectionMessage.value = message
        databaseProduct.value = ''
        databaseVersion.value = ''
    }

    const invalidateConnection = () => {
        connectionStatus.value = CONNECTION_STATUS.IDLE
        connectionMessage.value = ''
        databaseProduct.value = ''
        databaseVersion.value = ''
    }

    const setAnalysisJob = (analysisJob) => {
        analysisJobId.value = analysisJob.jobId
        currentStep.value = 2
    }

    const resetWizard = () => {
        currentStep.value = 1
        projectId.value = null
        analysisJobId.value = null
        projectName.value = ''
        sourceType.value = 'GIT'
        invalidateRepository()
        invalidateConnection()
    }

    return {
        currentStep,
        projectId,
        analysisJobId,
        projectName,
        sourceType,
        repositoryStatus,
        repositoryMessage,
        repositoryBranch,
        repositoryCommitSha,
        connectionStatus,
        connectionMessage,
        databaseProduct,
        databaseVersion,
        setProject,
        setRepositoryTesting,
        setRepositoryResult,
        setRepositoryError,
        invalidateRepository,
        setConnectionTesting,
        setConnectionResult,
        setConnectionError,
        invalidateConnection,
        setAnalysisJob,
        resetWizard,
    }
})
