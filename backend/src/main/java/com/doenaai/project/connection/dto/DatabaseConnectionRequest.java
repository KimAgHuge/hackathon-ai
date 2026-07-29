package com.doenaai.project.connection.dto;

import com.doenaai.project.connection.entity.DatabaseType;
import jakarta.validation.constraints.*;

public record DatabaseConnectionRequest(

        @NotNull
        DatabaseType dbType,

        @NotBlank
        @Size(max = 255)
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9.-]*$",
                message = "호스트 형식이 올바르지 않습니다."
        )
        String host,

        @NotNull
        @Min(1)
        @Max(65535)
        Integer port,

        @NotBlank
        @Size(max = 200)
        @Pattern(
                regexp = "^[A-Za-z0-9_$#.-]+$",
                message = "데이터베이스명 형식이 올바르지 않습니다."
        )
        String databaseName,

        @NotBlank
        @Size(max = 200)
        @Pattern(
                regexp = "^[A-Za-z0-9_$#.-]+$",
                message = "스키마명 형식이 올바르지 않습니다."
        )
        String schemaName,

        @NotBlank
        @Size(max = 200)
        String username,

        @NotBlank
        @Size(max = 1000)
        String password

) {
}
