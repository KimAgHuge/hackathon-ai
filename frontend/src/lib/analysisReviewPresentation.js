// 검토 결정과 데이터 전달 상태를 구분하며 전달 완료를 모델 학습 완료로 표시하지 않는다.
export const analysisReviewLabel = (status) => ({ APPROVED: '승인 완료', REJECTED: '반려', PENDING: '검토 대기' })[status] || '검토 대기'

export const trainingTransferPresentation = (status) => {
    const states = {
        WAITING_FOR_RECEIVER: {
            label: '파일 게시 완료 · AI 학습 서버 연결 대기',
            description: '공유 폴더에 파일을 올렸습니다. AI 학습 서버에 수신 알림을 다시 보내고 있습니다. 다시 전송할 필요는 없습니다.',
        },
        WAITING_FOR_SYNC: {
            label: 'AI 학습 서버 파일 동기화 대기',
            description: 'AI 학습 서버가 알림을 받았습니다. 공유 폴더의 파일 동기화와 수신 검증을 기다리고 있습니다.',
        },
        VERIFIED: {
            label: 'AI 학습 서버 수신·검증 완료',
            description: '학습에 사용할 데이터가 AI 학습 서버에 도착했습니다. 모델 학습·평가는 별도 단계이며, 현재 서비스의 모델은 바뀌지 않습니다.',
        },
        REJECTED: {
            label: '학습 데이터 검증 실패',
            description: 'AI 학습 서버가 데이터를 받았지만 검증을 통과하지 못했습니다. 오류 원인을 확인해야 하며 학습 자료로 사용하지 않습니다.',
        },
    }
    return Object.hasOwn(states, status) ? states[status] : { label: '전송 상태 확인 중', description: '아직 데이터 전달 완료가 확인되지 않았습니다.' }
}
