# Frontend Style Guide

## 적용 기술

- Vue 3
- Vite
- Tailwind CSS 4
- Vue Router
- Pinia
- Axios
- Supabase JavaScript SDK
- ESLint Flat Config
- Prettier

## Tailwind CSS

`../vite.config.js`에서 `@tailwindcss/vite` 플러그인을 사용합니다.

전역 CSS 진입점은 `../src/assets/styles/main.css`이며, 첫 줄에 다음이 들어 있습니다.

```css
@import "tailwindcss";
```

기존 대시보드 디자인 CSS도 같은 파일에 유지되어 있어 이전 화면 디자인이 그대로 보입니다.
신규 화면에서는 Tailwind 유틸리티 클래스를 바로 사용할 수 있습니다.

## ESLint

```bash
npm run lint
npm run lint:fix
```

주요 규칙:

- `no-unused-vars`: warn
- `vue/no-unused-vars`: warn
- `vue/multi-word-component-names`: off
- `no-prototype-builtins`: error

## Prettier

```bash
npm run format
npm run format:check
```

포맷:

- 세미콜론 없음
- 들여쓰기 4칸
- 작은따옴표
- 한 줄 최대 160자
