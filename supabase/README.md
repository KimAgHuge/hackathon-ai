# Supabase

Supabase Auth와 PostgreSQL을 사용해 사용자·권한·조직·프로젝트·분석·학습 이력을 관리합니다.
브라우저는 공개키로 인증하고 업무 데이터는 백엔드 API를 통해 접근합니다.

## 마이그레이션

`migrations/`의 SQL을 번호 순서대로 한 번씩 적용합니다.
이미 적용한 파일은 수정하거나 다시 실행하지 않으며, 변경은 다음 번호의 새 파일로 추가합니다.
적용한 번호는 운영 환경별로 관리하고 비밀 연결 정보는 Git에 넣지 않습니다.

| 파일 | 주요 내용 |
| --- | --- |
| `001_auth_rbac.sql` | 사용자 프로필·역할·권한 |
| `002_projects_analysis.sql` | 프로젝트·분석 작업·결과 |
| `003_ai_model_training.sql` | AI 모델·학습 데이터·학습 작업 |
| `004_project_connection.sql` | 프로젝트 DB 연결 |
| `005_project_repository_connection.sql` | 프로젝트 Git 연결 |
| `006_git_host_policy.sql` | 허용 Git 호스트 정책 |
| `007_organization_requirement_estimate.sql` | 조직·프로젝트 역할·요구사항 버전·견적 |
| `008_project_soft_delete.sql` | 프로젝트 등록 해제·복구 |
| `009_requirement_follow_up.sql` | 요구사항 후속 답변·이전 버전 연결 |
| `010_analysis_result_review.sql` | 분석 결과 검토 상태·담당자·시각 |
| `011_analysis_worker_routing.sql` | 분석 작업 대상 워커 지정 |
| `012_model_management_operations.sql` | 모델·학습 실행 요청과 변경 이력 |

`007`은 기존 프로젝트를 보존하기 위해 `projects.organization_id`의 빈 값을 허용합니다.
기존 프로젝트는 조직을 생성한 뒤 해당 조직에 배정해야 합니다.
빈 값을 금지하는 DB 제약은 현재 마이그레이션에 없으며 별도 변경이 필요합니다.
`011`을 적용한 환경의 워커 지정 방법은 [워커 운영 안내](../docs/16_분석_워커_지정_운영.md)를 따릅니다.

## 접근 권한

- `auth.users`는 Supabase가 관리하므로 직접 만들지 않습니다.
- 프론트에는 publishable key 또는 anon key만 사용합니다. `service_role`과 secret key는 서버에서만 관리합니다.
- 업무 테이블은 RLS와 직접 접근 권한 회수 정책을 유지합니다. 브라우저가 직접 쓸 수 있도록 권한을 넓히지 않습니다.
- 백엔드는 전역 권한과 조직·프로젝트 역할을 함께 검사합니다.
- 모델 실행 이벤트는 추가만 허용합니다. 과거 이력을 수정·삭제하는 대신 새 요청과 이력을 남깁니다.

서버 연결 준비는 [프로젝트 안내](../README.md),
전체 구성은 [시스템 아키텍처](../docs/02_시스템_아키텍처.md)를 참고합니다.
