# doenaai

프로젝트 소스코드와 DB 스키마를 함께 분석해 변경 영향도와 위험도를 제공하는 모노레포입니다.

## 모듈

- `frontend`: Vue 3 + Vite + Vue Router + Pinia + Axios + Supabase
- `backend`: Java 25 + Spring Boot 4.1
- `ai-server`: Python FastAPI
- `training`: MLX-LM / LoRA 학습 영역
- `shared`: 공통 JSON Schema
- `supabase`: DB 마이그레이션
- `docs`: 아키텍처 및 버전 문서

현재 프론트엔드는 화면 검토를 위해 하드코딩 데이터를 기본값으로 사용합니다.
`VITE_USE_MOCK=false`로 변경하면 Spring Boot API 호출 구조로 전환됩니다.

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
gradle bootRun
```

## AI 서버 실행

```bash
cd ai-server
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```
