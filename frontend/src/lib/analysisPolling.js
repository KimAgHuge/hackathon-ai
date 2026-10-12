const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const NON_RETRYABLE_STATUSES = new Set([400, 401, 403, 404])

// Schedule after a request finishes so a slow analysis server cannot cause overlapping polls.
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
        start(jobId) {
            stop()
            current = { jobId, controller: new AbortController(), busy: false, terminal: false, timer: null }
            void poll(current)
        },
        refresh() {
            if (!current || current.busy) return
            clearTimer(current.timer)
            current.timer = null
            void poll(current)
        },
        stop,
    }
}
