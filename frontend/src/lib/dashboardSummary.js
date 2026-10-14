const sourceNames = {
    projects: '프로젝트',
    requirements: '변경요청',
    analyses: '분석 이력',
}

/** 조회 가능한 실제 데이터를 대시보드 표시 형태로 변환한다. 실패한 조회는 0건으로 표시하지 않는다. */
export const buildDashboardSummary = ({ projects, requirements, history, failures = [] }) => {
    const available = {
        projects: Array.isArray(projects),
        requirements: Array.isArray(requirements),
        analyses: Array.isArray(history?.items) && Number.isFinite(history?.total),
    }

    const metric = (label, value, change, tone) => ({ label, value, change, tone })

    return {
        mode: 'live',
        warnings: failures.map((source) => `${sourceNames[source]} 정보를 불러오지 못했습니다.`),
        metrics: [
            metric('등록 프로젝트', available.projects ? projects.length : '—', '접근 가능한 프로젝트', 'blue'),
            metric('등록 변경요청', available.requirements ? requirements.length : '—', '전체 요청', 'amber'),
            metric('저장된 분석 작업', available.analyses ? history.total : '—', '진행 중·완료 포함', 'emerald'),
            metric('높은 우선순위 요청', available.requirements
                ? requirements.filter((item) => ['HIGH', 'URGENT'].includes(item.priority)).length
                : '—', '긴급·높음 기준', 'violet'),
        ],
        recentAnalyses: available.analyses ? history.items.map((item) => ({
            id: item.jobId?.slice(0, 8) || '—',
            jobId: item.jobId,
            projectId: item.projectId,
            project: item.projectName || '이름 없는 프로젝트',
            request: item.requirementTitle || '프로젝트 구조 분석',
            risk: item.riskLevel || null,
            score: null,
            status: item.status,
        })) : [],
        systems: Object.entries(sourceNames).map(([source, name]) => ({
            name: `${name} 조회`,
            description: available[source] ? '현재 계정에서 접근 가능한 데이터를 조회했습니다.' : '데이터 조회에 실패했습니다.',
            status: available[source] ? 'ONLINE' : 'ERROR',
        })),
        analysesAvailable: available.analyses,
    }
}
