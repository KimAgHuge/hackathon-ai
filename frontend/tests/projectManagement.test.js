import assert from 'node:assert/strict'
import test from 'node:test'
import { createProjectCrud } from '../src/api/projectCrud.js'
import { buildProjectUpdatePayload, filterProjects, projectErrorMessage, projectForm } from '../src/lib/projectManagement.js'
import { createProjectActionRunner, createProjectRequestScope } from '../src/lib/projectRequests.js'
import { createProjectMockRepository } from '../src/mocks/projectRepositoryMock.js'

const project = (id = 'project-a') => ({
    id,
    organizationId: 'organization-a',
    name: '실제 프로젝트',
    description: null,
    sourceType: 'GIT',
    repositoryUrl: 'https://example.com/project.git',
    defaultBranch: 'release/2026',
    status: 'ACTIVE',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-14T00:00:00Z',
})
const deferred = () => {
    let resolve
    const promise = new Promise((done) => {
        resolve = done
    })
    return { promise, resolve }
}
const memoryStorage = () => {
    let value = null
    return {
        getItem: () => value,
        setItem: (_, next) => {
            value = next
        },
    }
}

test('실제 응답의 저장소·브랜치·설명으로 검색하며 없는 기술 스택을 가정하지 않는다', () => {
    const rows = [project(), { ...project('project-b'), repositoryUrl: null, description: '별도 설명', sourceType: 'ZIP' }]
    assert.deepEqual(filterProjects(rows, ' RELEASE/2026 '), rows)
    assert.deepEqual(filterProjects(rows, '별도 설명'), [rows[1]])
    assert.deepEqual(filterProjects(rows, 'undefined'), [])
})

test('수정 요청은 조직·소스 유형을 원본에서 유지하고 비밀값·응답 전용 필드를 제외한다', () => {
    const payload = buildProjectUpdatePayload(project(), {
        ...projectForm(project()),
        name: ' 새 이름 ',
        organizationId: 'other',
        sourceType: 'ZIP',
        password: 'test-only',
        createdBy: 'other',
    })
    assert.deepEqual(payload, {
        organizationId: 'organization-a',
        name: '새 이름',
        description: null,
        sourceType: 'GIT',
        repositoryUrl: 'https://example.com/project.git',
        defaultBranch: 'release/2026',
        status: 'ACTIVE',
    })
})

test('이름·브랜치·상태·저장소 길이를 검사하고 압축파일의 빈 저장소를 허용한다', () => {
    for (const invalid of [
        { name: '  ' },
        { name: 'a'.repeat(201) },
        { defaultBranch: '' },
        { defaultBranch: 'a'.repeat(201) },
        { status: 'READY' },
        { repositoryUrl: '' },
        { repositoryUrl: 'a'.repeat(2001) },
    ]) {
        assert.throws(() => buildProjectUpdatePayload(project(), { ...projectForm(project()), ...invalid }))
    }
    const zip = { ...project(), sourceType: 'ZIP', repositoryUrl: null }
    assert.equal(buildProjectUpdatePayload(zip, projectForm(zip)).repositoryUrl, null)
})

test('목록·단건·수정·논리 삭제·복구는 서버 경로와 요청 취소 옵션을 유지한다', async () => {
    const calls = []
    const client = Object.fromEntries(
        ['get', 'put', 'delete', 'post'].map((method) => [
            method,
            async (...args) => {
                calls.push([method, ...args])
                return { data: project() }
            },
        ])
    )
    const api = createProjectCrud({ client })
    const options = { signal: new AbortController().signal }
    await api.getProjects({ deleted: true, ...options })
    await api.getProjectById('project/a', options)
    await api.updateProject('project/a', { name: '수정' }, options)
    await api.deleteProject('project/a')
    await api.restoreProject('project/a')
    assert.deepEqual(calls, [
        ['get', '/projects', { ...options, params: { deleted: true } }],
        ['get', '/projects/project%2Fa', options],
        ['put', '/projects/project%2Fa', { name: '수정' }, options],
        ['delete', '/projects/project%2Fa'],
        ['post', '/projects/project%2Fa/restore'],
    ])
})

test('권한이 없거나 조회가 끝나지 않은 경우 저장 요청을 보내지 않는다', async () => {
    const scope = createProjectRequestScope()
    scope.open('project-a')
    let allowed = false
    let ready = true
    let denied = 0
    let calls = 0
    const run = createProjectActionRunner({
        scope,
        canEdit: () => allowed,
        isReady: () => ready,
        onDenied: () => {
            denied++
        },
    })
    const operation = {
        execute: () => {
            calls++
        },
        onSuccess: () => {},
        onError: () => {},
        onPending: () => {},
    }
    assert.equal(await run(operation), false)
    allowed = true
    ready = false
    assert.equal(await run(operation), false)
    assert.equal(calls, 0)
    assert.equal(denied, 1)
})

