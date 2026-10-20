// 화면 구성을 확인하는 가상 자료이며 실제 프로젝트·분석 결과를 나타내지 않는다.
export const projectMock = {
    id: 'demo',
    name: 'project_example',
    branch: 'main',
    databaseType: 'POSTGRESQL',
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
    sourceCommit: 'example-commit',
    databaseSnapshot: 'db-snapshot-example',
    nodes: [
        { type: 'VUE_VIEW', name: 'ExampleView.vue' },
        { type: 'REST_API', name: 'POST /api/examples' },
        { type: 'SERVICE', name: 'ExampleService' },
        { type: 'REPOSITORY', name: 'ExampleRepository' },
        { type: 'TABLE', name: 'table_example' },
    ],
}

export const impactAnalysisMock = {
    riskLevel: 'LOW',
    riskScore: 20,
    expectedDelayMin: 1,
    expectedDelayMax: 2,
    impactAreas: ['예시 입력 화면', '예시 API', '예시 서비스', '예시 테이블'],
    evidences: [
        {
            order: 1,
            name: 'ExampleView.vue',
            description: '가상 사례: 항목의 설명을 입력하고 표시하는 영역을 추가합니다.',
            path: 'frontend/src/views/ExampleView.vue',
        },
        {
            order: 2,
            name: 'ExampleService.saveExample',
            description: '가상 사례: 선택 입력인 설명 값을 확인하고 저장하는 흐름입니다.',
            path: 'backend/src/main/java/example/ExampleService.java',
        },
        {
            order: 3,
            name: 'table_example',
            description: '가상 사례: description_example 컬럼을 선택 값으로 추가하며 기존 값은 비워 둡니다.',
            path: 'database:schema_example.table_example',
        },
    ],
    recommendations: [
        '가상 사례의 설명은 선택 입력으로 두고 최대 길이를 정합니다.',
        '기존 항목의 설명이 비어 있어도 조회와 수정이 가능하도록 확인합니다.',
        '설명 수정에는 기존 항목의 수정 권한을 적용합니다.',
        '입력·저장·재조회와 빈 값 처리를 함께 시험합니다.',
    ],
}
