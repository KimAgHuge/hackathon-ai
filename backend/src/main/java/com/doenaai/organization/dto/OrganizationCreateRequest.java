package com.doenaai.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record OrganizationCreateRequest(

        @NotBlank
        @Size(max = 200)
        String name,

        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "slug는 소문자 영문, 숫자와 단일 하이픈만 사용할 수 있습니다."
        )
        String slug,

        String description

) {
}
