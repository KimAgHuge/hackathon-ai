<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, RouterLink, useRoute, useRouter } from 'vue-router'
import PageTitle from '@/components/common/PageTitle.vue'
import BaseTextarea from '@/components/base/BaseTextarea.vue'
import {
    cancelAnalysisJob,
    getAnalysisJob,
    getAnalysisResult,
    getTrainingCaseDraft,
    getTrainingTransferStatus,
    isAnalysisDemoMode,
    reviewAnalysisResult,
    publishTrainingCase,
    startProjectAnalysis,
} from '@/api/analysisApi'
import { getAllRequirements, createRequirementFollowUp } from '@/api/requirementApi'
import { createAnalysisPoller } from '@/lib/analysisPolling'
import {
    activeActivityEvent,
    activeAnalysisStage,
    analysisActivity,
    analysisActivityEvents,
    analysisElapsedSeconds,
    formatAnalysisElapsed,
} from '@/lib/analysisProgress'
import { shouldConfirmAnalysisExit, warnOnAnalysisUnload } from '@/lib/analysisNavigation'
import { buildFollowUpPayload, canContinueFromAnalysis, savedFollowUpDraft } from '@/lib/followUpWorkflow'
import { buildAnalysisReviewPayload } from '@/lib/analysisReview'
import { applyTrainingReviewConfirmations } from '@/lib/trainingReview'
import { riskPresentation } from '@/lib/riskLevel'
import { useAuthStore } from '@/stores/authStore'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const job = ref(null)
const analysis = ref(null)
const loading = ref(false)
const retryScheduled = ref(false)
const requestError = ref(null)
const cancelBusy = ref(false)
const cancelError = ref('')
const followUpAnswers = ref([])
const followUpQuestion = ref('')
const followUpBusy = ref(false)
const followUpError = ref('')
const followUpSavedVersionId = ref('')
const previousQuestionJobId = ref('')
const reviewNote = ref('')
const reviewUseCorrection = ref(false)
const reviewCorrectedText = ref('')
const reviewBusy = ref(false)
const reviewError = ref('')
const reviewNotice = ref('')
const trainingDraftBusy = ref(false)
const trainingStatusBusy = ref(false)
const trainingDraftError = ref('')
const trainingEditorOpen = ref(false)
const trainingCaseText = ref('')
const trainingReviewRequired = ref([])
const trainingAnswerChecked = ref(false)
const trainingSanitizedChecked = ref(false)
const trainingTransfer = ref(null)
let trainingTimer = null
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
    if (job.value?.status === 'FAILED') return '작업이 실패하여 자동 조회를 중지했습니다. 이전 완료 분석의 질문으로 돌아가 답변을 수정할 수 있습니다.'
    if (job.value?.status === 'CANCELLED') return '취소된 분석 작업입니다. 이전 완료 분석의 질문에서 다시 이어갈 수 있습니다.'
    if (job.value?.status === 'PENDING') return '서비스 서버가 분석 작업을 시작하기를 기다리고 있습니다.'
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

watch(
    () => [job.value?.jobId, job.value?.status],
    async ([jobId, status]) => {
        previousQuestionJobId.value = ''
        if (!jobId || !['FAILED', 'CANCELLED'].includes(status) || !job.value?.requirementVersionId) return
        try {
            const requirements = await getAllRequirements()
            if (job.value?.jobId !== jobId || job.value?.status !== status) return
            const requirement = requirements.find((item) => item.currentVersion?.id === job.value.requirementVersionId)
            previousQuestionJobId.value = requirement?.currentVersion?.sourceAnalysisJobId || ''
        } catch {
            // 요구사항 조회 권한이 없거나 연결이 끊겨도 분석 상태 화면은 유지한다.
        }
    },
    { immediate: true }
)

watch(analysis, async (value, previous) => {
    if (value?.jobId && value.jobId === previous?.jobId) return
    followUpAnswers.value = []
    followUpQuestion.value = ''
    followUpError.value = ''
    followUpSavedVersionId.value = ''
    reviewNote.value = ''
    reviewUseCorrection.value = false
    reviewError.value = ''
    reviewNotice.value = ''
    reviewCorrectedText.value = JSON.stringify(analysis.value?.correctedResult || analysis.value?.result || {}, null, 2)
    trainingEditorOpen.value = false
    trainingCaseText.value = ''
    trainingReviewRequired.value = []
    trainingAnswerChecked.value = false
    trainingSanitizedChecked.value = false
    trainingTransfer.value = null
    if (value?.reviewStatus === 'APPROVED' && authStore.hasPermission('ANALYSIS_APPROVE')) checkTrainingTransfer()
    if (!value?.jobId || !job.value?.requirementVersionId) return
    try {
        const requirements = await getAllRequirements()
        if (analysis.value?.jobId !== value.jobId || followUpAnswers.value.some((answer) => answer?.trim()) || followUpQuestion.value.trim()) return
        const requirementId = value.facts?.sourceSummary?.changeRequirement?.requirementId
        const requirement = requirements.find((item) => item.id === requirementId || item.currentVersion?.id === job.value?.requirementVersionId)
        if (requirement?.currentVersion?.sourceAnalysisJobId !== value.jobId) return
        const draft = savedFollowUpDraft(value.jobId, requirement.currentVersion.clarifications)
        followUpAnswers.value = draft.answers
        followUpQuestion.value = draft.question
    } catch {
        // 저장된 답변을 불러오지 못해도 현재 분석 결과와 입력 화면은 유지한다.
    }
})

