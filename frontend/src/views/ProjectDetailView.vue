<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getAnalysisHistory } from '@/api/appApi'
import { getDatabaseConnection, getGitRepositoryConnection } from '@/api/projectConnectionApi'
import { getProjectById, isProjectDemoMode } from '@/api/projectApi'
import { projectErrorMessage } from '@/lib/projectManagement'
import { createProjectRequestScope } from '@/lib/projectRequests'
import BaseButton from '@/components/base/BaseButton.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import ViewHeading from '@/components/common/ViewHeading.vue'
import { riskLevelLabel } from '@/lib/riskLevel'
import { useAuthStore } from '@/stores/authStore'

const route = useRoute()
const authStore = useAuthStore()
const project = ref(null)
const gitConnection = ref(null)
const databaseConnection = ref(null)
const latestAnalysis = ref(null)
const loading = ref(false)
const error = ref('')
const sectionErrors = ref({})
const requests = createProjectRequestScope()

const projectId = computed(() => String(route.params.projectId || ''))
const sourceTypeLabel = computed(() => ({ GIT: 'Git 저장소', ZIP: 'ZIP 업로드' })[project.value?.sourceType] || project.value?.sourceType || '정보 없음')
const repositoryUrl = computed(() => {
    if (!project.value?.repositoryUrl) return '정보 없음'
    const withoutQuery = String(project.value.repositoryUrl).split(/[?#]/, 1)[0]
    return withoutQuery.replace(/^([a-z][a-z0-9+.-]*:\/\/)[^/@]+@/i, '$1').replace(/^[^@/]+@(?=[^/]+:)/, '')
})
const connectionLabel = (status) => ({ CONNECTED: '연결됨', PENDING: '설정 대기', FAILED: '연결 실패' })[status] || status || '정보 없음'
const formatDate = (value) => {
    if (!value) return '정보 없음'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}
const display = (value) => value || '정보 없음'
const failureMessage = (failure, fallback) => failure?.response?.data?.message || failure?.response?.data?.detail || failure?.message || fallback

const loadProject = async () => {
    const ticket = requests.open(projectId.value)
    if (!ticket) return
    loading.value = true
    error.value = ''
    sectionErrors.value = {}
    project.value = null
    gitConnection.value = null
    databaseConnection.value = null
    latestAnalysis.value = null

    try {
        const result = await getProjectById(ticket.id, ticket.options)
        if (!requests.isCurrent(ticket)) return
        if (result.id !== ticket.id) throw new Error('프로젝트 응답이 요청한 프로젝트와 다릅니다.')
        project.value = result

        const [gitResult, databaseResult, historyResult] = await Promise.allSettled([
            result.sourceType === 'GIT' ? getGitRepositoryConnection(ticket.id, ticket.options) : Promise.resolve(null),
            getDatabaseConnection(ticket.id, ticket.options),
            authStore.hasPermission('ANALYSIS_READ')
                ? getAnalysisHistory({ page: 0, size: 1, projectId: ticket.id, ...ticket.options })
                : Promise.resolve(null),
        ])
        if (!requests.isCurrent(ticket)) return

        const errors = {}
        if (gitResult.status === 'fulfilled') gitConnection.value = gitResult.value
        else errors.git = failureMessage(gitResult.reason, 'Git 연결정보를 불러오지 못했습니다.')
        if (databaseResult.status === 'fulfilled') databaseConnection.value = databaseResult.value
        else errors.database = failureMessage(databaseResult.reason, 'DB 연결정보를 불러오지 못했습니다.')
        if (historyResult.status === 'fulfilled') latestAnalysis.value = historyResult.value?.items?.[0] || null
        else errors.history = failureMessage(historyResult.reason, '최근 분석을 불러오지 못했습니다.')
        sectionErrors.value = errors
    } catch (failure) {
        if (requests.isCurrent(ticket)) error.value = projectErrorMessage(failure)
    } finally {
        if (requests.isCurrent(ticket)) loading.value = false
    }
}

watch(projectId, loadProject, { immediate: true })
onBeforeUnmount(() => requests.dispose())
</script>

<template>
    <div class="min-w-0">
        <ViewHeading
            :title="project?.name || '프로젝트 상세'"
            description="프로젝트 설정과 저장된 분석을 확인합니다. 연결 비밀번호와 Git 토큰은 표시하지 않습니다."
        >
            <template #actions>
                <BaseButton class="w-full sm:w-auto" variant="secondary" :to="{ name: 'projects' }">목록으로</BaseButton>
                <BaseButton
                    v-if="project && authStore.hasPermission('PROJECT_UPDATE')"
                    class="w-full sm:w-auto"
                    variant="secondary"
                    :to="{ name: 'project-edit', params: { projectId } }"
                    >프로젝트 수정</BaseButton
                >
                <BaseButton
                    v-if="project && authStore.hasPermission('ANALYSIS_READ')"
                    class="w-full sm:w-auto"
                    variant="secondary"
                    :to="{ name: 'analysis-history', query: { projectId } }"
                    >분석 이력</BaseButton
                >
                <BaseButton v-if="project" class="w-full sm:w-auto" variant="primary" :to="{ name: 'change-requests' }">변경요청 관리</BaseButton>
            </template>
        </ViewHeading>

        <p v-if="isProjectDemoMode" role="status" class="mb-4 rounded-lg bg-blue-50 p-3 text-sm text-blue-800">
            데모 모드입니다. 이 브라우저에 저장된 프로젝트 기본정보를 표시합니다.
        </p>
        <p
            v-if="loading"
            role="status"
            class="rounded-xl border border-slate-200 bg-white p-6 text-sm text-slate-600 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-300"
        >
            프로젝트 정보를 불러오는 중입니다.
        </p>
        <div
            v-else-if="error"
            role="alert"
            class="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300"
        >
            <p>{{ error }}</p>
            <BaseButton class="mt-4" variant="secondary" @click="loadProject">다시 시도</BaseButton>
        </div>

        <div v-else-if="project" class="grid min-w-0 gap-5 xl:grid-cols-2">
            <section class="min-w-0 rounded-xl border border-slate-200 bg-white p-5 sm:p-6 dark:border-slate-700 dark:bg-slate-900">
                <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
                    <h2 class="text-lg font-bold text-slate-900 dark:text-slate-100">기본 정보</h2>
                    <StatusBadge :status="project.status" />
                </div>
                <dl class="grid min-w-0 gap-4 text-sm sm:grid-cols-2">
                    <div class="sm:col-span-2">
                        <dt class="text-slate-500 dark:text-slate-400">설명</dt>
                        <dd class="mt-1 break-words text-slate-800 dark:text-slate-200">{{ display(project.description) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">프로젝트 ID</dt>
                        <dd class="mt-1 break-all font-mono text-xs text-slate-800 dark:text-slate-200">{{ project.id }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">조직 ID</dt>
                        <dd class="mt-1 break-all font-mono text-xs text-slate-800 dark:text-slate-200">{{ display(project.organizationId) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">생성</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ formatDate(project.createdAt) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">최근 변경</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ formatDate(project.updatedAt) }}</dd>
                    </div>
                </dl>
            </section>

            <section class="min-w-0 rounded-xl border border-slate-200 bg-white p-5 sm:p-6 dark:border-slate-700 dark:bg-slate-900">
                <h2 class="mb-5 text-lg font-bold text-slate-900 dark:text-slate-100">프로젝트 소스</h2>
                <dl class="grid min-w-0 gap-4 text-sm sm:grid-cols-2">
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">방식</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ sourceTypeLabel }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">브랜치</dt>
                        <dd class="mt-1 break-all text-slate-800 dark:text-slate-200">
                            {{ project.sourceType === 'GIT' ? display(project.defaultBranch) : '해당 없음' }}
                        </dd>
                    </div>
                    <div v-if="project.sourceType === 'GIT'" class="sm:col-span-2">
                        <dt class="text-slate-500 dark:text-slate-400">저장소 URL</dt>
                        <dd class="mt-1 break-all text-slate-800 dark:text-slate-200">{{ repositoryUrl }}</dd>
                    </div>
                    <div v-if="project.sourceType === 'ZIP'" class="sm:col-span-2">
                        <dt class="text-slate-500 dark:text-slate-400">소스 파일</dt>
                        <dd class="mt-1 break-all text-slate-800 dark:text-slate-200">{{ display(project.sourceFileName) }}</dd>
                    </div>
                    <div v-if="project.sourceType === 'GIT'">
                        <dt class="text-slate-500 dark:text-slate-400">Git 연결</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">
                            {{ gitConnection ? connectionLabel(gitConnection.status) : sectionErrors.git ? '조회 실패' : '미설정' }}
                        </dd>
                    </div>
                    <div v-if="gitConnection">
                        <dt class="text-slate-500 dark:text-slate-400">확인된 커밋</dt>
                        <dd class="mt-1 break-all font-mono text-xs text-slate-800 dark:text-slate-200">{{ display(gitConnection.verifiedCommitSha) }}</dd>
                    </div>
                    <div v-if="gitConnection">
                        <dt class="text-slate-500 dark:text-slate-400">마지막 연결 확인</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ formatDate(gitConnection.lastTestedAt) }}</dd>
                    </div>
                </dl>
                <p v-if="sectionErrors.git" role="alert" class="mt-4 text-sm text-rose-700 dark:text-rose-300">{{ sectionErrors.git }}</p>
            </section>

            <section class="min-w-0 rounded-xl border border-slate-200 bg-white p-5 sm:p-6 dark:border-slate-700 dark:bg-slate-900">
                <h2 class="mb-5 text-lg font-bold text-slate-900 dark:text-slate-100">DB 연결</h2>
                <p v-if="sectionErrors.database" role="alert" class="text-sm text-rose-700 dark:text-rose-300">{{ sectionErrors.database }}</p>
                <p v-else-if="!databaseConnection" class="text-sm text-slate-500 dark:text-slate-400">저장된 DB 연결정보가 없습니다.</p>
                <dl v-else class="grid min-w-0 gap-4 text-sm sm:grid-cols-2">
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">상태</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ connectionLabel(databaseConnection.status) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">DB 종류</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ display(databaseConnection.dbType) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">호스트 · 포트</dt>
                        <dd class="mt-1 break-all text-slate-800 dark:text-slate-200">
                            {{ display(databaseConnection.host) }} : {{ display(databaseConnection.port) }}
                        </dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">데이터베이스</dt>
                        <dd class="mt-1 break-all text-slate-800 dark:text-slate-200">{{ display(databaseConnection.databaseName) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">스키마</dt>
                        <dd class="mt-1 break-all text-slate-800 dark:text-slate-200">{{ display(databaseConnection.schemaName) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">읽기 계정</dt>
                        <dd class="mt-1 break-all text-slate-800 dark:text-slate-200">{{ display(databaseConnection.username) }}</dd>
                    </div>
                    <div>
                        <dt class="text-slate-500 dark:text-slate-400">마지막 연결 확인</dt>
                        <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ formatDate(databaseConnection.lastTestedAt) }}</dd>
                    </div>
                </dl>
                <p class="mt-5 text-xs text-slate-500 dark:text-slate-400">접속 비밀번호는 이 화면과 조회 응답에 포함되지 않습니다.</p>
            </section>

            <section
                v-if="authStore.hasPermission('ANALYSIS_READ')"
                class="min-w-0 rounded-xl border border-slate-200 bg-white p-5 sm:p-6 dark:border-slate-700 dark:bg-slate-900"
            >
                <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
                    <h2 class="text-lg font-bold text-slate-900 dark:text-slate-100">최근 분석</h2>
                    <BaseButton size="small" variant="secondary" :to="{ name: 'analysis-history', query: { projectId } }">전체 이력</BaseButton>
                </div>
                <p v-if="sectionErrors.history" role="alert" class="text-sm text-rose-700 dark:text-rose-300">{{ sectionErrors.history }}</p>
                <p v-else-if="!latestAnalysis" class="text-sm text-slate-500 dark:text-slate-400">저장된 분석 이력이 없습니다.</p>
                <template v-else>
                    <div class="flex flex-wrap items-center gap-2">
                        <StatusBadge :status="latestAnalysis.status" />
                        <span class="text-sm font-semibold text-slate-800 dark:text-slate-200">{{
                            latestAnalysis.requirementTitle || '프로젝트 구조 분석'
                        }}</span>
                    </div>
                    <dl class="mt-4 grid gap-4 text-sm sm:grid-cols-2">
                        <div>
                            <dt class="text-slate-500 dark:text-slate-400">위험도</dt>
                            <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ riskLevelLabel(latestAnalysis.riskLevel) }}</dd>
                        </div>
                        <div>
                            <dt class="text-slate-500 dark:text-slate-400">요청 시각</dt>
                            <dd class="mt-1 text-slate-800 dark:text-slate-200">{{ formatDate(latestAnalysis.createdAt) }}</dd>
                        </div>
                    </dl>
                    <BaseButton
                        class="mt-5 w-full sm:w-auto"
                        variant="secondary"
                        :to="{ name: 'analysis-progress', params: { projectId, analysisJobId: latestAnalysis.jobId } }"
                        >{{ latestAnalysis.status === 'COMPLETED' ? '저장된 결과 보기' : '분석 상태 보기' }}</BaseButton
                    >
                </template>
            </section>
        </div>
    </div>
</template>
