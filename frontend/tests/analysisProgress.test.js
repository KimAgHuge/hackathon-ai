import assert from 'node:assert/strict'
import test from 'node:test'
import { activeAnalysisStage, analysisActivity, analysisElapsedSeconds, formatAnalysisElapsed } from '../src/lib/analysisProgress.js'

test('shows the real active phase without inventing progress percentages', () => {
    const job = { status: 'RUNNING', sourceProgress: 0, databaseProgress: 0, relationProgress: 0 }
    assert.equal(activeAnalysisStage(job), 'SRC')
    assert.match(analysisActivity(job), /Git/)

    job.sourceProgress = 40
    assert.equal(activeAnalysisStage(job), 'DB')
    assert.match(analysisActivity(job), /DB/)

    job.databaseProgress = 100
    assert.equal(activeAnalysisStage(job), 'AI')
    assert.match(analysisActivity(job), /참조 관계/)

    job.sourceProgress = 100
    job.relationProgress = 40
    assert.match(analysisActivity(job), /AI 모델/)

    job.relationProgress = 90
    assert.match(analysisActivity(job), /저장/)

    job.status = 'COMPLETED'
    assert.equal(activeAnalysisStage(job), null)
    assert.equal(analysisActivity(job), null)
})

test('elapsed time advances while running but freezes at completion', () => {
    const start = '2026-10-14T01:00:00Z'
    const job = { status: 'RUNNING', startedAt: start }
    assert.equal(analysisElapsedSeconds(job, Date.parse('2026-10-14T01:02:03Z')), 123)
    assert.equal(formatAnalysisElapsed(123), '2분 3초')

    job.status = 'COMPLETED'
    job.completedAt = '2026-10-14T02:01:01Z'
    assert.equal(analysisElapsedSeconds(job, Date.parse('2026-10-15T00:00:00Z')), 3661)
    assert.equal(formatAnalysisElapsed(3661), '1시간 1분 1초')
    assert.equal(analysisElapsedSeconds({}, Date.now()), null)
})
