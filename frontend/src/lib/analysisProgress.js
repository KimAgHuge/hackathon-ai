export const activeAnalysisStage = (job) => {
    if (job?.status !== 'RUNNING') return null
    if ((job.sourceProgress ?? 0) < 40) return 'SRC'
    if ((job.databaseProgress ?? 0) < 100) return 'DB'
    return 'AI'
}

export const analysisActivity = (job) => {
    if (job?.status === 'PENDING') return '분석 작업 시작을 기다리는 중'
    if (job?.status !== 'RUNNING') return null
    if ((job.sourceProgress ?? 0) < 40) return 'Git 소스를 수집하고 안전성을 확인하는 중'
    if ((job.databaseProgress ?? 0) < 100) return '읽기 전용 DB 구조를 수집하는 중'
    if ((job.relationProgress ?? 0) < 40) return '소스와 DB의 참조 관계를 분석하는 중'
    if ((job.relationProgress ?? 0) < 90) return 'AI 모델의 분석 응답을 기다리는 중'
    return '분석 결과를 저장하는 중'
}

export const analysisElapsedSeconds = (job, now = Date.now()) => {
    const start = Date.parse(job?.startedAt || job?.createdAt || '')
    if (!Number.isFinite(start)) return null
    const completed = Date.parse(job?.completedAt || '')
    const end = Number.isFinite(completed) ? completed : now
    return Math.max(0, Math.floor((end - start) / 1000))
}

export const formatAnalysisElapsed = (seconds) => {
    if (!Number.isFinite(seconds)) return '계산 중'
    const safe = Math.max(0, Math.floor(seconds))
    const hours = Math.floor(safe / 3600)
    const minutes = Math.floor((safe % 3600) / 60)
    const remainingSeconds = safe % 60
    return hours ? `${hours}시간 ${minutes}분 ${remainingSeconds}초` : `${minutes}분 ${remainingSeconds}초`
}
