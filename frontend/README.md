# doenaai Frontend

Vue 3, Vite, Pinia, Vue Router, Tailwind CSS 4, Supabase Auth 기반의 관리자 프론트엔드입니다.

## 현재 구현 상태 — 2026-10-14 점검

프로젝트 등록·조직 생성/선택·Git/DB 연결과 현재 요구사항 분석은 실제 API 경로가 있습니다.
분석 진행 화면은 polling과 저장된 DB 판단·근거·수집 범위 경고를 표시합니다.
프로젝트 상세/수정, 이력, 아키텍처·영향도 전용 화면, 모델·문서·설정·견적 기능은 미완료입니다.
`VITE_USE_MOCK=false`를 설정해도 대응 Backend API가 없는 화면까지 동작하지는 않습니다.
프로젝트 목록은 실제 DTO 필드와 화면 표시 필드가 달라 후속 수정이 필요합니다.

이번 검사: Node 테스트 14개·production build 통과, lint는 포맷 오류 113개·경고 1개로 실패했습니다.
실제 로그인·Backend·Supabase를 연결한 브라우저 E2E는 이번 점검에서 수행하지 않았습니다.
화면별 현황·계약 불일치·다음 작업은 [Frontend 구현 점검](../docs/13_2026-10-14_Frontend_구현_점검.md)을 참고합니다.

## 포함된 기능

- 로그인 화면 및 세션 복원
- Mock 인증 / Supabase Auth 전환
- Spring Boot `/api/auth/me` 연동 옵션
- JWT Bearer Token Axios 자동 첨부
- Router 인증 가드와 권한 가드
- 권한별 사이드바 메뉴 및 버튼 표시
- 라이트/다크 모드
- 재사용 가능한 Base UI 컴포넌트
- 데이터 개수에 따라 자동 증감하는 공통 BaseTable
- 컬럼 설정, 동적 셀 슬롯, 정렬, 선택, 행 클릭, 고정 헤더, 페이지네이션

## 실행

```bash
npm install
npm run dev
```

기본 `.env.example`은 Mock 인증입니다. 복사해 `.env.local`을 생성합니다.

```text
이메일: admin@doenaai.local
비밀번호: admin1234
```

## 실제 Supabase 로그인 사용

`.env.local`을 다음과 같이 변경합니다.

```env
VITE_AUTH_MODE=supabase
VITE_AUTH_PROFILE_SOURCE=backend
VITE_USE_MOCK=false

VITE_SUPABASE_URL=https://프로젝트ID.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=publishable-key
VITE_API_BASE_URL=/api
```

`VITE_AUTH_PROFILE_SOURCE=backend`일 때 로그인 성공 후 Spring Boot의 다음 API를 호출합니다.

```text
GET /api/auth/me
Authorization: Bearer <Supabase Access Token>
```

백엔드가 아직 없으면 다음처럼 사용자 프로필만 Mock으로 사용할 수 있습니다.

```env
VITE_AUTH_MODE=supabase
VITE_AUTH_PROFILE_SOURCE=mock
```

이 경우 로그인은 실제 Supabase Auth가 처리하고, 화면 권한은 개발용 ADMIN 권한을 사용합니다.

## Supabase 테이블

`supabase/migrations/001_auth_rbac.sql`을 Supabase SQL Editor에서 실행하면 다음 테이블이 생성됩니다.

- profiles
- roles
- permissions
- user_roles
- role_permissions

`auth.users`는 Supabase가 관리하므로 직접 만들지 않습니다.

## 주요 구조

```text
src/
├─ api/
│  ├─ authApi.js
│  ├─ httpClient.js
│  └─ appApi.js
├─ components/
│  ├─ base/
│  │  ├─ BaseButton.vue
│  │  ├─ BaseInput.vue
│  │  ├─ BaseSelect.vue
│  │  ├─ BaseTextarea.vue
│  │  ├─ BaseCard.vue
│  │  ├─ BaseTable.vue
│  │  └─ BasePagination.vue
│  ├─ common/
│  └─ layout/
├─ composables/
│  └─ useClientTable.js
├─ layouts/
│  ├─ AdminLayout.vue
│  └─ AuthLayout.vue
├─ stores/
│  ├─ authStore.js
│  └─ themeStore.js
└─ views/
   ├─ LoginView.vue
   └─ ...
```

## BaseTable 사용 예시

```vue
<BaseTable
    :columns="columns"
    :rows="rows"
    :loading="loading"
    clickable
    @row-click="openDetail"
>
    <template #cell-status="{ value }">
        <StatusBadge :status="value" />
    </template>

    <template #footer>
        <BasePagination
            v-model:page="page"
            :page-size="pageSize"
            :total-count="totalCount"
        />
    </template>
</BaseTable>
```

행은 `rows` 배열의 길이에 따라 자동으로 늘어나거나 줄어듭니다. 컬럼은 화면별 `columns` 배열로 지정하고, 특수 셀은 `cell-컬럼키` 슬롯으로 변경합니다.

## 보안 주의

프론트 환경변수에는 Supabase publishable key 또는 anon key만 넣습니다. `service_role`, secret key, 데이터베이스 비밀번호는 Spring Boot 서버에서만 관리해야 합니다.

## 프로젝트 문서

Frontend 기술, 디렉터리, 공통 컴포넌트와 작성 규칙은 다음 공식 문서에
통합되어 있습니다.

```text
../docs/01_프로젝트_개요.md
```

전체 배치와 통신 흐름:

```text
../docs/02_시스템_아키텍처.md
```

실제 구현과 검증 상태:

```text
../docs/03_현재_진행상황.md
```
