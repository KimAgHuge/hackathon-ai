# doenaai 아키텍처

```text
일반 PC
├─ frontend : Vue 3 + Vite
└─ backend  : Java 25 + Spring Boot 4.1

M1 Max Mac Studio
└─ ai-server
   ├─ 프로젝트 소스 분석
   ├─ DB 스키마 분석
   ├─ 코드·DB 관계 생성
   ├─ RAG
   └─ AI 추론

M4 Max MacBook
└─ training
   ├─ 승인 데이터셋 생성
   ├─ LoRA 학습
   ├─ 평가
   └─ 모델 배포

Supabase
├─ Auth
├─ PostgreSQL
├─ pgvector
├─ 분석 결과
├─ 피드백
└─ 모델 버전
```

## 요청 흐름

```text
Vue
→ Spring Boot
→ FastAPI
→ AI 분석
→ Spring Boot
→ Supabase 저장
→ Vue
```
