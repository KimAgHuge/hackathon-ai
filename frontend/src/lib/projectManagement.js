/** 프로젝트 기본정보 응답의 표시와 서버에 전달할 수정 내용을 관리한다. */
export const projectStatusOptions = [
    { value: 'ACTIVE', label: '활성' },
    { value: 'INACTIVE', label: '비활성' },
    { value: 'ARCHIVED', label: '보관' },
]

export const sourceTypeLabel = (value) => ({ GIT: 'Git 저장소', ZIP: 'ZIP 파일' })[value] || value || '정보 없음'

export const formatProjectDate = (value) => {
    if (!value) return '—'
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('ko-KR')
}

/** 서버 응답에 없는 기술 스택을 만들어 넣지 않고 실제 기본정보만 검색한다. */
export const filterProjects = (projects, keyword) => {
    const query = keyword.trim().toLowerCase()
    if (!query) return projects
    return projects.filter((project) =>
        [project.id, project.name, project.description, project.repositoryUrl, project.defaultBranch, project.sourceType, project.organizationId].some(
            (value) =>
                String(value ?? '')
                    .toLowerCase()
                    .includes(query)
        )
    )
}

export const projectForm = (project) => ({
    name: project.name || '',
    description: project.description || '',
    repositoryUrl: project.repositoryUrl || '',
    defaultBranch: project.defaultBranch || '',
    status: project.status,
})

/** 변경할 수 없는 조직·소스 유형은 조회한 원본에서 가져온다. 접속 비밀과 응답 전용 필드는 보내지 않는다. */
export const buildProjectUpdatePayload = (original, form) => {
    const name = String(form.name ?? '').trim()
    const defaultBranch = String(form.defaultBranch ?? '').trim()
    const repositoryUrl = String(form.repositoryUrl ?? '').trim()
    if (!original.organizationId) throw new Error('프로젝트의 소유 조직 정보를 확인할 수 없습니다.')
    if (!name || name.length > 200) throw new Error('프로젝트 이름은 1~200자로 입력하세요.')
    if (!defaultBranch || defaultBranch.length > 200) throw new Error('기본 브랜치는 1~200자로 입력하세요.')
    if (repositoryUrl.length > 2000) throw new Error('저장소 주소는 2000자 이하로 입력하세요.')
    if (original.sourceType === 'GIT' && !repositoryUrl) throw new Error('Git 프로젝트의 저장소 주소를 입력하세요.')
    if (!projectStatusOptions.some((option) => option.value === form.status)) throw new Error('프로젝트 상태를 선택하세요.')
    return {
        organizationId: original.organizationId,
        name,
        description: String(form.description ?? '').trim() || null,
        repositoryUrl: repositoryUrl || null,
        defaultBranch,
        sourceType: original.sourceType,
        status: form.status,
    }
}

/** 서버 오류와 접근 거부를 화면에서 이해할 수 있는 안내로 바꾼다. */
export const projectErrorMessage = (error) => {
    const status = error?.response?.status
    if (status === 401) return '로그인이 만료됐습니다. 다시 로그인한 뒤 시도하세요.'
    if (status === 403) return '이 프로젝트를 조회하거나 수정할 권한이 없습니다.'
    if (status === 404) return '프로젝트를 찾을 수 없습니다. 목록에서 다시 선택하세요.'
    if (status === 409) return error?.response?.data?.message || '프로젝트 정보를 다시 확인한 뒤 저장하세요.'
    return error?.response?.data?.message || error?.message || '요청을 처리하지 못했습니다. 다시 시도하세요.'
}
