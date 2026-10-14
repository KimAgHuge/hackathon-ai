import assert from 'node:assert/strict'
import test from 'node:test'
import { buildDashboardSummary } from '../src/lib/dashboardSummary.js'

test('builds dashboard counts and recent analyses from live records', () => {
    const dashboard = buildDashboardSummary({
        projects: [{ id: 'project-1' }, { id: 'project-2' }],
        requirements: [{ priority: 'URGENT' }, { priority: 'HIGH' }, { priority: 'LOW' }],
        history: {
            total: 12,
            items: [{
                jobId: '12345678-aaaa', projectId: 'project-1', projectName: '예시 프로젝트',
                requirementTitle: '컬럼 추가', status: 'COMPLETED', riskLevel: 'HIGH',
            }],
        },
    })

    assert.deepEqual(dashboard.metrics.map((item) => item.value), [2, 3, 12, 2])
    assert.equal(dashboard.recentAnalyses[0].id, '12345678')
    assert.equal(dashboard.recentAnalyses[0].jobId, '12345678-aaaa')
    assert.equal(dashboard.recentAnalyses[0].risk, 'HIGH')
    assert.equal(dashboard.recentAnalyses[0].score, null)
    assert.deepEqual(dashboard.warnings, [])
})

test('does not mistake failed or incomplete retrieval for zero records', () => {
    const dashboard = buildDashboardSummary({
        projects: [],
        requirements: null,
        history: null,
        failures: ['requirements', 'analyses'],
    })

    assert.deepEqual(dashboard.metrics.map((item) => item.value), [0, '—', '—', '—'])
    assert.equal(dashboard.analysesAvailable, false)
    assert.equal(dashboard.recentAnalyses.length, 0)
    assert.equal(dashboard.systems[0].status, 'ONLINE')
    assert.equal(dashboard.systems[1].status, 'ERROR')
    assert.equal(dashboard.warnings.length, 2)
})
