import assert from 'node:assert/strict'
import test from 'node:test'
import { setImmediate as flush } from 'node:timers/promises'
import { createAnalysisPoller } from '../src/lib/analysisPolling.js'

const deferred = () => {
    let resolve
    let reject
    const promise = new Promise((resolvePromise, rejectPromise) => {
        resolve = resolvePromise
        reject = rejectPromise
    })
    return { promise, resolve, reject }
}

const setup = (overrides = {}) => {
    const jobs = []
    const results = []
    const errors = []
    const states = []
    const timers = new Map()
    let timerId = 0
    const poller = createAnalysisPoller({
        loadJob: async (jobId) => ({ jobId, status: 'RUNNING' }),
        loadResult: async (jobId) => ({ jobId, summary: '분석 완료' }),
        onJob: (job) => jobs.push(job),
        onResult: (result) => results.push(result),
        onError: (error) => errors.push(error),
        onState: (state) => states.push(state),
        setTimer: (callback, delay) => {
            timers.set(++timerId, { callback, delay })
            return timerId
        },
        clearTimer: (id) => timers.delete(id),
        ...overrides,
    })
    const tick = () => {
        const [id, timer] = timers.entries().next().value
        timers.delete(id)
        timer.callback()
    }
    return { poller, jobs, results, errors, states, timers, tick }
}

test('polls sequentially after 2500ms and fetches the completed result exactly once', async () => {
    const firstRequest = deferred()
    let calls = 0
    const harness = setup({
        loadJob: () => {
            calls += 1
            return calls === 1 ? firstRequest.promise : Promise.resolve({ status: 'COMPLETED' })
        },
    })

    harness.poller.start('job-1')
    harness.poller.refresh()
    assert.equal(calls, 1)
    assert.equal(harness.timers.size, 0)

    firstRequest.resolve({ status: 'RUNNING' })
    await flush()
    assert.equal(harness.timers.values().next().value.delay, 2500)
    harness.tick()
    await flush()

    assert.equal(calls, 2)
    assert.equal(harness.results.length, 1)
    assert.equal(harness.results[0].jobId, 'job-1')
    assert.equal(harness.timers.size, 0)
    assert.deepEqual(harness.states.at(-1), { loading: false, retryScheduled: false })
    harness.poller.stop()
})

test('FAILED is terminal and never fetches a result', async () => {
    const harness = setup({ loadJob: async () => ({ status: 'FAILED', errorMessage: 'DB 연결 실패' }) })
    harness.poller.start('job-1')
    await flush()

    assert.equal(harness.jobs[0].errorMessage, 'DB 연결 실패')
    assert.equal(harness.results.length, 0)
    assert.equal(harness.timers.size, 0)
    harness.poller.stop()
})

test('CANCELLED stops polling without requesting a result', async () => {
    const harness = setup({ loadJob: async () => ({ status: 'CANCELLED' }) })
    harness.poller.start('job-1')
    await flush()
    assert.equal(harness.results.length, 0)
    assert.equal(harness.timers.size, 0)
    harness.poller.stop()
})

test('temporary status errors retry, and manual refresh replaces the pending timer', async () => {
    const error = new Error('Network Error')
    let calls = 0
    const harness = setup({
        loadJob: async () => {
            if (++calls === 1) throw error
            return { status: 'RUNNING' }
        },
    })
    harness.poller.start('job-1')
    await flush()

    assert.deepEqual(harness.errors.at(-1), { error, phase: 'status' })
    assert.equal(harness.timers.size, 1)
    assert.equal(harness.states.at(-1).retryScheduled, true)

    harness.poller.refresh()
    assert.equal(harness.timers.size, 0)
    await flush()
    assert.equal(calls, 2)
    assert.equal(harness.errors.at(-1), null)
    assert.equal(harness.timers.size, 1)
    harness.poller.stop()
    assert.equal(harness.timers.size, 0)
})

for (const status of [400, 401, 403, 404]) {
    test(`HTTP ${status} stops automatic status retries`, async () => {
        const harness = setup({
            loadJob: async () => {
                throw { response: { status } }
            },
        })
        harness.poller.start('job-1')
        await flush()
        assert.equal(harness.timers.size, 0)
        assert.equal(harness.states.at(-1).retryScheduled, false)
        harness.poller.stop()
    })
}

test('route changes abort the old request and ignore its late response', async () => {
    const firstRequest = deferred()
    let oldSignal
    const harness = setup({
        loadJob: async (jobId, { signal }) => {
            if (jobId === 'old-job') {
                oldSignal = signal
                return firstRequest.promise
            }
            return { jobId, status: 'FAILED' }
        },
    })
    harness.poller.start('old-job')
    harness.poller.start('new-job')
    firstRequest.resolve({ jobId: 'old-job', status: 'COMPLETED' })
    await flush()

    assert.equal(oldSignal.aborted, true)
    assert.deepEqual(
        harness.jobs.map(({ jobId }) => jobId),
        ['new-job']
    )
    assert.equal(harness.results.length, 0)
    assert.equal(harness.timers.size, 0)
    harness.poller.stop()
})

test('unmount aborts an in-flight result and prevents state updates', async () => {
    const resultRequest = deferred()
    let signal
    const harness = setup({
        loadJob: async () => ({ status: 'COMPLETED' }),
        loadResult: async (_jobId, options) => {
            signal = options.signal
            return resultRequest.promise
        },
    })
    harness.poller.start('job-1')
    await flush()
    const stateCount = harness.states.length
    harness.poller.stop()
    resultRequest.resolve({ summary: '늦은 결과' })
    await flush()

    assert.equal(signal.aborted, true)
    assert.equal(harness.results.length, 0)
    assert.equal(harness.states.length, stateCount)
    assert.equal(harness.timers.size, 0)
})

test('a completed result fetch error is shown without polling and can be manually retried', async () => {
    let attempts = 0
    const error = { response: { status: 409, data: { message: '결과가 아직 없습니다.' } } }
    const harness = setup({
        loadJob: async () => ({ status: 'COMPLETED' }),
        loadResult: async () => {
            if (++attempts === 1) throw error
            return { summary: '저장된 분석 결과' }
        },
    })
    harness.poller.start('job-1')
    await flush()

    assert.deepEqual(harness.errors.at(-1), { error, phase: 'result' })
    assert.equal(harness.timers.size, 0)
    harness.poller.refresh()
    await flush()
    assert.equal(attempts, 2)
    assert.equal(harness.errors.at(-1), null)
    assert.equal(harness.results[0].summary, '저장된 분석 결과')
    assert.equal(harness.timers.size, 0)
    harness.poller.stop()
})
