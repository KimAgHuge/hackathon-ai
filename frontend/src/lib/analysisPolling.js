const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const NON_RETRYABLE_STATUSES = new Set([400, 401, 403, 404])

/**
 * @param {object} handlers 작업/결과 조회 함수와 화면 갱신 콜백
 * @returns {{start: Function, refresh: Function, stop: Function}} 중복 요청 없이 상태를 조회하는 제어기
 * 각 요청이 끝난 뒤 다음 조회를 예약하고, 작업 변경·화면 종료 시 이전 요청을 취소한다.
 */
export const createAnalysisPoller = ({
    loadJob,
    loadResult,
    onJob,
    onResult,
    onError,
    onState,
    interval = 2500,
    setTimer = setTimeout,
    clearTimer = clearTimeout,
}) => {
    let current = null

    const isCurrent = (run) => current === run && !run.controller.signal.aborted

    const poll = async (run) => {
        if (!isCurrent(run) || run.busy) return

        run.busy = true
        run.retryBlocked = false
        let phase = 'status'
        onState({ loading: true, retryScheduled: false })

        try {
            const options = { signal: run.controller.signal }
            const job = await loadJob(run.jobId, options)
            if (!isCurrent(run)) return

            run.terminal = TERMINAL_STATUSES.has(job.status)
            onJob(job)
            onError(null)

            if (job.status === 'COMPLETED') {
                phase = 'result'
                const result = await loadResult(run.jobId, options)
                if (!isCurrent(run)) return
                onResult(result)
            }
        } catch (error) {
            if (!isCurrent(run)) return

            run.retryBlocked = NON_RETRYABLE_STATUSES.has(error.response?.status)
            onError({ error, phase })
        } finally {
            run.busy = false

            if (isCurrent(run)) {
                const retryScheduled = !run.terminal && !run.retryBlocked
                onState({ loading: false, retryScheduled })

                if (retryScheduled) {
                    run.timer = setTimer(() => {
                        run.timer = null
                        void poll(run)
                    }, interval)
                }
            }
        }
    }

    const stop = () => {
        if (!current) return
        clearTimer(current.timer)
        current.controller.abort()
        current = null
    }

    return {
        /** jobId의 자동 조회를 시작하고 이전 작업의 요청·타이머를 취소한다. */
        start(jobId) {
            stop()
            current = { jobId, controller: new AbortController(), busy: false, terminal: false, timer: null }
            void poll(current)
        },
        /** 현재 작업을 즉시 다시 조회한다; 진행 중인 요청은 중복 실행하지 않는다. */
        refresh() {
            if (!current || current.busy) return
            clearTimer(current.timer)
            current.timer = null
            void poll(current)
        },
        stop,
    }
}
