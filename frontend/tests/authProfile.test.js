import test from 'node:test'
import assert from 'node:assert/strict'
import { getProfileErrorMessage, isRetryableProfileError, loadProfileWithRetry } from '../src/lib/authProfile.js'

test('일시적인 사용자 정보 조회 실패는 한 번 재시도한다', async () => {
    let attempts = 0
    const profile = await loadProfileWithRetry(async () => {
        attempts += 1
        if (attempts === 1) throw new Error('연결 실패')
        return { roles: ['ADMIN'], permissions: ['DASHBOARD_READ'] }
    })

    assert.equal(attempts, 2)
    assert.deepEqual(profile.permissions, ['DASHBOARD_READ'])
})

test('권한 거부는 다시 시도하지 않는다', async () => {
    let attempts = 0
    const denied = { response: { status: 403 } }

    await assert.rejects(
        loadProfileWithRetry(async () => {
            attempts += 1
            throw denied
        }),
        (error) => error === denied
    )
    assert.equal(attempts, 1)
    assert.equal(isRetryableProfileError(denied), false)
})

test('계속 실패하면 재시도 횟수를 제한하고 안내 문구를 구분한다', async () => {
    let attempts = 0
    const unavailable = { response: { status: 503 } }

    await assert.rejects(
        loadProfileWithRetry(async () => {
            attempts += 1
            throw unavailable
        }),
        (error) => error === unavailable
    )
    assert.equal(attempts, 2)
    assert.equal(getProfileErrorMessage(unavailable), '계정 권한 정보를 불러오지 못했습니다. 다시 시도해 주세요.')
    assert.match(getProfileErrorMessage({ response: { status: 401 } }), /다시 로그인/)
    assert.match(getProfileErrorMessage({ response: { status: 403 } }), /관리자에게 문의/)
})
