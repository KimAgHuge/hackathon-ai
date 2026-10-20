// 모든 이름·문서·수치는 화면 시험을 위해 만든 가상 자료다. 실제 업무 자료를 넣지 않는다.
export const dashboardMock = {
    metrics: [
        { label: '등록 프로젝트', value: 12, change: '+2 이번 달', tone: 'blue' },
        { label: '검토 대기 변경요청', value: 7, change: '3건 긴급', tone: 'amber' },
        { label: '완료된 분석', value: 48, change: '+11 이번 주', tone: 'emerald' },
        { label: '활성 AI 모델', value: 'v1.4.2', change: 'LoRA Adapter', tone: 'violet' },
    ],
    systems: [
        { name: 'AI 분석/추론 서버', description: 'FastAPI · Ollama', status: 'ONLINE', latency: '42ms' },
        { name: 'AI 학습 서버', description: 'MLX-LM · LoRA · 평가', status: 'OFFLINE', latency: '-' },
        { name: 'Supabase', description: 'Auth · PostgreSQL · pgvector', status: 'ONLINE', latency: '68ms' },
    ],
    recentAnalyses: [
        {
            id: 'AN-2026-048',
            project: 'project_example_01',
            request: '항목 설명 입력 기능 추가',
            risk: 'HIGH',
            score: 78,
            status: 'COMPLETED',
            date: '2026-07-23 11:02',
        },
        {
            id: 'AN-2026-047',
            project: 'project_example_02',
            request: '목록 정렬 조건 변경',
            risk: 'MEDIUM',
            score: 54,
            status: 'COMPLETED',
            date: '2026-07-22 17:42',
        },
        {
            id: 'AN-2026-046',
            project: 'project_example_03',
            request: '상태 표시 문구 변경',
            risk: 'LOW',
            score: 20,
            status: 'REVIEW',
            date: '2026-07-22 14:18',
        },
    ],
}

export const projectsMock = [
    {
        id: 'PRJ-001',
        name: 'project_example_01',
        stack: 'Vue 3 · Spring Boot · PostgreSQL',
        source: 'GitHub',
        branch: 'develop',
        status: 'READY',
        updatedAt: '2026-07-23 10:32',
    },
    {
        id: 'PRJ-002',
        name: 'project_example_02',
        stack: 'Vue 3 · FastAPI · PostgreSQL',
        source: 'ZIP',
        branch: 'release',
        status: 'ANALYZING',
        updatedAt: '2026-07-22 18:10',
    },
    {
        id: 'PRJ-003',
        name: 'project_example_03',
        stack: 'Vue 3 · Spring Boot · PostgreSQL',
        source: 'GitHub',
        branch: 'main',
        status: 'READY',
        updatedAt: '2026-07-22 15:44',
    },
    {
        id: 'PRJ-004',
        name: 'project_example_04',
        stack: 'Vue 3 · Spring Boot · PostgreSQL',
        source: 'GitHub',
        branch: 'main',
        status: 'ERROR',
        updatedAt: '2026-07-21 09:20',
    },
]

export const changeRequestsMock = [
    {
        id: 'CR-2026-031',
        project: 'project_example_01',
        title: '항목 설명 입력 기능 추가',
        requester: 'team_example_01',
        priority: 'HIGH',
        status: 'ANALYZED',
        riskScore: 78,
        createdAt: '2026-07-23',
    },
    {
        id: 'CR-2026-030',
        project: 'project_example_02',
        title: '목록 정렬 조건 변경',
        requester: 'team_example_02',
        priority: 'MEDIUM',
        status: 'REVIEW',
        riskScore: 54,
        createdAt: '2026-07-22',
    },
    {
        id: 'CR-2026-029',
        project: 'project_example_03',
        title: '상태 표시 문구 변경',
        requester: 'team_example_03',
        priority: 'LOW',
        status: 'REQUESTED',
        riskScore: null,
        createdAt: '2026-07-22',
    },
]

export const analysisHistoryMock = [
    {
        id: 'AN-2026-048',
        project: 'project_example_01',
        model: 'doenaai-impact-v1.4.2',
        risk: 'HIGH',
        score: 78,
        duration: '2분 14초',
        reviewer: 'reviewer_example_01',
        result: 'APPROVED',
        analyzedAt: '2026-07-23 11:02',
    },
    {
        id: 'AN-2026-047',
        project: 'project_example_02',
        model: 'doenaai-impact-v1.4.2',
        risk: 'MEDIUM',
        score: 54,
        duration: '1분 49초',
        reviewer: 'reviewer_example_02',
        result: 'CORRECTED',
        analyzedAt: '2026-07-22 17:42',
    },
    {
        id: 'AN-2026-046',
        project: 'project_example_03',
        model: 'doenaai-impact-v1.4.1',
        risk: 'LOW',
        score: 31,
        duration: '1분 22초',
        reviewer: '-',
        result: 'PENDING',
        analyzedAt: '2026-07-22 14:18',
    },
]

export const modelsMock = {
    active: {
        name: 'doenaai-impact-v1.4.2',
        baseModel: 'Qwen Local Base',
        adapter: 'impact-lora-v1.4.2',
        status: 'ACTIVE',
        deployedAt: '2026-07-20 22:15',
        server: 'AI 분석/추론 서버',
        accuracy: 87.6,
    },
    versions: [
        { version: 'v1.4.2', adapter: 'impact-lora-v1.4.2', dataset: 'dataset-2026-07-18', accuracy: 87.6, status: 'ACTIVE', createdAt: '2026-07-20' },
        { version: 'v1.4.1', adapter: 'impact-lora-v1.4.1', dataset: 'dataset-2026-07-10', accuracy: 84.9, status: 'STANDBY', createdAt: '2026-07-12' },
        { version: 'v1.3.0', adapter: 'impact-lora-v1.3.0', dataset: 'dataset-2026-06-25', accuracy: 80.8, status: 'ARCHIVED', createdAt: '2026-06-28' },
    ],
    evaluations: [
        { label: '위험등급 정확도', value: 87.6, delta: '+2.7%' },
        { label: '영향 파일 Recall', value: 91.2, delta: '+1.4%' },
        { label: 'DB 영향 Recall', value: 88.4, delta: '+3.1%' },
        { label: '형식 준수율', value: 99.1, delta: '+0.3%' },
    ],
}

export const documentsMock = [
    {
        id: 'DOC-101',
        name: 'document_example_01.pdf',
        project: 'project_example_01',
        type: '요구사항',
        size: '2.4 MB',
        indexed: true,
        updatedAt: '2026-07-23 09:12',
    },
    {
        id: 'DOC-100',
        name: 'document_example_02.xlsx',
        project: 'project_example_02',
        type: '인터페이스',
        size: '840 KB',
        indexed: true,
        updatedAt: '2026-07-22 16:30',
    },
    {
        id: 'DOC-099',
        name: 'document_example_03.md',
        project: 'project_example_03',
        type: '업무절차',
        size: '42 KB',
        indexed: false,
        updatedAt: '2026-07-22 13:05',
    },
]
