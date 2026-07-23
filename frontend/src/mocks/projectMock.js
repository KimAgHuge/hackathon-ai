export const projectMock = {
    id: 'demo',
    name: 'doenaai',
    branch: 'main',
    databaseType: 'TIBERO',
    status: 'READY',
}

export const analysisProgressMock = {
    overallProgress: 84,
    sourceProgress: 100,
    databaseProgress: 100,
    relationProgress: 68,
    status: 'PROCESSING',
}

export const architectureMock = {
    sourceCommit: 'a3b925c',
    databaseSnapshot: 'db-snapshot-015',
    nodes: [
        { type: 'VUE_VIEW', name: 'ApplicationView.vue' },
        { type: 'REST_API', name: 'POST /api/applications' },
        { type: 'SERVICE', name: 'ApplicationService' },
        { type: 'MYBATIS', name: 'ApplicationMapper' },
        { type: 'TABLE', name: 'TB_BM_APLY_RCPT' },
    ],
}

export const impactAnalysisMock = {
    riskLevel: 'HIGH',
    riskScore: 78,
    expectedDelayMin: 5,
    expectedDelayMax: 8,
    impactAreas: ['Vue 화면', 'Spring API', 'Service', 'Mapper SQL', 'Tibero 테이블'],
    evidences: [
        {
            order: 1,
            name: 'ApplicationView.vue',
            description: '신청 접수 화면에 전자서명 입력 및 검증 결과 표시가 필요합니다.',
            path: 'frontend/src/views/ApplicationView.vue',
        },
        {
            order: 2,
            name: 'ApplicationService.createApplication',
            description: '전자서명 검증과 저장 절차가 기존 접수 트랜잭션에 추가됩니다.',
            path: 'backend/src/main/java/.../ApplicationService.java',
        },
        {
            order: 3,
            name: 'TB_BM_APLY_RCPT',
            description: '검증 결과 컬럼 또는 별도 전자서명 이력 테이블이 필요합니다.',
            path: 'database:DOENAAI.TB_BM_APLY_RCPT',
        },
    ],
    recommendations: [
        '전자서명 원문은 대용량·민감 데이터를 고려하여 별도 이력 테이블에 저장합니다.',
        '기존 신청번호를 FK로 연결하고 검증 상태 조회 인덱스를 검토합니다.',
        '외부 인증 모듈 실패 시 재처리 및 보상 트랜잭션 정책을 정의합니다.',
        '기존 접수 흐름과 신규 전자서명 흐름을 대상으로 통합 테스트를 수행합니다.',
    ],
}
