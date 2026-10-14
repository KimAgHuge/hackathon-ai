import assert from 'node:assert/strict'
import test from 'node:test'
import { calendarDays, parseDateKey, toDateKey } from '../src/lib/calendarDates.js'

test('accepts real local dates and rejects invalid calendar dates', () => {
    assert.equal(toDateKey(parseDateKey('2028-02-29')), '2028-02-29')
    assert.equal(parseDateKey('2027-02-29'), null)
    assert.equal(parseDateKey('2026-13-01'), null)
    assert.equal(parseDateKey('2026/10/14'), null)
})

test('calendar starts on Monday and includes adjacent month days', () => {
    const days = calendarDays(2026, 10)
    assert.equal(days.length % 7, 0)
    assert.equal(days[0].key, '2026-09-28')
    assert.equal(days.find((day) => day.key === '2026-10-14')?.currentMonth, true)
    assert.equal(days.at(-1).key, '2026-11-01')
})
