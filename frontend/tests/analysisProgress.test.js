import assert from 'node:assert/strict'
import test from 'node:test'
import {
    activeAnalysisStage,
    activeActivityEvent,
    analysisActivity,
    analysisActivityEvents,
    analysisElapsedSeconds,
    formatAnalysisElapsed,
} from '../src/lib/analysisProgress.js'

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

test('shows saved activity in newest-first order and only marks an unfinished stage active', () => {
    const job = {
        status: 'RUNNING',
        activityLog: [
            { label: 'Git 소스코드 수집', state: 'STARTED', at: '2026-10-14T01:00:00Z' },
            { label: 'Git 소스코드 수집', state: 'COMPLETED', at: '2026-10-14T01:00:03Z' },
            { label: 'AI 모델의 분석 응답 대기', state: 'STARTED', at: '2026-10-14T01:02:00Z' },
        ],
    }
    assert.deepEqual(
        analysisActivityEvents(job).map((event) => event.state),
        ['STARTED', 'COMPLETED', 'STARTED']
    )
    assert.equal(activeActivityEvent(job).label, 'AI 모델의 분석 응답 대기')
    job.activityLog.push({ label: 'AI 모델의 분석 응답 대기', state: 'WAITING', at: '2026-10-14T01:02:30Z' })
    assert.equal(activeActivityEvent(job).at, '2026-10-14T01:02:00Z')
    assert.equal(analysisActivityEvents(job)[0].state, 'WAITING')
    job.activityLog.push({ label: 'AI 모델의 분석 응답 대기', state: 'COMPLETED', at: '2026-10-14T01:04:00Z' })
    assert.equal(activeActivityEvent(job), null)
    job.status = 'COMPLETED'
    assert.equal(activeActivityEvent(job), null)
    assert.deepEqual(analysisActivityEvents({}), [])
})
