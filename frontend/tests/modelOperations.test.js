import assert from 'node:assert/strict'
import test from 'node:test'
import { createTrainingPoller, probeMatchesSelection } from '../src/lib/modelOperations.js'

const now = 1700000000000
const model = { name: 'local-model:latest', digest: 'a'.repeat(64), selectable: true }
const snapshot = { currentRevision: 3 }
const passed = {
    status: 'PASSED',
    model: model.name,
    digest: model.digest,
    currentRevision: 3,
    probeToken: 'temporary_probe_token_12345678901234567890',
    expiresAt: new Date(now + 60000).toISOString(),
}

test('모델과 파일 식별값과 선택 버전과 유효 시간이 모두 맞는 시험 증거만 허용한다', () => {
    assert.equal(probeMatchesSelection(passed, model, snapshot, now), true)
})

for (const [label, change] of [
    ['통과하지 않은 상태', { status: 'FAILED' }],
    ['다른 모델', { model: 'other:latest' }],
    ['다른 파일 식별값', { digest: 'b'.repeat(64) }],
    ['다른 선택 버전', { currentRevision: 4 }],
    ['빈 시험 토큰', { probeToken: '' }],
    ['없는 시험 토큰', { probeToken: undefined }],
    ['객체 시험 토큰', { probeToken: {} }],
    ['공백 시험 토큰', { probeToken: '   ' }],
    ['만료 시각 도달', { expiresAt: new Date(now).toISOString() }],
    ['지난 만료 시각', { expiresAt: new Date(now - 1).toISOString() }],
    ['잘못된 만료 시각', { expiresAt: 'invalid-date' }],
    ['없는 만료 시각', { expiresAt: undefined }],
]) {
    test(`${label} 시험 증거는 전환 근거로 사용하지 않는다`, () => {
        assert.equal(probeMatchesSelection({ ...passed, ...change }, model, snapshot, now), false)
    })
}

test('선택 불가와 형식이 잘못된 선택 가능 표시는 모두 거절한다', () => {
    for (const selectable of [false, undefined, null, 'false', 1]) {
        assert.equal(probeMatchesSelection(passed, { ...model, selectable }, snapshot, now), false)
    }
})

test('모델이나 현재 선택 상태가 없으면 시험 증거를 인정하지 않는다', () => {
    assert.equal(probeMatchesSelection(null, model, snapshot, now), false)
    assert.equal(probeMatchesSelection(passed, null, snapshot, now), false)
    assert.equal(probeMatchesSelection(passed, model, null, now), false)
})

test('양쪽에 같은 잘못된 값이 있어도 누락된 모델과 해시와 버전을 통과시키지 않는다', () => {
    for (const value of [undefined, null, '']) {
        assert.equal(probeMatchesSelection({ ...passed, model: value }, { ...model, name: value }, snapshot, now), false)
        assert.equal(probeMatchesSelection({ ...passed, digest: value }, { ...model, digest: value }, snapshot, now), false)
    }
    for (const value of [undefined, null, '', '3', -1, 1.5, Infinity]) {
        assert.equal(probeMatchesSelection({ ...passed, currentRevision: value }, model, { currentRevision: value }, now), false)
    }
})

const deferred = () => {
    let resolve, reject
    const promise = new Promise((done, fail) => {
        resolve = done
        reject = fail
    })
    return { promise, resolve, reject }
}

const harness = (fetchStatus) => {
    let hidden = false
    let sequence = 0
    const timers = new Map()
    const applied = []
    const errors = []
    const poller = createTrainingPoller({
        fetchStatus,
        applyStatus: (value) => applied.push(value),
        onError: (error) => errors.push(error),
        isHidden: () => hidden,
        setTimer: (callback, delay) => {
            const id = ++sequence
            timers.set(id, { callback, delay })
            return id
        },
        clearTimer: (id) => timers.delete(id),
    })
    return {
        poller,
        applied,
        errors,
        timers,
        hide: (value) => (hidden = value),
        fire: () => {
            const [id, timer] = timers.entries().next().value
            timers.delete(id)
            return timer.callback()
        },
    }
}

test('활성 학습 응답 뒤에만 5초 순차 조회를 예약하고 종료하면 멈춘다', async () => {
    let calls = 0
    const h = harness(async () => ({ jobs: [{ status: ++calls === 1 ? 'TRAINING' : 'SUCCEEDED' }] }))
    await h.poller.refresh()
    assert.equal(calls, 1)
    assert.deepEqual(
        [...h.timers.values()].map((timer) => timer.delay),
        [5000]
    )
    await h.fire()
    assert.equal(calls, 2)
    assert.equal(h.timers.size, 0)
    assert.equal(h.applied.length, 2)
})

