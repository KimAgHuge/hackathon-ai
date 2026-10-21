import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('우선 검토 결과는 원본 실행 범위를 표시하고 제외한 테이블을 펼쳐 볼 수 있다', () => {
    const view = readFileSync(new URL('../src/views/AnalysisProgressView.vue', import.meta.url), 'utf8')
    assert.match(view, /analysis\.value\?\.result\?\.databaseReviewCoverage/)
    assert.match(view, /요구사항 관련 범위 우선 검토/)
    assert.match(view, /databaseReviewCoverage\.notice/)
    assert.match(view, /상세 검토에서 제외된 테이블 보기/)
    assert.match(view, /dark:border-slate-700/)
})
