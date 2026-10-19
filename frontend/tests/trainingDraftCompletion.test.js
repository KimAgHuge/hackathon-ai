import test from 'node:test'
import assert from 'node:assert/strict'
import {
    completeMissingTrainingFields,
    readTrainingDraft,
    remainingTrainingReviewNotes,
    trainingDraftIssues,
    updateTrainingDraftField,
    unmatchedTrainingEvidence,
    trainingValidationMessage,
    trainingReviewWarnings,
} from '../src/lib/trainingDraftCompletion.js'

const validDraft = () => ({
    caseId: 'case-1',
    humanApproved: true,
    sanitized: true,
    snapshot: { sourceRevision: 'a'.repeat(40), requirementVersionId: 'version-1', databaseCapturedAt: '2026-10-16T01:02:03Z' },
    approval: { approvedAt: '2026-10-16T02:02:03+09:00', note: '근거 확인' },
    answers: { general: { databaseRelevance: 'RELATED', databaseRelevanceReason: '저장 구조 변경 필요' }, database: { status: 'CHANGE_REQUIRED' } },
})

test('저장된 값은 빈 항목에만 반영하고 사용자가 편집한 정답은 보존한다', () => {
    const edited = validDraft()
    edited.snapshot.databaseCapturedAt = null
    edited.answers.general.databaseRelevanceReason = '사람이 수정한 판단 이유'
    const result = completeMissingTrainingFields(JSON.stringify(edited), validDraft())
    assert.deepEqual(result.filledPaths, ['snapshot.databaseCapturedAt'])
    assert.equal(result.caseDraft.answers.general.databaseRelevanceReason, '사람이 수정한 판단 이유')
    assert.equal(result.caseDraft.humanApproved, false)
    assert.equal(result.caseDraft.sanitized, false)
    assert.equal(edited.snapshot.databaseCapturedAt, null)
})

test('복원할 값이 없으면 시각을 만들지 않고 기존 확인 표시도 유지한다', () => {
    const draft = validDraft()
    draft.snapshot.databaseCapturedAt = null
    const result = completeMissingTrainingFields(JSON.stringify(draft), draft)
    assert.deepEqual(result.filledPaths, [])
    assert.equal(result.caseDraft.snapshot.databaseCapturedAt, null)
    assert.equal(result.caseDraft.humanApproved, true)
    assert.ok(trainingDraftIssues(JSON.stringify(draft)).some((item) => item.startsWith('DB 수집 시각:')))
})

test('다른 작업의 초안과 JSON 객체가 아닌 입력을 거부한다', () => {
    assert.throws(() => completeMissingTrainingFields(JSON.stringify(validDraft()), { ...validDraft(), caseId: 'other' }))
    for (const text of ['{', '[]', 'null', 'true']) assert.throws(() => readTrainingDraft(text))
})

test('폼 수정은 허용된 값만 바꾸며 정답이나 DB 단계를 임의로 제거하지 않는다', () => {
    const result = updateTrainingDraftField(JSON.stringify(validDraft()), 'answers.general.databaseRelevance', 'UNRELATED')
    assert.equal(result.answers.database.status, 'CHANGE_REQUIRED')
    assert.ok(trainingDraftIssues(JSON.stringify(result)).some((item) => item.includes('함께 있습니다')))
    assert.throws(() => updateTrainingDraftField(JSON.stringify(validDraft()), 'approval.reviewerId', 'changed'))
})

test('시각 형식과 판단 이유, DB 단계 누락을 전송 전에 안내한다', () => {
    assert.deepEqual(trainingDraftIssues(JSON.stringify(validDraft())), [])
    for (const time of [null, '2026-10-16', '2026-10-16T01:02:03', '2026-02-30T00:00:00Z']) {
        const draft = validDraft()
        draft.snapshot.databaseCapturedAt = time
        assert.ok(trainingDraftIssues(JSON.stringify(draft)).some((item) => item.startsWith('DB 수집 시각:')))
    }
    const draft = validDraft()
    draft.answers.general.databaseRelevance = 'UNCERTAIN'
    draft.answers.general.databaseRelevanceReason = ' '
    delete draft.answers.database
    assert.equal(trainingDraftIssues(JSON.stringify(draft)).length, 2)
})

test('값을 보완하면 오래된 누락 안내만 사라지고 사람 검토 안내는 남는다', () => {
    const notes = [
        'snapshot.databaseCapturedAt: 필요',
        'answers.general.databaseRelevance: 필요',
        'answers.general.databaseRelevanceReason: 필요',
        'answers: 검토 필요',
    ]
    assert.deepEqual(remainingTrainingReviewNotes(notes, JSON.stringify(validDraft())), ['answers: 검토 필요'])
})

test('파일 이름만 같은 근거와 정확한 줄까지 확인한 보완 근거를 구분한다', () => {
    const draft = validDraft()
    draft.request = { sourceSummary: { components: [{ path: 'schema.sql', line: 1 }] } }
    draft.answers.general.evidences = [
        { file: 'schema.sql', line: 483 },
        { file: 'schema.sql', line: 504 },
    ]
    assert.equal(unmatchedTrainingEvidence(JSON.stringify(draft)).length, 2)
    draft.request.sourceSummary.reviewedEvidence = [{ file: 'schema.sql', line: 483, excerpt: '확인한 조각' }]
    assert.deepEqual(unmatchedTrainingEvidence(JSON.stringify(draft)), [{ file: 'schema.sql', line: 504 }])
})

test('알려진 오류 코드는 안내하되 원격 오류 원문은 표시하지 않는다', () => {
    const message = trainingValidationMessage({ response: { status: 422, data: { code: 'GROUNDING_MISMATCH', message: 'private-customer-value' } } })
    assert.match(message, /GROUNDING_MISMATCH/)
    assert.doesNotMatch(message, /private-customer-value/)
    assert.doesNotMatch(trainingValidationMessage({ response: { status: 422, data: { message: 'private-customer-value' } } }), /private-customer-value/)
})

test('파일 단위 인용은 실제 입력 파일과 비교하고 명시한 줄 번호는 정확하게 검사한다', () => {
    const draft = validDraft()
    draft.request = { sourceSummary: { components: [{ path: 'known.vue', line: 7 }] }, relations: [] }
    draft.request.sourceSummary.changeRequirement = { title: '화면 변경', userClaim: { file: 'not-collected.vue', line: 3 } }
    draft.answers.general.evidences = [
        { file: 'known.vue' },
        { file: 'known.vue', line: null },
        { file: 'known.vue', line: 8 },
        { file: 'not-collected.vue' },
        { file: 'known.vue', line: 0 },
    ]
    assert.deepEqual(unmatchedTrainingEvidence(JSON.stringify(draft)), [
        { file: 'known.vue', line: 8 },
        { file: 'not-collected.vue', line: null },
        { file: 'known.vue', line: '?' },
    ])
})

test('DB 범위 확인 안내는 정해진 코드만 표시하고 검증 실패로 바꾸지 않는다', () => {
    assert.equal(trainingReviewWarnings({ valid: true, reviewWarnings: ['DATABASE_SCOPE_REVIEW'] }).length, 1)
    assert.deepEqual(trainingReviewWarnings({ valid: true, reviewWarnings: ['private-customer-value'] }), [])
    assert.deepEqual(trainingReviewWarnings({ valid: true }), [])
})