test('응답 대기 중에는 다음 자동 조회를 예약하지 않는다', async () => {
    const first = deferred()
    const second = deferred()
    let calls = 0
    const h = harness(() => (++calls === 1 ? first.promise : second.promise))
    const initial = h.poller.refresh()
    assert.equal(calls, 1)
    assert.equal(h.timers.size, 0)
    first.resolve({ jobs: [{ status: 'PREPARING' }] })
    await initial
    const next = h.fire()
    assert.equal(calls, 2)
    assert.equal(h.timers.size, 0)
    second.resolve({ jobs: [{ status: 'VERIFYING' }] })
    await next
    assert.equal(h.timers.size, 1)
    h.poller.stop()
})

for (const status of ['SUCCEEDED', 'FAILED', 'CANCELLED']) {
    test(`${status} 학습 결과만 있으면 자동 조회를 중단한다`, async () => {
        const h = harness(async () => ({ jobs: [{ status }] }))
        await h.poller.refresh()
        assert.equal(h.applied.length, 1)
        assert.equal(h.timers.size, 0)
    })
}

test('종료 작업과 활성 작업이 섞여 있으면 남은 활성 작업을 계속 확인한다', async () => {
    const h = harness(async () => ({ jobs: [{ status: 'SUCCEEDED' }, { status: 'UNKNOWN' }] }))
    await h.poller.refresh()
    assert.equal(h.timers.size, 1)
    h.poller.stop()
})

test('빈 목록에는 자동 조회를 예약하지 않는다', async () => {
    const h = harness(async () => ({ jobs: [] }))
    await h.poller.refresh()
    assert.equal(h.timers.size, 0)
})

test('숨긴 화면은 조회하지 않고 다시 보일 때 명시적으로 재개한다', async () => {
    let calls = 0
    const h = harness(async () => ({ jobs: [{ status: ++calls ? 'TRAINING' : 'PENDING' }] }))
    h.hide(true)
    await h.poller.refresh()
    assert.equal(calls, 0)
    h.hide(false)
    await h.poller.refresh()
    assert.equal(calls, 1)
    h.hide(true)
    h.poller.pause()
    assert.equal(h.timers.size, 0)
    h.hide(false)
    await h.poller.refresh()
    assert.equal(calls, 2)
    h.poller.stop()
})

test('응답 대기 중 화면이 숨겨지면 늦은 응답을 표시하거나 예약하지 않는다', async () => {
    const pending = deferred()
    const h = harness(() => pending.promise)
    const request = h.poller.refresh()
    h.hide(true)
    pending.resolve({ jobs: [{ status: 'TRAINING' }] })
    await request
    assert.deepEqual(h.applied, [])
    assert.equal(h.timers.size, 0)
})

test('조회 오류 뒤에는 자동 재시도하지 않고 수동 조회만 허용한다', async () => {
    const failure = new Error('시험용 연결 오류')
    let calls = 0
    const h = harness(async () => {
        if (++calls === 1) throw failure
        return { jobs: [{ status: 'TRAINING' }] }
    })
    await h.poller.refresh()
    assert.deepEqual(h.errors, [failure])
    assert.equal(h.timers.size, 0)
    assert.equal(calls, 1)
    await h.poller.refresh()
    assert.equal(calls, 2)
    assert.equal(h.timers.size, 1)
    h.poller.stop()
})

test('일시 중지 전 응답이 나중에 와도 새 조회 결과를 덮지 않는다', async () => {
    const first = deferred()
    let calls = 0
    const latest = { jobs: [{ status: 'SUCCEEDED' }] }
    const h = harness(() => (++calls === 1 ? first.promise : Promise.resolve(latest)))
    const old = h.poller.refresh()
    h.poller.pause()
    await h.poller.refresh()
    first.resolve({ jobs: [{ status: 'TRAINING' }] })
    await old
    assert.deepEqual(h.applied, [latest])
    assert.equal(h.timers.size, 0)
})

test('종료하면 예약을 지우고 이후 수동 조회도 보내지 않는다', async () => {
    let calls = 0
    const h = harness(async () => {
        calls += 1
        return { jobs: [{ status: 'TRAINING' }] }
    })
    await h.poller.refresh()
    h.poller.stop()
    assert.equal(h.timers.size, 0)
    await h.poller.refresh()
    assert.equal(calls, 1)
})

test('종료 뒤 도착한 응답과 오류를 모두 무시한다', async () => {
    for (const fail of [false, true]) {
        const pending = deferred()
        const h = harness(() => pending.promise)
        const request = h.poller.refresh()
        h.poller.stop()
        if (fail) pending.reject(new Error('종료 뒤 도착한 시험 오류'))
        else pending.resolve({ jobs: [{ status: 'TRAINING' }] })
        await request
        assert.deepEqual(h.applied, [])
        assert.deepEqual(h.errors, [])
        assert.equal(h.timers.size, 0)
    }
})
