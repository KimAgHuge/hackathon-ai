const reasonLabels = {
    ANALYSIS_NOT_COMPLETED: '분석이 완료되지 않았습니다.',
    RESULT_MISSING: '저장된 분석 결과가 없습니다.',
    SOURCE_EVIDENCE_MISSING: '저장된 소스 근거가 없습니다.',
    DATABASE_EVIDENCE_MISSING: '저장된 DB 근거가 없습니다.',
    SOURCE_COMPONENTS_MISSING: '저장된 소스 목록을 확인할 수 없습니다.',
    DATABASE_TABLES_MISSING: '저장된 DB 테이블 목록을 확인할 수 없습니다.',
    SOURCE_COMMIT_MISSING: '분석 작업에 Git 커밋 정보가 없습니다.',
    SAVED_COMMIT_MISSING: '저장된 소스 근거에 Git 커밋 정보가 없습니다.',
    SOURCE_COMMIT_MISMATCH: '분석 작업과 저장된 소스 근거의 Git 커밋이 다릅니다.',
    SOURCE_COLLECTOR_OUTDATED: '소스 수집 방식이 업데이트되었습니다. 최신 Git·DB 확인으로 새 연결 근거를 수집해 주세요.',
    SOURCE_PROMPT_COVERAGE_MISSING: 'AI에 전달한 소스 범위 정보가 없습니다.',
    SOURCE_PROMPT_TRUNCATED: 'AI에 전달한 소스 정보가 입력 크기 제한으로 잘렸습니다.',
    SOURCE_SCAN_STATUS_MISSING: '소스 파일 수집 완료 여부를 확인할 수 없습니다.',
    SOURCE_SCAN_TRUNCATED: '소스 파일 수집이 제한에 걸려 일부만 저장됐습니다.',
    DATABASE_COVERAGE_MISSING: 'DB 스키마 수집 범위 정보가 없습니다.',
    DATABASE_TRUNCATED: 'DB 스키마 수집이 제한에 걸려 일부만 저장됐습니다.',
    DATABASE_WARNINGS_MISSING: 'DB 스키마 수집 경고 여부를 확인할 수 없습니다.',
    DATABASE_WARNINGS_PRESENT: 'DB 스키마 수집 중 경고가 발생했습니다.',
    RELATIONS_MISSING: '소스와 DB의 관계 정보가 저장되지 않았습니다.',
    EVIDENCE_FORMAT_UNSUPPORTED: '저장 근거의 형식을 확인할 수 없습니다.',
}

export const evidenceUnavailableReasonLabel = (code) => reasonLabels[code] || '저장 근거를 재사용할 수 없습니다. 관리자에게 확인해 주세요.'

const warningLabels = {
    SOURCE_COLLECTOR_OUTDATED: '이전 수집 방식으로 저장한 자료입니다. 현재 방식에서 추가한 연결 정보는 포함되지 않을 수 있습니다.',
    SOURCE_PROMPT_COVERAGE_MISSING: '당시 AI에 전달한 소스 자료의 범위를 확인할 수 없습니다.',
    SOURCE_PROMPT_TRUNCATED: '당시 AI 입력 크기 제한으로 소스 자료 일부가 생략됐습니다.',
    SOURCE_SCAN_STATUS_MISSING: '당시 소스 파일 수집을 어디까지 완료했는지 확인할 수 없습니다.',
    SOURCE_SCAN_TRUNCATED: '소스 파일 일부만 수집한 자료입니다.',
    DATABASE_COVERAGE_MISSING: '당시 DB 구조의 수집 범위를 확인할 수 없습니다.',
    DATABASE_TRUNCATED: 'DB 구조 일부만 수집한 자료입니다.',
    DATABASE_WARNINGS_MISSING: '당시 DB 구조 수집 경고 여부를 확인할 수 없습니다.',
    DATABASE_WARNINGS_PRESENT: 'DB 구조 수집 중 경고가 있었던 자료입니다. 일부 구조가 빠졌을 수 있습니다.',
}

export const evidenceWarningLabel = (code) => warningLabels[code] || '일부 수집 범위나 시점 정보가 부족합니다. 저장된 자료 안에서만 분석합니다.'
