import assert from 'node:assert/strict'
import test from 'node:test'
import { evidenceUnavailableReasonLabel } from '../src/lib/evidenceReuse.js'

test('이전 수집 자료를 재사용하지 못하는 이유와 새 근거 수집 방법을 안내한다', () => {
    const label = evidenceUnavailableReasonLabel('SOURCE_COLLECTOR_OUTDATED')
    assert.match(label, /소스 수집 방식이 업데이트/)
    assert.match(label, /최신 Git·DB 확인/)
    assert.doesNotMatch(label, /크기 제한|삭제/)
})
