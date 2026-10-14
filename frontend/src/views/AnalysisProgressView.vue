<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink, useRoute } from 'vue-router'
import PageTitle from '@/components/common/PageTitle.vue'
import { cancelAnalysisJob, getAnalysisJob, getAnalysisResult, isAnalysisDemoMode } from '@/api/analysisApi'
import { createAnalysisPoller } from '@/lib/analysisPolling'
import { activeAnalysisStage, analysisActivity, analysisElapsedSeconds, formatAnalysisElapsed } from '@/lib/analysisProgress'
import { shouldConfirmAnalysisExit, warnOnAnalysisUnload } from '@/lib/analysisNavigation'
import { riskLevelLabel } from '@/lib/riskLevel'

const route = useRoute()
const job = ref(null)
const analysis = ref(null)
const loading = ref(false)
const retryScheduled = ref(false)
const requestError = ref(null)
const cancelBusy = ref(false)
const cancelError = ref('')
const lastCheckedAt = ref(null)
const clock = ref(Date.now())
let elapsedTimer = null

// 브라우저 뒤로가기·앱 내부 이동은 화면만 벗어난다. 서버 분석을 자동 취소하지 않는다.
const confirmLeavingRunningJob = () => {
    if (!shouldConfirmAnalysisExit(job.value?.status)) return true
    return window.confirm(
        '분석 화면을 나가시겠습니까? 서버 작업은 계속되며 분석 이력에서 다시 확인할 수 있습니다. 작업 자체를 멈추려면 이 화면의 “분석 작업 취소”를 사용하세요.'
    )
}
onBeforeRouteLeave(confirmLeavingRunningJob)
onBeforeRouteUpdate((to) => (to.params.analysisJobId === route.params.analysisJobId ? true : confirmLeavingRunningJob()))

// 새로고침·탭/창 닫기에는 브라우저 기본 경고만 가능하며 비동기 취소 요청은 보장되지 않는다.
const warnBeforeUnload = (event) => {
    warnOnAnalysisUnload(event, job.value?.status)
}

const statusLabels = { PENDING: '분석 대기', RUNNING: '분석 진행 중', COMPLETED: '분석 완료', FAILED: '분석 실패', CANCELLED: '분석 취소' }
const statusLabel = computed(() => statusLabels[job.value?.status] || job.value?.status || '작업 확인 중')
const isRequirementAnalysis = computed(() => Boolean(job.value?.requirementVersionId))
const statusDescription = computed(() => {
    if (job.value?.status === 'COMPLETED') return '분석이 완료되었습니다. 아래에서 저장된 결과와 수집 근거를 확인할 수 있습니다.'
    if (job.value?.status === 'FAILED') return '작업이 실패하여 자동 조회를 중지했습니다. 오류 원인을 해결한 뒤 프로젝트에서 새 분석을 시작하세요.'
    if (job.value?.status === 'CANCELLED') return '취소된 분석 작업입니다.'
    if (job.value?.status === 'PENDING') return '분석 서버가 작업을 시작하기를 기다리고 있습니다.'
    return '서버에서 소스코드와 DB 구조를 수집하고 분석하고 있습니다.'
})

const errorMessage = computed(() => {
    const error = requestError.value?.error
    return error?.response?.data?.message || error?.response?.data?.detail || error?.message || '요청을 처리하지 못했습니다.'
})

const cancelJob = async () => {
    if (!window.confirm('분석 작업을 취소할까요? 화면에서만 나가려면 “이력으로 나가기”를 선택하세요.')) return
    cancelBusy.value = true
    cancelError.value = ''
    try {
        const updated = await cancelAnalysisJob(route.params.analysisJobId)
        job.value = updated
        if (updated.status === 'CANCELLED') {
            poller.stop()
            loading.value = false
            retryScheduled.value = false
        } else {
            poller.refresh()
        }
    } catch (error) {
        cancelError.value = error.response?.data?.message || error.message || '분석을 취소하지 못했습니다.'
    } finally {
        cancelBusy.value = false
    }
}

// 작업 상태를 자동 조회하고 COMPLETED일 때만 저장된 결과를 한 번 읽는다.
const poller = createAnalysisPoller({
    loadJob: getAnalysisJob,
    loadResult: getAnalysisResult,
    onJob: (value) => {
        job.value = value
        lastCheckedAt.value = new Date()
    },
    onResult: (value) => {
        analysis.value = value
    },
    onError: (value) => {
        requestError.value = value
    },
    onState: (value) => {
        loading.value = value.loading
        retryScheduled.value = value.retryScheduled
    },
})

