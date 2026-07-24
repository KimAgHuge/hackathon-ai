package com.doenaai.project;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    @PreAuthorize(
            "@permissionChecker.has(authentication, 'PROJECT_READ')"
    )
    @GetMapping
    public String getProjects() {
        return "프로젝트 조회 권한 확인 성공";
    }
}