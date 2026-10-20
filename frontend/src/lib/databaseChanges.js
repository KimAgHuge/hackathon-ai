export const databaseChangeLabels = {
    CREATE_TABLE: '테이블 생성',
    ADD_COLUMN: '컬럼 추가',
    ALTER_COLUMN: '컬럼 변경',
    DROP_COLUMN: '컬럼 삭제',
    ADD_CONSTRAINT: '제약조건 추가',
    ALTER_CONSTRAINT: '제약조건 변경',
    ADD_INDEX: '인덱스 추가',
    DATA_MIGRATION: '데이터 이관',
    OTHER: '기타 변경',
}

// 구형 결과에 없는 필수 여부를 임의로 확정하지 않는다.
export const databaseChangeNecessityLabel = (value) => (value === 'REQUIRED' ? '필수 변경' : value === 'RECOMMENDED' ? '권장 개선' : '필수·권장 구분 없음')
