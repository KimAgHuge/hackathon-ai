# Supabase

- Auth
- PostgreSQL
- pgvector
- 프로젝트/분석/피드백 저장

프론트 브라우저에서는 publishable key만 사용합니다.
service role key는 절대 프론트 환경변수에 넣지 않습니다.

## Migration 적용 순서

`supabase/migrations`의 SQL은 번호 순서대로 한 번씩 적용합니다. 이미 적용된
migration 파일은 수정하거나 다시 실행하지 않습니다.

```text
001_auth_rbac.sql
002_projects_analysis.sql
003_ai_model_training.sql
004_project_connection.sql
005_project_repository_connection.sql
006_git_host_policy.sql
007_organization_requirement_estimate.sql
```

`007`은 다음 구조를 추가합니다.

- `organizations`, `organization_members`
- 프로젝트의 `organization_id`
- 프로젝트 역할 `PM`, `PL`, `CUSTOMER`, `DEVELOPER`
- `requirements`, `requirement_versions`
- 분석 작업의 `requirement_version_id`
- `estimates`, `estimate_items`, `estimate_reviews`
- 요구사항·견적 관련 전역 permission

기존 프로젝트에는 조직 정보가 없기 때문에 `projects.organization_id`는 전환
기간 동안 nullable입니다. `007` 적용 후에는 조직을 먼저 생성하고 기존 프로젝트를
해당 조직에 배정해야 합니다. Backend에서 신규 프로젝트의 조직 입력과 tenant
검증을 구현한 뒤 후속 migration에서 `organization_id`를 `NOT NULL`로 전환합니다.

신규 업무 테이블도 RLS를 활성화하고 `anon`, `authenticated`의 직접 권한을
회수합니다. 조직·프로젝트·고객 공개 범위는 Spring Boot에서 전역 permission과
프로젝트 역할을 함께 검증합니다.
