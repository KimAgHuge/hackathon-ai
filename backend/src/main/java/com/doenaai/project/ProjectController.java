package com.doenaai.project;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ProjectController {

    @GetMapping("/projects/demo")
    public ProjectResponse getProject() {
        return new ProjectResponse("demo", "doenaai", "main", "TIBERO", "READY");
    }

    @PostMapping("/projects/demo/analysis")
    public AnalysisJobResponse startAnalysis(@RequestBody(required = false) Map<String, Object> request) {
        return new AnalysisJobResponse(
                "job-demo-001",
                "PROCESSING",
                "소스코드와 DB 스키마 병렬 분석을 시작했습니다."
        );
    }

    @GetMapping("/analysis-jobs/demo")
    public AnalysisProgressResponse getProgress() {
        return new AnalysisProgressResponse(84, 100, 100, 68, "PROCESSING");
    }

    @GetMapping("/projects/demo/architecture")
    public ArchitectureResponse getArchitecture() {
        return new ArchitectureResponse(
                "a3b925c",
                "db-snapshot-015",
                List.of(
                        Map.of("type", "VUE_VIEW", "name", "ApplicationView.vue"),
                        Map.of("type", "REST_API", "name", "POST /api/applications"),
                        Map.of("type", "SERVICE", "name", "ApplicationService"),
                        Map.of("type", "MYBATIS", "name", "ApplicationMapper"),
                        Map.of("type", "TABLE", "name", "TB_BM_APLY_RCPT")
                )
        );
    }

    @GetMapping("/projects/demo/impact")
    public ImpactAnalysisResponse getImpactAnalysis() {
        return new ImpactAnalysisResponse(
                "HIGH",
                78,
                5,
                8,
                List.of("Vue 화면", "Spring API", "Service", "Mapper SQL", "Tibero 테이블"),
                List.of(
                        new EvidenceResponse(
                                1,
                                "ApplicationView.vue",
                                "신청 접수 화면에 전자서명 입력 및 검증 결과 표시가 필요합니다.",
                                "frontend/src/views/ApplicationView.vue"
                        ),
                        new EvidenceResponse(
                                2,
                                "ApplicationService.createApplication",
                                "전자서명 검증과 저장 절차가 기존 접수 트랜잭션에 추가됩니다.",
                                "backend/src/main/java/.../ApplicationService.java"
                        ),
                        new EvidenceResponse(
                                3,
                                "TB_BM_APLY_RCPT",
                                "검증 결과 컬럼 또는 별도 전자서명 이력 테이블이 필요합니다.",
                                "database:DOENAAI.TB_BM_APLY_RCPT"
                        )
                ),
                List.of(
                        "전자서명 원문은 대용량·민감 데이터를 고려하여 별도 이력 테이블에 저장합니다.",
                        "기존 신청번호를 FK로 연결하고 검증 상태 조회 인덱스를 검토합니다.",
                        "외부 인증 모듈 실패 시 재처리 및 보상 트랜잭션 정책을 정의합니다.",
                        "기존 접수 흐름과 신규 전자서명 흐름을 대상으로 통합 테스트를 수행합니다."
                )
        );
    }

    public record ProjectResponse(String id, String name, String branch, String databaseType, String status) {}
    public record AnalysisJobResponse(String jobId, String status, String message) {}
    public record AnalysisProgressResponse(
            int overallProgress,
            int sourceProgress,
            int databaseProgress,
            int relationProgress,
            String status
    ) {}
    public record ArchitectureResponse(
            String sourceCommit,
            String databaseSnapshot,
            List<Map<String, String>> nodes
    ) {}
    public record ImpactAnalysisResponse(
            String riskLevel,
            int riskScore,
            int expectedDelayMin,
            int expectedDelayMax,
            List<String> impactAreas,
            List<EvidenceResponse> evidences,
            List<String> recommendations
    ) {}
    public record EvidenceResponse(int order, String name, String description, String path) {}
}
