# doenaai

고객 요구사항을 프로젝트 소스와 DB 구조에 연결해 영향 범위·위험·예상 작업량·
기간을 분석하고, 여러 조직의 PM/PL이 근거 있는 견적을 작성하도록 지원하는
플랫폼 모노레포입니다.

## 모듈

- `frontend`: Vue 3 + Vite + Vue Router + Pinia + Axios + Supabase
- `backend`: Java 25 + Spring Boot 4.1
- `ai-server`: Python FastAPI
- `training`: MLX-LM / LoRA 학습 영역
- `shared`: 공통 JSON Schema
- `supabase`: DB 마이그레이션
- `docs`: 프로젝트 개요, 시스템 아키텍처, 현재 진행상황, 다른 PC 작업 인수인계

현재 프론트엔드는 화면 검토를 위해 하드코딩 데이터를 기본값으로 사용합니다.
`VITE_USE_MOCK=false`로 변경하면 Spring Boot API 호출 구조로 전환됩니다.

## 공식 문서

처음 합류한 팀원은 [팀원 온보딩 가이드](docs/07_팀원_온보딩_프로젝트_설명.md)를 먼저 읽어주세요.
프로젝트 목적, 현재 서버 구성도, 분석 순서도, 계정/DB 구분과 공동개발 실행 규칙을 정리했습니다.
최신 Worker 실행 경로는 `06`·`07` 문서를, AI 분석 분기와 MacBook 학습 계획은 `08` 문서를 기준으로 확인합니다.

1. `docs/01_프로젝트_개요.md`
   - 프로젝트 목적, 기술 스택, 저장소와 기능 구조
2. `docs/02_시스템_아키텍처.md`
   - Frontend, Backend, AI 서버, 학습 서버, Supabase 구성과 흐름
3. `docs/03_현재_진행상황.md`
   - 완료/미완료 기능, 검증 결과, 알려진 문제, 다음 작업
4. `docs/04_다른_PC_Backend_작업_인수인계.md`
   - 다른 PC의 Backend 담당 범위, 실행 준비, 구현 순서와 완료 기준
5. `docs/05_2026-09-23_MAC_STUDIO_작업_인수인계.md`
   - 9월 23일 기준 Mac 작업 이력
6. `docs/06_2026-10-12_분석_Worker_구현_인수인계.md`
   - 최신 Worker, 소스/DB 수집, AI 호출, 결과 저장과 검증 기록
7. `docs/07_팀원_온보딩_프로젝트_설명.md`
   - 팀원 공유용 목적·구성·흐름·구현 범위·역할 분담 안내
8. `docs/08_2026-10-13_AI_분석과_맥북_학습_인수인계.md`
   - Mac Studio 추론과 M4 Max MacBook 학습의 역할, DB 분석 분기, 검증·미완료 사항

## 프론트 실행

```bash
cd frontend
npm install
npm run dev
```

## 백엔드 실행

JDK 25가 필요합니다.

```bash
cd backend
./gradlew bootRun
```

Windows PowerShell에서는 `./gradlew` 대신 `.\gradlew.bat`을 사용합니다.

## AI 서버 실행

```bash
cd ai-server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```