watch(
    () => [route.params.projectId, route.params.analysisJobId],
    ([, jobId]) => {
        job.value = null
        analysis.value = null
        requestError.value = null
        lastCheckedAt.value = null
        poller.start(jobId)
    },
    { immediate: true }
)

onMounted(() => {
    window.addEventListener('beforeunload', warnBeforeUnload)
    elapsedTimer = setInterval(() => {
        if (job.value?.status === 'PENDING' || job.value?.status === 'RUNNING') clock.value = Date.now()
    }, 1000)
})
onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', warnBeforeUnload)
    poller.stop()
    if (elapsedTimer !== null) clearInterval(elapsedTimer)
})

const asList = (value) => (Array.isArray(value) ? value : [])
const displayValue = (value) => {
    if (value === null || value === undefined) return '정보 없음'
    return typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)
}
const percentage = (value) => (typeof value === 'number' && Number.isFinite(value) ? Math.min(100, Math.max(0, value)) : null)
const progressText = (value) => (percentage(value) === null ? '진행률 미제공' : `${percentage(value)}%`)
const activeStage = computed(() => activeAnalysisStage(job.value))
const activityText = computed(() => analysisActivity(job.value))
const elapsedText = computed(() => formatAnalysisElapsed(analysisElapsedSeconds(job.value, clock.value)))
const formatDate = (value) => {
    if (!value) return '—'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('ko-KR')
}
const count = (value) => (Array.isArray(value) ? `${value.length.toLocaleString('ko-KR')}개` : '정보 없음')
// result는 AI 판단, facts는 AI에 실제 전달된 소스·DB 근거다.
const source = computed(() => analysis.value?.facts?.sourceSummary || {})
const database = computed(() => analysis.value?.facts?.databaseSummary || {})
const aiResult = computed(() => analysis.value?.result || {})
const plainLanguageSummary = computed(() => {
    const value = aiResult.value.plainLanguageSummary
    return typeof value === 'string' && value.trim() ? value.trim() : ''
})
const databaseImpact = computed(() => aiResult.value.databaseImpact || null)
const databaseImpactLabels = {
    CHANGE_REQUIRED: 'DB 변경 필요',
    NO_CHANGE: 'DB 변경 불필요',
    NEEDS_CLARIFICATION: '요구사항 확인 필요',
    UNKNOWN: '판단 근거 부족',
    NOT_APPLICABLE: 'DB 분석 대상 아님',
}
const databaseChangeLabels = {
    CREATE_TABLE: '테이블 생성',
    ADD_COLUMN: '컬럼 추가',
    ALTER_COLUMN: '컬럼 변경',
    DROP_COLUMN: '컬럼 삭제',
    ADD_CONSTRAINT: '제약조건 추가',
    ALTER_CONSTRAINT: '제약조건 변경',
    DATA_MIGRATION: '데이터 이관',
    OTHER: '기타 변경',
}
const frameworks = computed(() => asList(aiResult.value.frameworks))
const sourceTechnologies = computed(() =>
    asList(source.value.frameworks)
        .map((framework) => (typeof framework === 'string' ? framework : framework.name))
        .filter(Boolean)
)
const riskLevel = computed(() => analysis.value?.riskLevel ?? aiResult.value.riskLevel ?? 'UNKNOWN')
const riskScore = computed(() => analysis.value?.riskScore ?? aiResult.value.riskScore)
const databaseColumnCount = computed(() => {
    if (!Array.isArray(database.value.tables) || database.value.tables.some((table) => !Array.isArray(table.columns))) return '정보 없음'
    return `${database.value.tables.reduce((total, table) => total + table.columns.length, 0).toLocaleString('ko-KR')}개`
})
const stages = computed(() => [
    {
        name: '소스코드 수집',
        icon: 'SRC',
        color: 'blue-bg',
        active: activeStage.value === 'SRC',
        progress: job.value?.sourceProgress,
        metrics: [
            { label: '구성요소', value: count(source.value.components) },
            { label: '감지된 프레임워크', value: count(source.value.frameworks) },
        ],
    },
    {
        name: 'DB 스키마 수집',
        icon: 'DB',
        color: 'purple-bg',
        fill: 'purple-fill',
        active: activeStage.value === 'DB',
        progress: job.value?.databaseProgress,
        metrics: [
            { label: '테이블', value: count(database.value.tables) },
            { label: '컬럼', value: databaseColumnCount.value },
        ],
    },
    {
        name: '관계 및 AI 분석',
        icon: 'AI',
        color: 'orange-bg',
        fill: 'orange-fill',
        active: activeStage.value === 'AI',
        progress: job.value?.relationProgress,
        metrics: [{ label: '수집된 관계', value: count(analysis.value?.facts?.relations) }],
    },
])
const collectionWarnings = computed(() => [
    ...asList(source.value.detectionWarnings),
    ...asList(source.value.limitations),
    ...asList(database.value.warnings),
    ...(database.value.truncated ? ['DB 수집 범위 제한으로 일부 스키마가 생략되었습니다.'] : []),
    ...(source.value.promptCoverage?.truncated
        ? [
              `AI 입력 크기 제한으로 수집 범위 중 테이블 ${source.value.promptCoverage.includedTables}/${source.value.promptCoverage.collectedTables}개, 관계 ${source.value.promptCoverage.includedRelations}/${source.value.promptCoverage.collectedRelations}개를 분석했습니다.`,
          ]
        : []),
])
const evidenceEntries = (evidence) => (evidence && typeof evidence === 'object' && !Array.isArray(evidence) ? Object.entries(evidence) : [['근거', evidence]])
</script>

