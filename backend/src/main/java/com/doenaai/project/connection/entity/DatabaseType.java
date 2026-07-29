package com.doenaai.project.connection.entity;

public enum DatabaseType {
    ORACLE(
            "oracle.jdbc.OracleDriver"
    ),
    POSTGRESQL(
            "org.postgresql.Driver"
    ),
    TIBERO(null),
    MYSQL(null),
    MARIADB(null),
    MSSQL(null),
    OTHER(null);

    private final String driverClassName;

    DatabaseType(
            String driverClassName
    ) {
        this.driverClassName = driverClassName;
    }

    public boolean isSupported() {
        return driverClassName != null;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public String jdbcUrl(
            String host,
            int port,
            String databaseName
    ) {
        return switch (this) {
            case ORACLE ->
                    "jdbc:oracle:thin:@//%s:%d/%s"
                            .formatted(host, port, databaseName);
            case POSTGRESQL ->
                    "jdbc:postgresql://%s:%d/%s"
                            .formatted(host, port, databaseName);
            default -> throw new IllegalStateException(
                    "지원하지 않는 데이터베이스 종류입니다."
            );
        };
    }
}
