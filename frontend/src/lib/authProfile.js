// 일시적인 서버·네트워크 오류만 다시 시도하고, 인증·권한 거부는 그대로 전달한다.
export const isRetryableProfileError = (error) => {
    const status = error?.response?.status
    return !status || status === 408 || status === 429 || status >= 500
}

export const loadProfileWithRetry = async (loadProfile) => {
    try {
        return await loadProfile()
    } catch (error) {
        if (!isRetryableProfileError(error)) throw error
        return loadProfile()
    }
}

export const getProfileErrorMessage = (error) => {
    if (error?.response?.status === 401) return '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.'
    if (error?.response?.status === 403) return '계정 프로필 또는 접근 권한을 확인할 수 없습니다. 관리자에게 문의해 주세요.'
    return '계정 권한 정보를 불러오지 못했습니다. 다시 시도해 주세요.'
}
