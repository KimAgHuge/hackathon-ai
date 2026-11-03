const objectValue = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)

/** 현재 버전만 제공하는 이전 서버에서도 이 작업과 같은 버전만 표시한다. */
export const requirementContextFromList = (requirements, job) => {
    if (!job?.requirementVersionId || !Array.isArray(requirements)) return null
    const requirement = requirements.find((item) => item.projectId === job.projectId && item.currentVersion?.id === job.requirementVersionId)
    if (!requirement) return null
    const version = requirement.currentVersion
    const questions = Array.isArray(version.clarifications)
        ? version.clarifications.filter(
              (item) =>
                  item?.kind === 'USER_QUESTION' &&
                  version.sourceAnalysisJobId &&
                  item.sourceAnalysisJobId === version.sourceAnalysisJobId &&
                  typeof item.question === 'string' &&
                  item.question.trim()
          )
        : []
    return {
        requirementId: requirement.id,
        versionId: version.id,
        versionNumber: version.versionNumber,
        title: version.title || requirement.title,
        originalContent: version.originalContent,
        refinedContent: version.refinedContent,
        acceptanceCriteria: version.acceptanceCriteria,
        sourceAnalysisJobId: version.sourceAnalysisJobId,
        latestFollowUpQuestion: questions.at(-1)?.question || null,
    }
}

/** 작업에 고정된 버전과 그 작업의 저장 결과를 우선하며 다른 버전의 내용을 섞지 않는다. */
export const requirementContextForJob = (job, analysis, fallback) => {
    if (!job?.requirementVersionId) return null
    let context = objectValue(fallback) && fallback.versionId === job.requirementVersionId ? fallback : null
    const facts = analysis?.jobId === job.jobId ? analysis.facts?.sourceSummary?.changeRequirement : null
    if (objectValue(facts) && (!facts.versionId || facts.versionId === job.requirementVersionId)) {
        context = { ...context, ...facts, versionId: job.requirementVersionId }
    }
    if (objectValue(job.requirementContext) && job.requirementContext.versionId === job.requirementVersionId) {
        context = job.requirementContext
    }
    return context
}
