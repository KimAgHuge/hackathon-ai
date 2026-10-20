const STORAGE_KEY = 'doenaai.demo.projects.v1'

const initialProjects = [
    {
        id: '00000000-0000-0000-0000-000000000101',
        organizationId: '00000000-0000-0000-0000-000000000100',
        name: 'project_example',
        description: '프로젝트 목록·상세·수정을 확인하기 위한 데모입니다.',
        repositoryUrl: 'https://github.com/example/doenaai-demo.git',
        defaultBranch: 'main',
        sourceType: 'GIT',
        sourceFileName: null,
        status: 'ACTIVE',
        createdBy: null,
        createdAt: '2026-10-14T00:00:00Z',
        updatedAt: '2026-10-14T00:00:00Z',
    },
]

/** 브라우저 데모에는 기본정보 항목만 저장한다. 소스 저장소와 데이터베이스의 접속 비밀은 받지 않는다. */
const publicProject = (project) => Object.fromEntries(Object.keys(initialProjects[0]).map((key) => [key, project[key] ?? null]))
const apiError = (status, message) => Object.assign(new Error(message), { response: { status, data: { message } } })

export const createProjectMockRepository = ({ storage = null, seed = initialProjects, seedNameVersion = 1 } = {}) => {
    let projects = seed.map(publicProject)
    let deletedIds = new Set()
    let migratedSeedNames = false
    try {
        const stored = storage?.getItem(STORAGE_KEY)
        if (stored) {
            const saved = JSON.parse(stored)
            const parsed = Array.isArray(saved) ? saved : saved.projects
            if (
                Array.isArray(parsed) &&
                parsed.every((p) => p && typeof p.id === 'string' && typeof p.name === 'string' && typeof p.organizationId === 'string')
            ) {
                const seedById = new Map(seed.map((p) => [p.id, p]))
                migratedSeedNames = seedNameVersion > 1 && saved.seedNameVersion !== seedNameVersion
                // 이전 기본 예시의 이름만 교체한다. 사용자가 추가한 행·다른 필드·삭제 표식은 보존한다.
                const restored = parsed.map((p) => (migratedSeedNames && seedById.has(p.id) ? { ...p, name: seedById.get(p.id).name } : p))
                projects = [...seed.filter((p) => !parsed.some((savedProject) => savedProject.id === p.id)), ...restored].map(publicProject)
                deletedIds = new Set(Array.isArray(saved.deletedIds) ? saved.deletedIds : [])
            }
        }
    } catch {
        // 저장 공간을 쓸 수 없거나 값이 손상됐으면 메모리에서 데모를 계속 실행한다.
    }
    const persist = () => {
        try {
            storage?.setItem(STORAGE_KEY, JSON.stringify({ projects, deletedIds: [...deletedIds], seedNameVersion }))
        } catch {
            /* 저장 공간 제한 시 메모리 상태를 유지한다. */
        }
    }
    if (migratedSeedNames) persist()
    const find = (id, includeDeleted = false) => {
        const project = projects.find((p) => p.id === id)
        if (!project || (!includeDeleted && deletedIds.has(id))) throw apiError(404, '프로젝트를 찾을 수 없습니다.')
        return project
    }
    return {
        list: ({ deleted = false } = {}) => structuredClone(projects.filter((p) => deletedIds.has(p.id) === deleted)),
        get: (id) => structuredClone(find(id)),
        create: (payload) => {
            const now = new Date().toISOString()
            const project = publicProject({
                ...payload,
                id: crypto.randomUUID(),
                sourceType: payload.sourceType || 'GIT',
                defaultBranch: payload.defaultBranch || 'main',
                status: 'ACTIVE',
                createdAt: now,
                updatedAt: now,
            })
            projects.push(project)
            persist()
            return structuredClone(project)
        },
        update: (id, payload) => {
            const original = find(id)
            if (payload.organizationId !== original.organizationId) throw apiError(409, '프로젝트의 소유 조직은 변경할 수 없습니다.')
            const updated = publicProject({ ...original, ...payload, id, createdAt: original.createdAt, updatedAt: new Date().toISOString() })
            projects = projects.map((p) => (p.id === id ? updated : p))
            persist()
            return structuredClone(updated)
        },
        remove: (id) => {
            find(id)
            deletedIds.add(id)
            persist()
        },
        restore: (id) => {
            find(id, true)
            deletedIds.delete(id)
            persist()
        },
    }
}
