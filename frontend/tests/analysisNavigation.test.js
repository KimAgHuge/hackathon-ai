import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldConfirmAnalysisExit, warnOnAnalysisUnload } from '../src/lib/analysisNavigation.js'

test('warns when status is not loaded or analysis is active', () => {
    assert.equal(shouldConfirmAnalysisExit(null), true)
    assert.equal(shouldConfirmAnalysisExit('PENDING'), true)
    assert.equal(shouldConfirmAnalysisExit('RUNNING'), true)
})

test('does not warn for terminal jobs', () => {
    for (const status of ['COMPLETED', 'FAILED', 'CANCELLED']) {
        assert.equal(shouldConfirmAnalysisExit(status), false)
    }
})

test('requests the native unload warning only while analysis may be active', () => {
    const active = {
        returnValue: false,
        prevented: false,
        preventDefault() {
            this.prevented = true
        },
    }
    warnOnAnalysisUnload(active, 'RUNNING')
    assert.equal(active.prevented, true)
    assert.equal(active.returnValue, true)

    const completed = {
        returnValue: false,
        prevented: false,
        preventDefault() {
            this.prevented = true
        },
    }
    warnOnAnalysisUnload(completed, 'COMPLETED')
    assert.equal(completed.prevented, false)
    assert.equal(completed.returnValue, false)
})
