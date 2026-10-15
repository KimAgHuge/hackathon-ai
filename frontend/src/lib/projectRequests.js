/** 프로젝트를 바꾸거나 화면을 닫으면 이전 조회와 저장 응답을 더 이상 반영하지 않는다. */
export const createProjectRequestScope = () => {
    let ticket = null
    let disposed = false
    return {
        open: (id) => {
            ticket?.controller.abort()
            if (disposed) return null
            const controller = new AbortController()
            ticket = { id, controller, options: { signal: controller.signal } }
            return ticket
        },
        current: () => ticket,
        isCurrent: (value) => !disposed && value !== null && value === ticket,
        dispose: () => {
            disposed = true
            ticket?.controller.abort()
            ticket = null
        },
    }
}

/** 권한·준비 상태·중복 요청을 확인하고 현재 프로젝트의 응답에만 화면 갱신을 허용한다. */
export const createProjectActionRunner = ({ scope, canEdit, isReady, onDenied }) => {
    let running = null
    return async ({ execute, onSuccess, onError, onPending }) => {
        const ticket = scope.current()
        if (!scope.isCurrent(ticket) || !isReady() || (running && scope.isCurrent(running))) return false
        if (!canEdit()) {
            onDenied()
            return false
        }
        running = ticket
        onPending(true)
        try {
            const result = await execute(ticket)
            if (!scope.isCurrent(ticket)) return false
            await onSuccess(result, ticket)
            return scope.isCurrent(ticket)
        } catch (error) {
            if (scope.isCurrent(ticket)) onError(error)
            return false
        } finally {
            if (running === ticket) running = null
            if (scope.isCurrent(ticket)) onPending(false)
        }
    }
}
