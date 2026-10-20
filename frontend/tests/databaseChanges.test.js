import test from 'node:test'
import assert from 'node:assert/strict'
import { databaseChangeLabels, databaseChangeNecessityLabel } from '../src/lib/databaseChanges.js'

test('인덱스와 컬럼 추가를 서로 다른 변경으로 표시한다', () => {
    assert.equal(databaseChangeLabels.ADD_INDEX, '인덱스 추가')
    assert.equal(databaseChangeLabels.ADD_COLUMN, '컬럼 추가')
    assert.equal(databaseChangeLabels.OTHER, '기타 변경')
})

test('필수 변경과 권장 개선을 구분하고 구형 결과를 필수로 바꾸지 않는다', () => {
    assert.equal(databaseChangeNecessityLabel('REQUIRED'), '필수 변경')
    assert.equal(databaseChangeNecessityLabel('RECOMMENDED'), '권장 개선')
    for (const value of [undefined, null, '', 'INVALID']) assert.equal(databaseChangeNecessityLabel(value), '필수·권장 구분 없음')
})