<template>
    <div class="analysis-page">
        <header class="analysis-header">
            <PageTitle
                class="analysis-heading"
                :eyebrow="isRequirementAnalysis ? 'CHANGE IMPACT' : 'PROJECT ANALYSIS'"
                :title="isRequirementAnalysis ? '변경 영향 분석 상태 및 결과' : '프로젝트 분석 상태 및 결과'"
                :description="
                    isRequirementAnalysis
                        ? '선택한 요구사항 버전과 저장소·DB 근거를 바탕으로 영향 범위를 확인합니다.'
                        : '저장소와 DB에서 수집한 사실을 바탕으로 분석 작업의 상태와 결과를 확인합니다.'
                "
            />
            <div class="analysis-header-tools">
                <div class="analysis-header-actions">
                    <RouterLink class="button secondary" :to="{ name: 'analysis-history', query: { projectId: route.params.projectId } }">
                        이력으로 나가기
                    </RouterLink>
                    <button
                        v-if="job && ['PENDING', 'RUNNING'].includes(job.status)"
                        type="button"
                        class="button analysis-cancel-button"
                        :disabled="cancelBusy"
                        @click="cancelJob"
                    >
                        {{ cancelBusy ? '취소 요청 중…' : '분석 작업 취소' }}
                    </button>
                </div>
                <p v-if="shouldConfirmAnalysisExit(job?.status)" class="analysis-header-hint">
                    화면을 나가도 서버 분석은 계속되며, 분석 이력에서 다시 확인할 수 있습니다.
                </p>
            </div>
        </header>

        <p v-if="isAnalysisDemoMode" class="analysis-notice">데모 모드: 아래 상태와 결과는 화면 확인용 샘플이며 실제 분석 결과가 아닙니다.</p>
        <p v-if="cancelError" role="alert" class="analysis-notice error-notice">{{ cancelError }}</p>

        <div class="analysis-toolbar flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <p role="status" aria-live="polite">
                <template v-if="loading">{{ job?.status === 'COMPLETED' ? '저장된 분석 결과를 불러오는 중…' : '작업 상태를 확인하는 중…' }}</template>
                <template v-else-if="retryScheduled">{{
                    requestError ? '조회 실패 · 약 2.5초 후 자동 재시도합니다.' : '약 2.5초 간격으로 자동 갱신합니다.'
                }}</template>
                <template v-else>자동 조회가 중지되었습니다.</template>
                <span v-if="lastCheckedAt"> · 마지막 확인 {{ formatDate(lastCheckedAt) }}</span>
            </p>
            <button class="button secondary w-full shrink-0 sm:w-auto" :disabled="loading" @click="poller.refresh()">
                {{ requestError?.phase === 'result' ? '결과 다시 불러오기' : '상태 다시 확인' }}
            </button>
        </div>

        <section v-if="requestError" class="analysis-notice error-notice" role="alert">
            <strong>{{ requestError.phase === 'result' ? '분석 결과를 불러오지 못했습니다.' : '작업 상태를 불러오지 못했습니다.' }}</strong>
            <p>{{ errorMessage }}</p>
            <p v-if="!retryScheduled && !loading">자동 재시도를 중지했습니다. 연결 상태와 접근 권한을 확인한 후 다시 불러오세요.</p>
        </section>

        <section v-if="job" class="hero-progress panel">
            <div class="hero-progress-header">
                <div>
                    <span class="badge" :class="job.status === 'COMPLETED' ? 'green' : job.status === 'FAILED' ? 'orange' : 'blue'">{{ statusLabel }}</span>
                    <h2>{{ statusLabel }}</h2>
                    <p>{{ statusDescription }}</p>
                </div>
                <div class="progress-score">{{ progressText(job.overallProgress) }}</div>
            </div>
            <div
                class="progress-track large"
                :class="{ 'is-active': job.status === 'PENDING' || job.status === 'RUNNING' }"
                role="progressbar"
                aria-label="전체 분석 진행률"
                aria-valuemin="0"
                aria-valuemax="100"
                :aria-valuenow="percentage(job.overallProgress) ?? undefined"
            >
                <div class="progress-fill" :style="{ width: `${percentage(job.overallProgress) ?? 0}%` }"></div>
            </div>
            <p v-if="activityText" class="analysis-activity">
                <span class="activity-dot" aria-hidden="true"></span>
                {{ activityText }} · 경과 {{ elapsedText }}
                <span class="activity-hint">실제 진행률은 서버의 단계 완료 시 갱신됩니다.</span>
            </p>
            <p v-else-if="job.completedAt" class="analysis-activity completed-activity">총 소요 {{ elapsedText }}</p>
            <dl class="job-metadata">
                <div>
                    <dt>작업 ID</dt>
                    <dd>{{ job.jobId }}</dd>
                </div>
                <div v-if="job.requirementVersionId">
                    <dt>분석 대상 요구사항</dt>
                    <dd>{{ source.changeRequirement?.title || `버전 ${job.requirementVersionId}` }}</dd>
                </div>
                <div>
                    <dt>요청</dt>
                    <dd>{{ formatDate(job.createdAt) }}</dd>
                </div>
                <div>
                    <dt>시작</dt>
                    <dd>{{ formatDate(job.startedAt) }}</dd>
                </div>
                <div>
                    <dt>종료</dt>
                    <dd>{{ formatDate(job.completedAt) }}</dd>
                </div>
            </dl>
            <div v-if="job.status === 'FAILED'" class="analysis-notice error-notice" role="alert">
                <strong>분석 실패 사유</strong>
                <p>{{ job.errorMessage || '서버가 상세 오류를 제공하지 않았습니다.' }}</p>
            </div>
        </section>

        <div v-if="job" class="grid three-columns">
            <section v-for="stage in stages" :key="stage.icon" class="panel progress-panel" :class="{ 'active-panel': stage.active }">
                <div class="progress-panel-icon" :class="stage.color">{{ stage.icon }}</div>
                <h3>{{ stage.name }}</h3>
                <div
                    class="progress-track"
                    :class="{ 'is-active': stage.active }"
                    role="progressbar"
                    :aria-label="stage.name"
                    aria-valuemin="0"
                    aria-valuemax="100"
                    :aria-valuenow="percentage(stage.progress) ?? undefined"
                >
                    <div class="progress-fill" :class="stage.fill" :style="{ width: `${percentage(stage.progress) ?? 0}%` }"></div>
                </div>
                <strong>{{ progressText(stage.progress) }}</strong>
                <p v-if="stage.active" class="stage-activity">현재 진행 중</p>
                <dl v-if="analysis" class="metric-list">
                    <div v-for="metric in stage.metrics" :key="metric.label">
                        <dt>{{ metric.label }}</dt>
                        <dd>{{ metric.value }}</dd>
                    </div>
                </dl>
                <p v-else>수집 개수는 완료된 분석 결과에서 확인할 수 있습니다.</p>
            </section>
        </div>

        <template v-if="analysis">
            <section class="panel analysis-section plain-language-section">
                <div class="panel-heading">
                    <div>
                        <h2>쉽게 보는 한 줄 요약</h2>
                        <p>고객·PM을 위한 설명</p>
                    </div>
                    <span class="badge blue">AI 초안 · 검토 전</span>
                </div>
                <p v-if="plainLanguageSummary" class="plain-language-copy">{{ plainLanguageSummary }}</p>
                <p v-else class="empty-copy">이전 분석 결과에는 쉬운 요약이 없습니다. 아래 기술 요약을 확인하세요.</p>
            </section>

            <section class="panel analysis-section">
                <div class="panel-heading">
                    <div>
                        <h2>분석 요약</h2>
                        <p>결과 저장 시각: {{ formatDate(analysis.createdAt) }}</p>
                    </div>
                    <span class="badge" :class="['HIGH', 'CRITICAL'].includes(riskLevel) ? 'orange' : 'blue'">
                        위험도 {{ riskLevelLabel(riskLevel) }}{{ typeof riskScore === 'number' ? ` · ${riskScore}/100` : '' }}
                    </span>
                </div>
                <p class="result-text">{{ analysis.summary || aiResult.summary || '제공된 요약이 없습니다.' }}</p>
                <div v-if="frameworks.length" class="tag-list compact">
                    <span v-for="framework in frameworks" :key="framework">{{ framework }}</span>
                </div>
                <h3 class="subheading">영향 영역</h3>
                <ul v-if="asList(aiResult.impactAreas).length" class="result-list">
                    <li v-for="(area, index) in aiResult.impactAreas" :key="index">{{ displayValue(area) }}</li>
                </ul>
                <p v-else class="empty-copy">보고된 영향 영역이 없습니다.</p>
            </section>

            <section v-if="isRequirementAnalysis" class="panel analysis-section">
                <div class="panel-heading">
                    <div>
                        <h2>DB 변경 영향 판단</h2>
                        <p>실제 DB에 변경을 적용하지 않은 AI 분석 결과입니다.</p>
                    </div>
                    <span v-if="databaseImpact" class="badge" :class="databaseImpact.status === 'CHANGE_REQUIRED' ? 'orange' : 'blue'">
                        {{ databaseImpactLabels[databaseImpact.status] || databaseImpact.status }}
                    </span>
                </div>
                <template v-if="databaseImpact">
                    <p class="result-text">{{ databaseImpact.explanation }}</p>
                    <div v-if="asList(databaseImpact.observedTables).length" class="database-impact-group">
                        <h3 class="subheading">확인한 기존 테이블</h3>
                        <ul class="result-list">
                            <li v-for="table in databaseImpact.observedTables" :key="table">{{ table }}</li>
                        </ul>
                    </div>
                    <div v-if="asList(databaseImpact.proposedChanges).length" class="database-impact-group">
                        <h3 class="subheading">제안된 DB 변경</h3>
                        <ol class="recommendation-list">
                            <li v-for="(change, index) in databaseImpact.proposedChanges" :key="index">
                                <strong
                                    >{{ change.targetTable }}{{ change.targetColumn ? `.${change.targetColumn}` : '' }} ·
                                    {{ databaseChangeLabels[change.changeType] || change.changeType }}</strong
                                >
                                <p>{{ change.description }}</p>
                                <p class="database-change-reason">이유: {{ change.rationale }}</p>
                            </li>
                        </ol>
                    </div>
                    <div v-if="asList(databaseImpact.conditionalPaths).length" class="database-impact-group">
                        <h3 class="subheading">조건별 DB 영향 (확정 아님)</h3>
                        <ul class="result-list">
                            <li v-for="(path, index) in databaseImpact.conditionalPaths" :key="index">
                                <strong>{{ path.condition }}</strong
                                >: {{ path.databaseEffect }}
                            </li>
                        </ul>
                    </div>
                    <div v-if="asList(databaseImpact.openQuestions).length" class="database-impact-group">
                        <h3 class="subheading">확인할 사항</h3>
                        <ul class="result-list">
                            <li v-for="(question, index) in databaseImpact.openQuestions" :key="index">{{ question }}</li>
                        </ul>
                    </div>
                </template>
                <p v-else class="empty-copy">
                    이 분석은 새 DB 영향 판단 형식 도입 전에 저장돼 세부 변경안을 제공하지 않습니다. 다시 분석하면 확인할 수 있습니다.
                </p>
            </section>

            <div class="grid two-columns analysis-section">
                <section class="panel">
                    <div class="panel-heading">
                        <div>
                            <h2>분석 근거</h2>
                            <p>AI가 분석에 인용한 수집 정보입니다. 실제 코드 및 DB와 함께 검토하세요.</p>
                        </div>
                    </div>
                    <ol v-if="asList(aiResult.evidences).length" class="evidence-list">
                        <li v-for="(evidence, index) in aiResult.evidences" :key="index">
                            <dl class="evidence-details">
                                <div v-for="[key, value] in evidenceEntries(evidence)" :key="key">
                                    <dt>{{ key }}</dt>
                                    <dd>{{ displayValue(value) }}</dd>
                                </div>
                            </dl>
                        </li>
                    </ol>
                    <p v-else class="empty-copy">제공된 분석 근거가 없습니다.</p>
                </section>
                <section class="panel">
                    <div class="panel-heading">
                        <div>
                            <h2>권장 대응 방안</h2>
                            <p>구현 전 검토할 항목</p>
                        </div>
                    </div>
                    <ol v-if="asList(aiResult.recommendations).length" class="recommendation-list">
                        <li v-for="(recommendation, index) in aiResult.recommendations" :key="index">{{ displayValue(recommendation) }}</li>
                    </ol>
                    <p v-else class="empty-copy">제공된 권장 사항이 없습니다.</p>
                </section>
            </div>

            <section class="panel analysis-section">
                <div class="panel-heading">
                    <div>
                        <h2>수집 범위와 한계</h2>
                        <p>AI 분석에 전달된 사실과 수집 경고입니다. 목록은 수집된 범위에 한정됩니다.</p>
                    </div>
                </div>
                <dl class="metric-list">
                    <div>
                        <dt>언어</dt>
                        <dd>{{ asList(source.languages).join(', ') || '정보 없음' }}</dd>
                    </div>
                    <div>
                        <dt>프레임워크</dt>
                        <dd>{{ sourceTechnologies.join(', ') || '감지된 항목 없음' }}</dd>
                    </div>
                    <div>
                        <dt>DB 유형 / 스키마</dt>
                        <dd>{{ database.type || '정보 없음' }} / {{ database.schema || '정보 없음' }}</dd>
                    </div>
                </dl>
                <ul v-if="collectionWarnings.length" class="result-list collection-warnings">
                    <li v-for="(warning, index) in collectionWarnings" :key="index">{{ displayValue(warning) }}</li>
                </ul>
                <details class="facts-details">
                    <summary>수집 사실 원본 보기</summary>
                    <pre>{{ JSON.stringify(analysis.facts, null, 2) }}</pre>
                </details>
            </section>
        </template>

        <div class="page-actions">
            <RouterLink class="button secondary" :to="isRequirementAnalysis ? '/change-requests' : '/projects'">
                {{ isRequirementAnalysis ? '변경요청 목록' : '프로젝트 목록' }}
            </RouterLink>
        </div>
    </div>
