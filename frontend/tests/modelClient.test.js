import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { createModelApi } from '../src/api/modelClient.js'

test('실제 모델 관리 경로에 목록·상태·상세를 각각 요청한다', async () => {
    const calls = []
    const api = createModelApi({
        async get(path) {
            calls.push(path)
            return { data: { path } }
        },
    })
    assert.deepEqual(await api.getModelRegistry(), { path: '/models' })
    assert.deepEqual(await api.getModelRuntime(), { path: '/models/runtime' })
    assert.deepEqual(await api.getModelVersion('version-example'), { path: '/models/versions/version-example' })
    assert.deepEqual(calls, ['/models', '/models/runtime', '/models/versions/version-example'])
})

test('등록·평가 요청은 본문을 보존하고 식별자를 경로로 해석하지 않는다', async () => {
    const calls = []
    const api = createModelApi({
        async post(path, body) {
            calls.push({ path, body })
            return { data: { id: 'saved-example' } }
        },
    })
    const version = { modelName: '모델 예시', baseModelRef: 'provider/model-example', version: 'v-example', artifactType: 'LORA' }
    const evaluation = { status: 'PASSED', notes: '담당자가 확인한 평가 결과 예시입니다.', metrics: {} }
    assert.deepEqual(await api.registerModelVersion(version), { id: 'saved-example' })
    assert.deepEqual(await api.recordModelEvaluation('a/b?x=1', evaluation), { id: 'saved-example' })
    assert.equal(calls[0].path, '/models/versions')
    assert.equal(calls[0].body, version)
    assert.equal(calls[1].path, '/models/versions/a%2Fb%3Fx%3D1/evaluations')
    assert.equal(calls[1].body, evaluation)
})

test('조회 실패를 예시 데이터로 숨기거나 배포 명령으로 재시도하지 않는다', async () => {
    const failure = new Error('시험용 연결 실패')
    const calls = []
    const api = createModelApi({
        async get(path) {
            calls.push(path)
            throw failure
        },
    })
    await assert.rejects(api.getModelRegistry(), (error) => error === failure)
    await assert.rejects(api.getModelRuntime(), (error) => error === failure)
    assert.deepEqual(calls, ['/models', '/models/runtime'])
})

test('모델 화면 API는 예시 모드와 무관하게 인증이 적용되는 공통 연결을 사용한다', () => {
    const source = readFileSync(new URL('../src/api/modelApi.js', import.meta.url), 'utf8')
    assert.match(source, /httpClient/)
    assert.doesNotMatch(source, /VITE_USE_MOCK|modelsMock|mockResponse|localStorage/)
})

test('설치 모델·학습·작업 이력은 실제 경로로 조회하고 식별자를 안전하게 인코딩한다', async () => {
    const calls = []
    const api = createModelApi({
        async get(path) {
            calls.push(path)
            return { data: { path } }
        },
    })
    await api.getInstalledModels()
    await api.getModelTraining()
    await api.getModelOperations()
    await api.getModelOperation('a/b?x=1')
    assert.deepEqual(calls, ['/models/installed', '/models/training', '/models/operations', '/models/operations/a%2Fb%3Fx%3D1'])
})

test('검증은 긴 제한 시간을 사용하고 변경·학습 요청은 원본 식별자와 본문을 보존한다', async () => {
    const calls = []
    const api = createModelApi({
        async post(path, body, options) {
            calls.push({ path, body, options })
            return { data: { status: 'UNKNOWN' } }
        },
    })
    const body = { operationId: 'same-request-id', model: 'installed:model', digest: 'digest', expectedRevision: 2 }
    await api.probeInstalledModel(body)
    await api.activateInstalledModel(body)
    await api.rollbackInstalledModel(body)
    await api.startModelTraining(body)
    await api.cancelModelTraining('job/id', body)
    assert.deepEqual(
        calls.map(({ path }) => path),
        ['/models/probe', '/models/activate', '/models/rollback', '/models/training', '/models/training/job%2Fid/cancel']
    )
    assert.equal(calls[0].options.timeout, 140000)
    assert.ok(calls.slice(1).every((call) => call.options.timeout === 15000))
    assert.ok(calls.every((call) => call.body === body))
})

test('변경 응답이 끊겨도 어댑터가 자동으로 새 요청을 만들거나 재시도하지 않는다', async () => {
    let count = 0
    const failure = new Error('응답 끊김 예시')
    const api = createModelApi({
        async post() {
            count += 1
            throw failure
        },
    })
    await assert.rejects(api.activateInstalledModel({ operationId: 'preserved-id' }), (error) => error === failure)
    assert.equal(count, 1)
})

test('선택 모델을 학습 조회 조건으로 보내고 해당 완료 작업에 어댑터 준비를 요청한다', async () => {
    const calls = []
    const api = createModelApi({
        async get(path, options) {
            calls.push({ path, options })
            return { data: {} }
        },
        async post(path, body, options) {
            calls.push({ path, body, options })
            return { data: { status: 'WAITING' } }
        },
    })
    await api.getModelTraining('other:model')
    assert.deepEqual(calls[0], { path: '/models/training', options: { params: { model: 'other:model' } } })
    await api.prepareModelAdapter('job/id?x=1')
    assert.deepEqual(calls[1], { path: '/models/adapters/job%2Fid%3Fx%3D1/prepare', body: {}, options: { timeout: 30000 } })
})
