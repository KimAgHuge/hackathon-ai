import assert from 'node:assert/strict'
import test from 'node:test'
import { matchesCreatedDateRange, toLocalDateKey } from '../src/lib/changeRequestFilters.js'

test('matches an inclusive date range', () => {
    assert.equal(matchesCreatedDateRange('2026-10-14', '2026-10-14', '2026-10-14'), true)
    assert.equal(matchesCreatedDateRange('2026-10-13', '2026-10-14', ''), false)
    assert.equal(matchesCreatedDateRange('2026-10-15', '', '2026-10-14'), false)
    assert.equal(matchesCreatedDateRange('2026-10-14', '', ''), true)
})

test('converts timestamp to the same local date shown in the table', () => {
    const timestamp = '2026-10-14T10:30:00+09:00'
    const expectedDate = new Date(timestamp)
    const localDate = [expectedDate.getFullYear(), String(expectedDate.getMonth() + 1).padStart(2, '0'), String(expectedDate.getDate()).padStart(2, '0')].join(
        '-'
    )

    assert.equal(toLocalDateKey(timestamp), localDate)
    assert.equal(matchesCreatedDateRange(timestamp, localDate, localDate), true)
    assert.equal(matchesCreatedDateRange('invalid', localDate, ''), false)
})