const submitReview = async (decision) => {
    reviewError.value = ''
    reviewNotice.value = ''
    let payload
    try {
        payload = buildAnalysisReviewPayload(decision, reviewNote.value, reviewUseCorrection.value, reviewCorrectedText.value)
    } catch (error) {
        reviewError.value = error.message
        return
    }
    const action = decision === 'REJECTED' ? '반려' : reviewUseCorrection.value ? '수정본 승인' : 'AI 원본 승인'
    const correctionWarning = decision === 'REJECTED' && reviewUseCorrection.value ? ' 입력한 수정 JSON은 저장되지 않습니다.' : ''
    if (!window.confirm(`이 분석을 ${action}할까요? 검토 결정은 다시 변경할 수 없습니다.${correctionWarning}`)) return

    reviewBusy.value = true
    try {
        analysis.value = await reviewAnalysisResult(job.value.jobId, payload)
        reviewNotice.value = `${action} 결과를 저장했습니다.`
    } catch (error) {
        reviewError.value = error.response?.data?.message || error.response?.data?.detail || error.message || '검토 결과를 저장하지 못했습니다.'
    } finally {
        reviewBusy.value = false
    }
}

const downloadTrainingDraft = async () => {
    if (
        !window.confirm(
            '이 파일에는 비식별화 전 소스·DB 근거가 들어갈 수 있습니다. 로컬에서 검토하고 비밀정보를 제거한 뒤에만 학습이나 공유 NAS 전송에 사용하세요. 초안을 내려받을까요?'
        )
    )
        return
    trainingDraftBusy.value = true
    trainingDraftError.value = ''
    try {
        const draft = await getTrainingCaseDraft(job.value.jobId)
        const content = new Blob([`${JSON.stringify(draft, null, 2)}\n`], { type: 'application/json;charset=utf-8' })
        const url = URL.createObjectURL(content)
        const link = document.createElement('a')
        link.href = url
        link.download = `training-case-draft-${job.value.jobId}.json`
        document.body.appendChild(link)
        link.click()
        link.remove()
        window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (error) {
        trainingDraftError.value = error.response?.data?.message || error.message || '학습 사례 초안을 만들지 못했습니다.'
    } finally {
        trainingDraftBusy.value = false
    }
}

const openTrainingEditor = async () => {
    trainingDraftBusy.value = true
    trainingDraftError.value = ''
    try {
        const draft = await getTrainingCaseDraft(job.value.jobId)
        trainingCaseText.value = JSON.stringify(draft.caseDraft, null, 2)
        trainingReviewRequired.value = draft.reviewRequired || []
        trainingAnswerChecked.value = false
        trainingSanitizedChecked.value = false
        trainingEditorOpen.value = true
    } catch (error) {
        trainingDraftError.value = error.response?.data?.message || error.message || '학습 사례 초안을 불러오지 못했습니다.'
    } finally {
        trainingDraftBusy.value = false
    }
}

const checkTrainingTransfer = async () => {
    const jobId = job.value?.jobId
    if (!jobId || analysis.value?.reviewStatus !== 'APPROVED' || trainingStatusBusy.value) return
    trainingStatusBusy.value = true
    try {
        const value = await getTrainingTransferStatus(jobId)
        if (job.value?.jobId === jobId) {
            trainingTransfer.value = value
            trainingDraftError.value = ''
        }
    } catch (error) {
        if (error.response?.status !== 404) trainingDraftError.value = '전송 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'
    } finally {
        trainingStatusBusy.value = false
    }
}

const syncTrainingReviewFlags = () => {
    try {
        const draft = applyTrainingReviewConfirmations(trainingCaseText.value, trainingAnswerChecked.value, trainingSanitizedChecked.value)
        trainingCaseText.value = JSON.stringify(draft, null, 2)
        trainingDraftError.value = ''
    } catch (error) {
        trainingDraftError.value = error.message
    }
}

const submitTrainingCase = async () => {
    trainingDraftError.value = ''
    if (!trainingAnswerChecked.value || !trainingSanitizedChecked.value) {
        trainingDraftError.value = '정답·근거 검토와 비식별화 확인을 모두 선택해 주세요.'
        return
    }
    let caseDraft
    try {
        caseDraft = applyTrainingReviewConfirmations(trainingCaseText.value, trainingAnswerChecked.value, trainingSanitizedChecked.value)
        trainingCaseText.value = JSON.stringify(caseDraft, null, 2)
    } catch (error) {
        trainingDraftError.value = error.message
        return
    }
    if (!window.confirm('검토·비식별화한 학습 사례를 공유 폴더에 게시하고 AI 학습 서버에 알릴까요?')) return
    trainingDraftBusy.value = true
    try {
        trainingTransfer.value = await publishTrainingCase(job.value.jobId, caseDraft)
        trainingEditorOpen.value = false
    } catch (error) {
        trainingDraftError.value = error.response?.data?.message || error.response?.data?.detail?.message || error.message || '학습 사례를 전송하지 못했습니다.'
    } finally {
        trainingDraftBusy.value = false
    }
}

const trainingTransferLabel = computed(
    () =>
        ({
            WAITING_FOR_RECEIVER: '공유 폴더 게시 완료 · AI 학습 서버 알림 재시도 중',
            WAITING_FOR_SYNC: 'AI 학습 서버 알림 완료 · 파일 동기화 대기 중',
            VERIFIED: 'AI 학습 서버 수신·검증 완료',
            REJECTED: 'AI 학습 서버 검증 실패 · 사례와 공유 파일 확인 필요',
        })[trainingTransfer.value?.status] || '전송 상태 확인 중'
)

onMounted(() => {
    window.addEventListener('beforeunload', warnBeforeUnload)
    elapsedTimer = setInterval(() => {
        if (job.value?.status === 'PENDING' || job.value?.status === 'RUNNING') clock.value = Date.now()
    }, 1000)
    trainingTimer = setInterval(() => {
        if (trainingTransfer.value && !['VERIFIED', 'REJECTED'].includes(trainingTransfer.value.status)) checkTrainingTransfer()
    }, 5000)
})
onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', warnBeforeUnload)
    poller.stop()
    if (elapsedTimer !== null) clearInterval(elapsedTimer)
    if (trainingTimer !== null) clearInterval(trainingTimer)
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
const activityEvents = computed(() => analysisActivityEvents(job.value))
const recentActivityEvents = computed(() => activityEvents.value.slice(0, 10))
const olderActivityEvents = computed(() => activityEvents.value.slice(10))
const currentActivity = computed(() => activeActivityEvent(job.value))
const elapsedText = computed(() => formatAnalysisElapsed(analysisElapsedSeconds(job.value, clock.value)))
const currentActivityElapsed = computed(() => {
    const started = Date.parse(currentActivity.value?.at || '')
    return Number.isFinite(started) ? formatAnalysisElapsed(Math.max(0, (clock.value - started) / 1000)) : null
})
const formatDate = (value) => {
    if (!value) return '—'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('ko-KR')
}
const formatActivityTime = (value) => {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '시간 미상' : date.toLocaleTimeString('ko-KR')
}
const activityStateLabel = (state) => ({ STARTED: '진행 중', WAITING: '응답 대기', COMPLETED: '완료', FAILED: '실패', CANCELLED: '취소' })[state] || ''
const count = (value) => (Array.isArray(value) ? `${value.length.toLocaleString('ko-KR')}개` : '정보 없음')
// result는 AI 판단, facts는 AI에 실제 전달된 소스·DB 근거다.
const source = computed(() => analysis.value?.facts?.sourceSummary || {})
const database = computed(() => analysis.value?.facts?.databaseSummary || {})
const aiResult = computed(() =>
    analysis.value?.reviewStatus === 'APPROVED' && analysis.value?.correctedResult ? analysis.value.correctedResult : analysis.value?.result || {}
)
const reviewStatusLabel = computed(() => ({ APPROVED: '사람 승인', REJECTED: '반려', PENDING: '검토 대기' })[analysis.value?.reviewStatus] || '검토 대기')
const plainLanguageSummary = computed(() => {
    const value = aiResult.value.plainLanguageSummary
    return typeof value === 'string' && value.trim() ? value.trim() : ''
})
const databaseImpact = computed(() => aiResult.value.databaseImpact || null)
const followUpQuestions = computed(() => asList(databaseImpact.value?.openQuestions))
const previousClarifications = computed(() => asList(source.value.changeRequirement?.clarifications))

const beginFollowUpAnalysis = async (versionId) => {
    const created = await startProjectAnalysis(route.params.projectId, versionId)
    await router.push({
        name: 'analysis-progress',
        params: { projectId: route.params.projectId, analysisJobId: created.jobId },
    })
}

const submitFollowUp = async () => {
    followUpError.value = ''
    let payload
    try {
        payload = buildFollowUpPayload(job.value?.jobId, job.value?.requirementVersionId, followUpAnswers.value, followUpQuestion.value)
    } catch (error) {
        followUpError.value = error.message
        return
    }
    followUpBusy.value = true
    try {
        if (followUpSavedVersionId.value) {
            await beginFollowUpAnalysis(followUpSavedVersionId.value)
            return
        }
        const requirements = await getAllRequirements()
        const requirementId = source.value.changeRequirement?.requirementId
        const requirement = requirements.find((item) => item.id === requirementId || item.currentVersion?.id === job.value?.requirementVersionId)
        if (!requirement || !canContinueFromAnalysis(job.value?.jobId, job.value?.requirementVersionId, requirement.currentVersion)) {
            throw new Error('요구사항이 다른 경로로 변경됐거나 접근할 수 없습니다. 변경요청 관리에서 최신 버전을 확인해 주세요.')
        }
        const version = await createRequirementFollowUp(requirement.id, payload)
        followUpSavedVersionId.value = version.id
        await beginFollowUpAnalysis(version.id)
    } catch (error) {
        const detail = error.response?.data?.message || error.response?.data?.detail || error.message
        followUpError.value = followUpSavedVersionId.value
            ? `답변은 새 버전으로 저장됐지만 분석을 시작하지 못했습니다. 다시 시도해 주세요. ${detail}`
            : `추가 질문을 저장하지 못했습니다. ${detail}`
    } finally {
        followUpBusy.value = false
    }
}
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
const displayedRisk = computed(() => riskPresentation(riskLevel.value, riskScore.value, aiResult.value.riskAssessment))
const riskFactors = computed(() => {
    const assessment = aiResult.value.riskAssessment
    if (!assessment || displayedRisk.value.warning || displayedRisk.value.score === null) return []
    return [
        ['변경 범위', 'changeScope', 25],
        ['데이터 영향', 'dataImpact', 20],
        ['보안 영향', 'securityImpact', 25],
        ['구현·배포 난도', 'deliveryComplexity', 20],
        ['남은 불확실성', 'uncertainty', 10],
    ].map(([label, key, maximum]) => ({ label, score: assessment[key].score, maximum, reason: assessment[key].reason }))
})
const databaseColumnCount = computed(() => {
    if (!Array.isArray(database.value.tables) || database.value.tables.some((table) => !Array.isArray(table.columns))) return '정보 없음'
    return `${database.value.tables.reduce((total, table) => total + table.columns.length, 0).toLocaleString('ko-KR')}개`
})
const stages = computed(() => [
    {
        name: 'Git·소스 근거 분석',
        icon: 'SRC',
        color: 'blue-bg',
        active: activeStage.value === 'SRC',
        progress: job.value?.sourceProgress,
        detail:
            (job.value?.sourceProgress ?? 0) >= 100
                ? 'Git 수집과 소스·DB 참조 조사가 끝났습니다.'
                : (job.value?.sourceProgress ?? 0) >= 40
                  ? 'Git 수집 완료 · 소스·DB 참조 조사 대기 또는 진행 중'
                  : 'Git 소스를 수집하는 중',
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
        detail:
            job.value?.status === 'COMPLETED'
                ? 'AI 분석과 결과 저장이 끝났습니다.'
                : (job.value?.relationProgress ?? 0) >= 90
                  ? 'AI 응답 완료 · 결과 저장 중'
                  : (job.value?.relationProgress ?? 0) >= 40
                    ? 'AI 응답 대기 중 · 모델 내부 진행률은 제공되지 않습니다.'
                    : 'AI 분석 자료를 준비하는 중',
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
                <span class="activity-hint">표시된 값은 서버 단계별 진행도이며 AI 모델 내부 진행률은 아닙니다.</span>
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
            <RouterLink
                v-if="previousQuestionJobId && ['FAILED', 'CANCELLED'].includes(job.status)"
                class="button primary mt-4"
                :to="{ name: 'analysis-progress', params: { projectId: route.params.projectId, analysisJobId: previousQuestionJobId } }"
            >
                이전 질문으로 돌아가 이어서 분석
            </RouterLink>
        </section>

        <section v-if="job" class="panel activity-log-panel" aria-labelledby="activity-log-title">
            <div class="panel-heading">
                <div>
                    <h2 id="activity-log-title">실시간 작업 내역</h2>
                    <p>서버에서 확인된 단계만 표시합니다. 내부 오류나 접속 정보는 표시하지 않습니다.</p>
                </div>
                <span v-if="job.status === 'RUNNING'" class="badge blue">자동 갱신 중</span>
            </div>
            <p v-if="currentActivity" class="activity-current">
                <span class="activity-dot" aria-hidden="true"></span>
                {{ currentActivity.label }} · 이 단계 경과 {{ currentActivityElapsed || '계산 중' }}
            </p>
            <ol
                v-if="recentActivityEvents.length"
                class="activity-log-list"
                role="log"
                aria-label="최근 분석 작업 내역"
                aria-live="polite"
                aria-relevant="additions"
            >
                <li v-for="event in recentActivityEvents" :key="event.sequence" class="activity-log-item">
                    <time :datetime="event.at">{{ formatActivityTime(event.at) }}</time>
                    <span class="activity-state" :class="`state-${event.state.toLowerCase()}`">{{ activityStateLabel(event.state) }}</span>
                    <span class="activity-label">{{ event.label }}</span>
                </li>
            </ol>
            <details v-if="olderActivityEvents.length" class="activity-history">
                <summary class="activity-history-toggle">이전 작업 내역 {{ olderActivityEvents.length }}건 보기</summary>
                <ol class="activity-log-list" aria-label="이전 분석 작업 내역">
                    <li v-for="event in olderActivityEvents" :key="event.sequence" class="activity-log-item">
                        <time :datetime="event.at">{{ formatActivityTime(event.at) }}</time>
                        <span class="activity-state" :class="`state-${event.state.toLowerCase()}`">{{ activityStateLabel(event.state) }}</span>
                        <span class="activity-label">{{ event.label }}</span>
                    </li>
                </ol>
            </details>
            <p v-if="!activityEvents.length" class="empty-copy">
                {{
                    job.status === 'PENDING'
                        ? '분석 작업 시작을 기다리고 있습니다.'
                        : '이 분석에는 저장된 작업 내역이 없습니다. 위의 현재 단계와 경과 시간을 확인하세요.'
                }}
            </p>
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
                <p v-if="stage.detail" class="stage-activity">{{ stage.detail }}</p>
                <p v-else-if="stage.active" class="stage-activity">현재 진행 중</p>
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
            <section v-if="!isAnalysisDemoMode" class="panel analysis-section" aria-labelledby="analysis-review-title">
                <div class="panel-heading">
                    <div>
                        <h2 id="analysis-review-title">사람 검토</h2>
                        <p>AI 초안과 실제 소스·DB 근거를 확인한 뒤 승인하거나 반려합니다.</p>
                    </div>
                    <span class="badge" :class="analysis.reviewStatus === 'APPROVED' ? 'green' : analysis.reviewStatus === 'REJECTED' ? 'orange' : 'blue'">
                        {{ reviewStatusLabel }}
                    </span>
                </div>
                <template v-if="analysis.reviewStatus && analysis.reviewStatus !== 'PENDING'">
                    <p class="text-sm text-slate-700 dark:text-slate-300">검토 시각: {{ formatDate(analysis.reviewedAt) }}</p>
                    <p class="mt-2 whitespace-pre-wrap break-words text-sm text-slate-700 dark:text-slate-300">검토 이유: {{ analysis.reviewNote }}</p>
                    <p v-if="analysis.correctedResult" class="mt-2 text-sm font-semibold text-blue-700 dark:text-blue-300">
                        아래에는 사람이 승인한 수정본을 표시합니다. AI 원본은 보존됩니다.
                    </p>
                    <p v-else-if="analysis.reviewStatus === 'REJECTED'" class="mt-2 text-sm text-amber-800 dark:text-amber-300">
                        아래 내용은 반려된 AI 초안이며 승인된 결론이 아닙니다.
                    </p>
                    <div
                        v-if="analysis.reviewStatus === 'APPROVED' && authStore.hasPermission('ANALYSIS_APPROVE')"
                        class="mt-4 flex flex-col items-start gap-2"
                    >
                        <button type="button" class="button secondary w-full sm:w-auto" :disabled="trainingDraftBusy" @click="downloadTrainingDraft">
                            {{ trainingDraftBusy ? '초안 준비 중…' : '학습 사례 초안 내려받기' }}
                        </button>
                        <button
                            v-if="!trainingTransfer"
                            type="button"
                            class="button secondary w-full sm:w-auto"
                            :disabled="trainingDraftBusy"
                            @click="openTrainingEditor"
                        >
                            {{ trainingDraftBusy ? '초안 준비 중…' : '검토 후 AI 학습 서버로 전송' }}
                        </button>
                        <p v-if="trainingTransfer" class="text-sm text-blue-700 dark:text-blue-300" role="status">{{ trainingTransferLabel }}</p>
                        <p v-if="trainingTransfer?.status === 'REJECTED'" class="text-sm text-rose-700 dark:text-rose-300">
                            같은 분석 작업의 전송 사례는 덮어쓰지 않습니다. 오류 원인을 확인하고 새 분석 사례로 다시 검토해 주세요.
                        </p>
                        <button type="button" class="button secondary w-full sm:w-auto" :disabled="trainingStatusBusy" @click="checkTrainingTransfer">
                            {{ trainingStatusBusy ? '상태 확인 중…' : '전송 상태 확인' }}
                        </button>
                        <div v-if="trainingEditorOpen" class="grid w-full min-w-0 gap-3 rounded-xl border border-slate-200 p-4 dark:border-slate-700">
                            <h3 class="font-semibold">학습 사례 검토</h3>
                            <p class="text-sm text-amber-800 dark:text-amber-300">
                                아래 항목을 확인하고 JSON의 빈 값과 정답을 수정하세요. 공유 폴더 게시 후에는 내용을 덮어쓸 수 없습니다.
                            </p>
                            <p class="text-sm text-slate-600 dark:text-slate-300">
                                프로젝트 그룹과 현재 로그인한 검토자의 식별자는 자동으로 입력됩니다. 아래 검토 확인란의 선택도 JSON에 자동으로 반영됩니다.
                            </p>
                            <ul class="list-disc space-y-1 pl-5 text-sm text-slate-700 dark:text-slate-300">
                                <li v-for="item in trainingReviewRequired" :key="item">{{ item }}</li>
                            </ul>
                            <BaseTextarea
                                id="training-case-json"
                                v-model="trainingCaseText"
                                label="학습 사례 JSON"
                                mode="code"
                                rows="12"
                                :disabled="trainingDraftBusy"
                            />
                            <label class="flex items-start gap-2 text-sm"
                                ><input
                                    v-model="trainingAnswerChecked"
                                    type="checkbox"
                                    :disabled="trainingDraftBusy"
                                    @change="syncTrainingReviewFlags"
                                />정답·소스·DB 근거의 일치를 사람이 확인했습니다.</label
                            >
                            <label class="flex items-start gap-2 text-sm"
                                ><input
                                    v-model="trainingSanitizedChecked"
                                    type="checkbox"
                                    :disabled="trainingDraftBusy"
                                    @change="syncTrainingReviewFlags"
                                />비밀키·개인정보·고객 원문을 제거했습니다.</label
                            >
                            <div class="flex flex-wrap gap-2">
                                <button type="button" class="button primary" :disabled="trainingDraftBusy" @click="submitTrainingCase">검토한 사례 전송</button>
                                <button type="button" class="button secondary" :disabled="trainingDraftBusy" @click="trainingEditorOpen = false">닫기</button>
                            </div>
                        </div>
                        <p class="text-xs text-amber-800 dark:text-amber-300">
                            내려받는 파일은 비식별화 전 초안입니다. 검토 항목을 채우고 검사하기 전에는 학습·공유 NAS 전송에 사용하지 마세요.
                        </p>
                        <p v-if="trainingDraftError" role="alert" class="break-words text-sm text-rose-700 dark:text-rose-300">{{ trainingDraftError }}</p>
                    </div>
                </template>
                <template v-else-if="authStore.hasPermission('ANALYSIS_APPROVE')">
                    <p class="mb-3 text-sm text-amber-800 dark:text-amber-300">
                        검토 결정은 한 번만 저장할 수 있습니다. 프로젝트 소유자 또는 관리자 권한이 필요하며, 승인 전 파일·DB 근거와 과장된 제안이 없는지
                        확인하세요.
                    </p>
                    <BaseTextarea
                        id="review-note"
                        v-model="reviewNote"
                        label="검토 이유"
                        rows="3"
                        maxlength="4000"
                        placeholder="확인한 근거와 승인 또는 반려 이유를 적어 주세요."
                        :disabled="reviewBusy"
                    />
                    <label class="mt-4 flex items-start gap-2 text-sm text-slate-700 dark:text-slate-300">
                        <input v-model="reviewUseCorrection" type="checkbox" class="mt-1" :disabled="reviewBusy" />
                        AI 결과를 수정한 전체 JSON을 승인합니다.
                    </label>
                    <div v-if="reviewUseCorrection" class="mt-3">
                        <BaseTextarea
                            id="review-corrected-result"
                            v-model="reviewCorrectedText"
                            label="수정 결과 JSON"
                            mode="code"
                            rows="15"
                            maxlength="200000"
                            spellcheck="false"
                            :disabled="reviewBusy"
                        />
                        <p class="mt-1 text-xs text-slate-600 dark:text-slate-400">
                            필수 필드와 DB 판단 형식은 유지해야 합니다. 검토하지 않은 근거나 비밀정보를 추가하지 마세요.
                        </p>
                    </div>
                    <p v-if="reviewError" role="alert" class="mt-3 break-words text-sm text-rose-700 dark:text-rose-300">{{ reviewError }}</p>
                    <div class="mt-4 flex flex-col gap-2 sm:flex-row sm:flex-wrap">
                        <button
                            type="button"
                            class="button primary w-full sm:w-auto"
                            :disabled="reviewBusy || (Boolean(displayedRisk.warning) && !reviewUseCorrection)"
                            @click="submitReview('APPROVED')"
                        >
                            {{ reviewBusy ? '저장 중…' : reviewUseCorrection ? '수정본 승인' : 'AI 원본 승인' }}
                        </button>
                        <button type="button" class="button secondary w-full sm:w-auto" :disabled="reviewBusy" @click="submitReview('REJECTED')">반려</button>
                    </div>
                </template>
                <p v-else class="text-sm text-slate-600 dark:text-slate-300">이 계정에는 분석 결과를 승인하거나 반려할 권한이 없습니다.</p>
                <p v-if="reviewNotice" role="status" class="mt-3 text-sm text-emerald-700 dark:text-emerald-300">{{ reviewNotice }}</p>
            </section>

            <section class="panel analysis-section plain-language-section">
                <div class="panel-heading">
                    <div>
                        <h2>쉽게 보는 한 줄 요약</h2>
                        <p>고객·PM을 위한 설명</p>
                    </div>
                    <span class="badge blue">{{
                        analysis.reviewStatus === 'APPROVED' ? '사람 승인 결과' : analysis.reviewStatus === 'REJECTED' ? '반려된 AI 초안' : 'AI 초안 · 검토 전'
                    }}</span>
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
                    <span class="badge" :class="displayedRisk.warning || ['HIGH', 'CRITICAL'].includes(riskLevel) ? 'orange' : 'blue'">
                        위험도 {{ displayedRisk.label }}{{ displayedRisk.score !== null ? ` · ${displayedRisk.score}/100` : ''
                        }}{{ displayedRisk.provisional && displayedRisk.score !== null ? ' · 잠정' : '' }}
                    </span>
                </div>
                <p v-if="displayedRisk.warning" role="alert" class="mb-3 text-sm text-amber-800 dark:text-amber-300">{{ displayedRisk.warning }}</p>
                <p v-else-if="displayedRisk.provisional && displayedRisk.score !== null" class="mb-3 text-sm text-amber-800 dark:text-amber-300">
                    확인되지 않은 조건이 남아 있어 잠정 점수입니다. 변경 자체의 위험과 남은 불확실성을 구분해 보세요.
                </p>
                <dl v-if="riskFactors.length" class="mb-4 grid gap-2 sm:grid-cols-2">
                    <div
                        v-for="factor in riskFactors"
                        :key="factor.label"
                        class="risk-factor-card rounded-lg border border-slate-200 p-3 dark:border-slate-700"
                    >
                        <dt class="text-xs font-bold text-slate-700 dark:text-slate-200">{{ factor.label }} · {{ factor.score }}/{{ factor.maximum }}</dt>
                        <dd class="mt-1 text-xs leading-relaxed text-slate-600 dark:text-slate-300">{{ factor.reason }}</dd>
                    </div>
                </dl>
                <p v-if="riskFactors.length" class="mb-4 text-xs leading-relaxed text-slate-600 dark:text-slate-400">
                    변경 자체의 영향은 최대 90점, 남은 불확실성은 최대 10점입니다. 0~24점은 낮음, 25~49점은 보통, 50~74점은 높음, 75~100점은 매우 높음입니다.
                    AI의 근거 기반 추정치이므로 최종 판단은 사람 검토가 필요합니다.
                </p>
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

            <section
                v-if="isRequirementAnalysis && job?.status === 'COMPLETED' && !isAnalysisDemoMode && authStore.hasPermission('PROJECT_UPDATE')"
                class="panel analysis-section"
                aria-labelledby="follow-up-title"
            >
                <div class="panel-heading">
                    <div>
                        <h2 id="follow-up-title">답변·추가 질문으로 다시 분석</h2>
                        <p>기존 결과는 보존합니다. 후속 분석이 취소되거나 실패해도 이 질문에서 답변을 수정하고 다시 분석할 수 있습니다.</p>
                    </div>
                </div>
                <details v-if="previousClarifications.length" class="clarification-history">
                    <summary class="clarification-history-toggle">
                        <span class="clarification-history-heading">
                            <span class="clarification-history-title">이전에 남긴 확인 내용</span>
                            <span class="clarification-history-count">{{ previousClarifications.length }}건</span>
                            <span class="clarification-history-hint">이전 질문과 답변을 확인할 수 있습니다.</span>
                        </span>
                        <span class="clarification-history-action" aria-hidden="true">
                            <span class="clarification-history-expand">내용 펼치기</span>
                            <span class="clarification-history-collapse">내용 접기</span>
                        </span>
                    </summary>
                    <ol class="clarification-history-list">
                        <li v-for="(item, index) in previousClarifications" :key="item.id || index" class="clarification-history-item">
                            <span class="clarification-history-index">이전 확인 {{ index + 1 }}</span>
                            <p class="clarification-history-question">{{ item.question }}</p>
                            <div class="clarification-history-answer">
                                <span>남긴 답변</span>
                                <p>{{ item.answer || '아직 답변하지 않았습니다.' }}</p>
                            </div>
                        </li>
                    </ol>
                </details>
                <p v-if="source.changeRequirement?.clarificationCoverage?.omitted > 0" class="mb-4 text-xs text-amber-800 dark:text-amber-300">
                    이전 확인 내용 {{ source.changeRequirement.clarificationCoverage.omitted }}건은 이력에 보존돼 있지만 이번 AI 입력에서는 제외됐습니다. 오래된
                    답변이 중요하다면 새 질문에 다시 적어 주세요.
                </p>
                <div v-if="followUpQuestions.length" class="follow-up-current-heading">
                    <h3>이번에 답할 확인 질문</h3>
                    <p>아래 질문은 이번 분석에서 새로 나온 내용입니다.</p>
                </div>
                <div v-if="followUpQuestions.length" class="space-y-4">
                    <div v-for="(question, index) in followUpQuestions" :key="`${job.jobId}:${index}`" class="min-w-0">
                        <BaseTextarea
                            :id="`follow-up-answer-${index}`"
                            v-model="followUpAnswers[index]"
                            :label="`확인 질문 ${index + 1} · ${question}`"
                            rows="2"
                            maxlength="4000"
                            placeholder="답을 알고 있는 질문만 입력하세요. 답하지 않은 질문은 그대로 남습니다."
                            :disabled="followUpBusy || Boolean(followUpSavedVersionId)"
                        />
                    </div>
                </div>
                <div class="mt-4">
                    <BaseTextarea
                        id="follow-up-question"
                        v-model="followUpQuestion"
                        label="새로 물어볼 질문 또는 추가 설명"
                        rows="3"
                        maxlength="2000"
                        placeholder="요구사항에 빠진 조건이나 새로 확인할 점을 적어 주세요."
                        :disabled="followUpBusy || Boolean(followUpSavedVersionId)"
                    />
                </div>
                <p class="mt-2 text-xs text-slate-600 dark:text-slate-400">
                    소스·DB는 최신 상태를 다시 확인합니다. 답변이 실제 DB 구조를 증명하는 것은 아닙니다.
                </p>
                <p v-if="followUpError" role="alert" class="mt-3 break-words text-sm text-rose-700 dark:text-rose-300">{{ followUpError }}</p>
                <div class="mt-4 flex flex-col gap-2 sm:flex-row">
                    <button type="button" class="button primary w-full sm:w-auto" :disabled="followUpBusy" @click="submitFollowUp">
                        {{ followUpBusy ? '처리 중…' : followUpSavedVersionId ? '새 버전 분석 다시 시작' : '답변 저장하고 재분석' }}
                    </button>
                </div>
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
.clarification-history {
    margin-bottom: 20px;
    border: 1px solid #cbd9f2;
    border-radius: 14px;
    background: #f6f9ff;
}
.clarification-history-toggle {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    padding: 15px 18px;
    cursor: pointer;
    list-style: none;
}
.clarification-history-toggle::-webkit-details-marker {
    display: none;
}
.clarification-history-toggle:focus-visible {
    border-radius: 14px;
    outline: 2px solid #4f6ee8;
    outline-offset: 2px;
}
.clarification-history-heading {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px 9px;
    min-width: 0;
}
.clarification-history-title {
    color: #203765;
    font-size: 14px;
    font-weight: 700;
}
.clarification-history-count {
    border-radius: 999px;
    background: #dfeaff;
    padding: 2px 8px;
    color: #3155a2;
    font-size: 12px;
    font-weight: 700;
}
.clarification-history-hint {
    color: #526583;
    font-size: 12px;
}
.clarification-history-action {
    flex: 0 0 auto;
    color: #3155a2;
    font-size: 12px;
    font-weight: 700;
    white-space: nowrap;
}
.clarification-history-action::after {
    display: inline-block;
    margin-left: 7px;
    content: '⌄';
    font-size: 16px;
    line-height: 0;
    transition: transform 0.2s ease;
}
.clarification-history-collapse,
.clarification-history[open] .clarification-history-expand {
    display: none;
}
.clarification-history[open] .clarification-history-collapse {
    display: inline;
}
.clarification-history[open] .clarification-history-action::after {
    transform: rotate(180deg);
}
.clarification-history-list {
    display: grid;
    gap: 10px;
    margin: 0;
    border-top: 1px solid #dbe5f5;
    padding: 14px 18px 18px;
    list-style: none;
}
.clarification-history-item {
    min-width: 0;
    border: 1px solid #dbe5f5;
    border-radius: 10px;
    background: #fff;
    padding: 13px 15px;
    overflow-wrap: anywhere;
}
.clarification-history-index {
    color: #4b66a6;
    font-size: 11px;
    font-weight: 700;
}
.clarification-history-question,
.clarification-history-answer p {
    margin: 5px 0 0;
    color: #26344e;
    font-size: 13px;
    line-height: 1.65;
    white-space: pre-wrap;
}
.clarification-history-question {
    font-weight: 700;
}
.clarification-history-answer {
    margin-top: 11px;
    border-left: 3px solid #8098ed;
    padding-left: 11px;
}
.clarification-history-answer > span {
    color: #526583;
    font-size: 11px;
    font-weight: 700;
}
.follow-up-current-heading {
    margin: 0 0 14px;
    border-top: 1px solid #e2e8f2;
    padding-top: 18px;
}
.follow-up-current-heading h3 {
    margin: 0;
    color: #203765;
    font-size: 14px;
}
.follow-up-current-heading p {
    margin: 4px 0 0;
    color: #667188;
    font-size: 12px;
}
:global(html.dark .clarification-history) {
    border-color: #385078;
    background: #17243b;
}
:global(html.dark .clarification-history-title),
:global(html.dark .follow-up-current-heading h3) {
    color: #e2ebff;
}
:global(html.dark .clarification-history-count) {
    background: #2e4166;
    color: #c6d8ff;
}
:global(html.dark .clarification-history-hint),
:global(html.dark .clarification-history-answer > span),
:global(html.dark .follow-up-current-heading p) {
    color: #aabbd4;
}
:global(html.dark .clarification-history-action),
:global(html.dark .clarification-history-index) {
    color: #b8cbff;
}
:global(html.dark .clarification-history-list),
:global(html.dark .follow-up-current-heading) {
    border-color: #385078;
}
:global(html.dark .clarification-history-item) {
    border-color: #385078;
    background: #1e2d47;
}
:global(html.dark .clarification-history-question),
:global(html.dark .clarification-history-answer p) {
    color: #e2e8f0;
}
.activity-log-panel {
    margin: 20px 0;
}
.activity-current {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 0 0 14px;
    color: #274895;
    font-size: 14px;
    font-weight: 700;
}
.activity-log-list {
    display: grid;
    gap: 8px;
    margin: 0;
    padding: 0;
    list-style: none;
}
.activity-history {
    margin-top: 12px;
}
.activity-history-toggle {
    width: fit-content;
    cursor: pointer;
    color: #3155a2;
    font-size: 13px;
    font-weight: 700;
}
.activity-history[open] .activity-history-toggle {
    margin-bottom: 10px;
}
:global(html.dark .activity-history-toggle) {
    color: #bfdbfe;
}
.activity-log-item {
    display: grid;
    grid-template-columns: 100px 58px minmax(0, 1fr);
    align-items: start;
    gap: 10px;
    border: 1px solid #e2e8f2;
    border-radius: 10px;
    padding: 9px 12px;
    font-size: 13px;
    overflow-wrap: anywhere;
}
.activity-log-item time {
    color: #667188;
    font-variant-numeric: tabular-nums;
}
.activity-state {
    color: #4665b2;
    font-weight: 700;
}
.state-completed {
    color: #23805a;
}
.state-failed,
.state-cancelled {
    color: #b04a4a;
}
:global(html.dark .activity-current) {
    color: #bfdbfe;
}
:global(html.dark .activity-log-item) {
    border-color: #334155;
}
:global(html.dark .activity-log-item time) {
    color: #a5b4c9;
}
:global(html.dark .activity-state) {
    color: #a7bfff;
}
:global(html.dark .state-completed) {
    color: #86efac;
}
:global(html.dark .state-failed),
:global(html.dark .state-cancelled) {
    color: #fda4af;
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
.risk-factor-card {
    min-width: 0;
}
.risk-factor-card dt,
.risk-factor-card dd {
    overflow-wrap: anywhere;
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
    .activity-log-item {
        grid-template-columns: 90px minmax(0, 1fr);
    }
    .activity-label {
        grid-column: 1 / -1;
    }
}
</style>
