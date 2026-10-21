# 프론트엔드

Vue 3·Vite·Pinia·Vue Router·Tailwind CSS 4 기반 관리 화면입니다.
실제 로그인은 Supabase Auth, 업무 요청은 Spring Boot API를 사용합니다.

## 로컬 실행

```bash
cd frontend
npm ci
cp .env.example .env.local
npm run dev
```

기본 주소는 `http://localhost:5173`이며 `/api` 요청은 Vite가 `http://localhost:8080`으로 전달합니다.
환경변수를 바꾸면 개발 서버를 재시작합니다.

기본 설정은 모의 인증과 예시 자료입니다. 개발용 계정은 [.env.example](.env.example)에 있습니다.
실제 연동에는 `.env.local`을 다음과 같이 설정합니다.

```dotenv
VITE_AUTH_MODE=supabase
VITE_AUTH_PROFILE_SOURCE=backend
VITE_USE_MOCK=false
VITE_SUPABASE_URL=https://프로젝트ID.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=공개키
VITE_API_BASE_URL=/api
```

로그인 뒤 `GET /api/auth/me`로 사용자와 권한을 조회하고 업무 요청에 JWT를 붙입니다.
`VITE_AUTH_PROFILE_SOURCE=mock`은 실제 로그인에 개발용 관리자 권한을 붙이므로 화면 개발에만 사용합니다.
프론트 환경변수에는 publishable key 또는 anon key만 넣습니다. `service_role`, secret key, DB 비밀번호와 내부 서버 키는 넣지 않습니다.

## 화면 범위

- 조직 선택, 프로젝트 등록·조회·수정·등록 해제·복구와 Git·DB 연결 설정
- 요구사항 등록·버전 관리·후속 답변과 분석 시작·취소·단계 조회
- 분석 이력·결과·DB 검토 범위, 검토 승인·반려·수정과 학습 사례 확인·전송
- AI 모델 목록, 모델 응답 검증·전환·직전 모델 복구, 모델·학습 실행 이력
- 모델별 학습 시작·취소, 완료 학습으로 새 모델 생성·취소·등록·검증·적용

AI 모델 관리의 `modelApi.js`는 모의 자료로 실패를 숨기지 않고 실제 백엔드를 호출합니다.
모델 작업은 연결된 스튜디오의 AI 분석/추론 서버와 맥북 학습 서버에서 실행됩니다.
긴 학습·변환·등록 요청의 접수 응답은 완료가 아니며 화면이 상태를 주기적으로 조회합니다.

아키텍처·영향도 전용 화면은 가상 예시입니다. 문서·설정 경로는 대시보드로 이동합니다.
`VITE_USE_MOCK=false`는 대응 API가 없는 예시 화면까지 실제 기능으로 바꾸지 않습니다.

## 주요 코드

| 위치 | 역할 |
| --- | --- |
| `src/api/` | 백엔드 요청과 모의 자료 전환 |
| `src/router/` | 로그인·화면 권한 가드 |
| `src/stores/` | 인증·테마와 화면 상태 |
| `src/components/base/` | 공통 입력·표·모달 |
| `src/components/models/` | 모델 작업과 실행 상태 화면 |
| `src/views/` | 각 관리 화면 |
| `tests/` | API 계약과 화면 동작 검사 |

## 검사

```bash
node --test tests/*.test.js
npm run lint
npm run build
```

검사에서 통신을 대체한 경우 실제 모델 생성·품질·서버 연결까지 확인한 것은 아닙니다.
전체 환경 연결과 새 모델의 업무 품질은 실제 서버에서 별도로 확인합니다.

전체 흐름은 [시스템 아키텍처](../docs/02_시스템_아키텍처.md),
모델 작업 순서는 [모델 관리 안내](../docs/18_AI_모델_관리_사용_안내.md),
DB 준비는 [Supabase 안내](../supabase/README.md)를 참고합니다.
