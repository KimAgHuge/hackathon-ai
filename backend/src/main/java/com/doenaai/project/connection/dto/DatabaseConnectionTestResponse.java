package com.doenaai.project.connection.dto;

public record DatabaseConnectionTestResponse(
        boolean success,
        String message,
        String databaseProduct,
        String databaseVersion
) {

    public static DatabaseConnectionTestResponse success(
            String databaseProduct,
            String databaseVersion
    ) {
        return new DatabaseConnectionTestResponse(
                true,
                "데이터베이스 연결에 성공했습니다.",
                databaseProduct,
                databaseVersion
        );
    }

    public static DatabaseConnectionTestResponse failure(
            String message
    ) {
        return new DatabaseConnectionTestResponse(
                false,
                message,
                null,
                null
        );
    }
}