</template>

<style scoped>
.analysis-header {
    display: flex;
    flex-direction: column;
    gap: 20px;
    margin-bottom: 24px;
}
.analysis-heading,
.analysis-header-tools {
    min-width: 0;
}
.analysis-header-tools {
    display: flex;
    flex-direction: column;
    gap: 8px;
}
.analysis-header-actions {
    display: flex;
    flex-direction: column;
    gap: 8px;
}
.analysis-header-actions .button {
    width: 100%;
}
.analysis-header-hint {
    margin: 0;
    color: #667188;
    font-size: 12px;
    line-height: 1.5;
}
.analysis-cancel-button {
    border-color: #fecdd3;
    background: #fff1f2;
    color: #be123c;
}
.analysis-cancel-button:hover:not(:disabled) {
    border-color: #fda4af;
    background: #ffe4e6;
}
:global(.dark) .analysis-cancel-button {
    border-color: #7f1d1d;
    background: #451a1a;
    color: #fecdd3;
}
:global(.dark) .analysis-cancel-button:hover:not(:disabled) {
    background: #631e28;
}
:global(.dark) .analysis-header-hint {
    color: #a5b4c9;
}
@media (min-width: 560px) {
    .analysis-header-actions {
        flex-direction: row;
        flex-wrap: wrap;
        justify-content: flex-end;
    }
    .analysis-header-actions .button {
        width: auto;
    }
    .analysis-header-hint {
        text-align: right;
    }
}
@media (min-width: 1280px) {
    .analysis-header {
        flex-direction: row;
        align-items: flex-start;
        justify-content: space-between;
        gap: 24px;
    }
    .analysis-heading {
        flex: 1 1 auto;
    }
    .analysis-header-tools {
        flex: 0 1 420px;
        align-items: flex-end;
    }
}
.analysis-toolbar,
.metric-list > div {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
}
.analysis-toolbar {
    margin-bottom: 20px;
}
.analysis-toolbar p,
.empty-copy {
    color: #667188;
    font-size: 13px;
}
.analysis-toolbar .button {
    flex-shrink: 0;
}
.analysis-notice {
    margin-bottom: 20px;
    border: 1px solid #f0d6ab;
    border-radius: 12px;
    background: #fff9ed;
    padding: 16px;
    color: #80571a;
    font-size: 13px;
    overflow-wrap: anywhere;
}
.error-notice {
    border-color: #f0c3c3;
    background: #fff3f3;
    color: #a23434;
}
.analysis-notice p {
    margin: 8px 0 0;
}
.job-metadata {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 16px;
    margin-top: 24px;
    font-size: 12px;
}
.job-metadata dt,
.metric-list dt,
.evidence-details dt {
    color: #667188;
}
.job-metadata dd,
.metric-list dd,
.evidence-details dd {
    margin: 6px 0 0;
    overflow-wrap: anywhere;
}
.metric-list {
    display: grid;
    gap: 12px;
    font-size: 13px;
}
.metric-list dd {
    margin: 0;
    text-align: right;
}
.analysis-section {
    margin-top: 20px;
}
.plain-language-section {
    border: 1px solid #b8cafa;
    background: #f3f7ff;
}
.plain-language-copy {
    margin: 0;
    color: #1e3a6d;
    font-size: clamp(16px, 2vw, 19px);
    font-weight: 700;
    line-height: 1.65;
    overflow-wrap: anywhere;
}
:global(html.dark .plain-language-section) {
    border-color: #365389;
    background: #14213d;
}
:global(html.dark .plain-language-copy) {
    color: #dbeafe;
}
.analysis-activity {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 7px;
    margin: 12px 0 0;
    color: #435273;
    font-size: 13px;
}
.activity-dot {
    width: 8px;
    height: 8px;
    flex: 0 0 auto;
    border-radius: 50%;
    background: #5373e9;
    animation: activity-pulse 1.6s ease-in-out infinite;
}
.activity-hint {
    color: #667188;
}
.completed-activity,
.stage-activity {
    color: #667188;
}
.stage-activity {
    margin: -8px 0 14px;
    font-size: 12px;
}
@keyframes activity-pulse {
    50% {
        opacity: 0.35;
    }
}
@media (prefers-reduced-motion: reduce) {
    .activity-dot {
        animation: none;
    }
}
.database-impact-group {
    margin-top: 16px;
}
.database-impact-group .subheading {
    margin-top: 0 !important;
}
.database-impact-group p {
    margin: 8px 0 0;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
}
.database-change-reason {
    color: #667188;
}
.result-text,
.result-list,
.evidence-details dd,
.recommendation-list {
    white-space: pre-wrap;
    overflow-wrap: anywhere;
    line-height: 1.7;
}
.result-list {
    padding-left: 22px;
}
.subheading {
    margin-top: 24px !important;
}
.evidence-list {
    padding-left: 22px;
}
.evidence-list > li + li {
    margin-top: 18px;
    border-top: 1px solid #e3e7ef;
    padding-top: 18px;
}
.evidence-details {
    display: grid;
    gap: 10px;
    font-size: 13px;
}
.collection-warnings {
    color: #80571a;
    font-size: 13px;
}
.facts-details {
    margin-top: 24px;
    font-size: 13px;
}
.facts-details summary {
    cursor: pointer;
    color: #526077;
}
.facts-details pre {
    max-height: 420px;
    overflow: auto;
    border-radius: 10px;
    background: #f5f7fb;
    padding: 16px;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
}
@media (max-width: 760px) {
    .analysis-toolbar,
    .hero-progress-header,
    .panel-heading {
        align-items: flex-start;
        flex-direction: column;
        gap: 16px;
    }
    .three-columns,
    .two-columns,
    .job-metadata {
        grid-template-columns: minmax(0, 1fr);
    }
}
</style>