test('기본정보와 연결 저장이 겹치면 중복 요청을 막고 실패 후 재시도를 허용한다', async () => {
    const scope = createProjectRequestScope()
    scope.open('project-a')
    const run = createProjectActionRunner({ scope, canEdit: () => true, isReady: () => true })
    const pending = deferred()
    let calls = 0
    let error = ''
    const flags = []
    const operation = {
        execute: async () => {
            calls++
            if (calls === 1) throw { response: { status: 409 } }
            await pending.promise
        },
        onSuccess: () => {},
        onError: (failure) => {
            error = projectErrorMessage(failure)
        },
        onPending: (value) => flags.push(value),
    }
    assert.equal(await run(operation), false)
    assert.ok(error)
    const retry = run(operation)
    assert.equal(await run(operation), false)
    pending.resolve()
    assert.equal(await retry, true)
    assert.equal(calls, 2)
    assert.deepEqual(flags, [true, false, true, false])
})

test('프로젝트 전환 뒤 늦은 저장 응답은 새 화면·완료 안내·입력값을 변경하지 않는다', async () => {
    const scope = createProjectRequestScope()
    const first = scope.open('project-a')
    const run = createProjectActionRunner({ scope, canEdit: () => true, isReady: () => true })
    const pending = deferred()
    let changes = 0
    const operation = {
        execute: () => pending.promise,
        onSuccess: () => {
            changes++
        },
        onError: () => {
            changes++
        },
        onPending: () => {
            changes++
        },
    }
    const saving = run(operation)
    const second = scope.open('project-b')
    assert.equal(first.options.signal.aborted, true)
    assert.equal(scope.isCurrent(first), false)
    assert.equal(scope.isCurrent(second), true)
    pending.resolve(project())
    assert.equal(await saving, false)
    assert.equal(changes, 1)
    assert.equal(await run({ ...operation, execute: async () => project('project-b') }), true)
})

test('화면 종료 뒤 조회·저장 응답을 무시하고 새 요청도 받지 않는다', async () => {
    const scope = createProjectRequestScope()
    const ticket = scope.open('project-a')
    const run = createProjectActionRunner({ scope, canEdit: () => true, isReady: () => true })
    const pending = deferred()
    let updated = false
    const operation = {
        execute: () => pending.promise,
        onSuccess: () => {
            updated = true
        },
        onError: () => {},
        onPending: () => {},
    }
    const saving = run(operation)
    scope.dispose()
    pending.resolve()
    assert.equal(await saving, false)
    assert.equal(updated, false)
    assert.equal(ticket.options.signal.aborted, true)
    assert.equal(scope.open('project-b'), null)
    assert.equal(await run(operation), false)
})

test('접근 거부·없는 프로젝트·로그인 만료는 사용자 안내로 구분한다', () => {
    assert.match(projectErrorMessage({ response: { status: 403 } }), /권한/)
    assert.match(projectErrorMessage({ response: { status: 404 } }), /찾을 수/)
    assert.match(projectErrorMessage({ response: { status: 401 } }), /로그인/)
})

test('데모 수정은 목록·상세·새 저장소에서 유지되고 접속 비밀을 저장하지 않는다', () => {
    const storage = memoryStorage()
    const repository = createProjectMockRepository({ storage, seed: [project()] })
    repository.update('project-a', { ...buildProjectUpdatePayload(project(), projectForm(project())), name: '저장된 이름', password: 'test-only-secret' })
    assert.equal(repository.list()[0].name, '저장된 이름')
    assert.equal(repository.get('project-a').name, '저장된 이름')
    assert.equal(createProjectMockRepository({ storage, seed: [project()] }).get('project-a').name, '저장된 이름')
    assert.equal(storage.getItem().includes('test-only-secret'), false)
    assert.throws(
        () => repository.get('missing'),
        (error) => error.response.status === 404
    )
    assert.throws(
        () => repository.update('project-a', { organizationId: 'other' }),
        (error) => error.response.status === 409
    )
})

test('데모 논리 삭제·복구가 새로고침 뒤에도 유지되며 상세 조회를 차단한다', () => {
    const storage = memoryStorage()
    const repository = createProjectMockRepository({ storage, seed: [project()] })
    repository.remove('project-a')
    assert.deepEqual(repository.list(), [])
    assert.equal(repository.list({ deleted: true })[0].id, 'project-a')
    const reloaded = createProjectMockRepository({ storage, seed: [project()] })
    assert.throws(
        () => reloaded.get('project-a'),
        (error) => error.response.status === 404
    )
    reloaded.restore('project-a')
    assert.equal(createProjectMockRepository({ storage, seed: [project()] }).get('project-a').name, '실제 프로젝트')
})

test('데모 신규 등록도 같은 목록과 상세에서 조회한다', () => {
    const repository = createProjectMockRepository({ seed: [] })
    const created = repository.create({
        organizationId: 'organization-a',
        name: '등록',
        repositoryUrl: 'https://example.com/new.git',
        defaultBranch: 'main',
        sourceType: 'GIT',
    })
    assert.equal(repository.list()[0].id, created.id)
    assert.equal(repository.get(created.id).name, '등록')
})
