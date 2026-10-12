package com.doenaai.project.connection.service;

import com.doenaai.project.connection.dto.DatabaseConnectionRequest;
import com.doenaai.project.connection.dto.DatabaseConnectionTestResponse;
import com.doenaai.project.connection.dto.ProjectDatabaseSourceResponse;
import com.doenaai.project.connection.entity.DatabaseType;
import com.doenaai.project.connection.entity.ProjectDatabaseSource;
import com.doenaai.project.connection.repository.ProjectDatabaseSourceRepository;
import com.doenaai.project.connection.security.CredentialCipher;
import com.doenaai.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseConnectionService {

    private static final int LOGIN_TIMEOUT_SECONDS = 5;
    private static final int NETWORK_TIMEOUT_MILLISECONDS = 5000;

    private final ProjectService projectService;
    private final ProjectDatabaseSourceRepository databaseSourceRepository;
    private final CredentialCipher credentialCipher;

    @Transactional(readOnly = true)
    public DatabaseConnectionTestResponse testConnection(
            UUID projectId,
            DatabaseConnectionRequest request,
            UUID userId
    ) {
        projectService.assertCanManage(
                projectId,
                userId
        );

        return executeConnectionTest(request);
    }

    @Transactional
    public ProjectDatabaseSourceResponse saveConnection(
            UUID projectId,
            DatabaseConnectionRequest request,
            UUID userId
    ) {
        projectService.assertCanManage(
                projectId,
                userId
        );

        DatabaseConnectionTestResponse testResult =
                executeConnectionTest(request);

        if (!testResult.success()) {
            throw new ResponseStatusException(
                    BAD_REQUEST,
                    testResult.message()
            );
        }

        ProjectDatabaseSource source =
                databaseSourceRepository
                        .findByProjectIdAndPrimaryConnectionTrue(
                                projectId
                        )
                        .orElseGet(() ->
                                new ProjectDatabaseSource(
                                        projectId,
                                        userId
                                )
                        );

        source.updateConnection(
                request.dbType(),
                request.host(),
                request.port(),
                request.databaseName(),
                request.schemaName(),
                request.username(),
                credentialCipher.encrypt(
                        request.password()
                )
        );

        return ProjectDatabaseSourceResponse.from(
                databaseSourceRepository.save(source)
        );
    }

    private DatabaseConnectionTestResponse executeConnectionTest(
            DatabaseConnectionRequest request
    ) {
        DatabaseType databaseType = request.dbType();

        if (!databaseType.isSupported()) {
            return DatabaseConnectionTestResponse.failure(
                    "%s 연결은 아직 지원하지 않습니다."
                            .formatted(databaseType)
            );
        }

        try {
            Class.forName(
                    databaseType.driverClassName()
            );
        } catch (ClassNotFoundException exception) {
            return DatabaseConnectionTestResponse.failure(
                    "%s JDBC 드라이버가 서버에 설치되지 않았습니다."
                            .formatted(databaseType)
            );
        }

        String jdbcUrl = databaseType.jdbcUrl(
                request.host(),
                request.port(),
                request.databaseName()
        );
        Properties properties = buildProperties(request);

        try {
            DriverManager.setLoginTimeout(
                    LOGIN_TIMEOUT_SECONDS
            );

            try (
                    Connection connection =
                            DriverManager.getConnection(
                                    jdbcUrl,
                                    properties
                            )
            ) {
                configureReadOnly(
                        connection,
                        databaseType
                );

                if (
                        databaseType == DatabaseType.POSTGRESQL &&
                        !isPostgreSqlReadOnly(connection)
                ) {
                    return DatabaseConnectionTestResponse.failure(
                            "PostgreSQL 연결이 읽기 전용으로 설정되지 않았습니다."
                    );
                }

                if (!connection.isValid(LOGIN_TIMEOUT_SECONDS)) {
                    return DatabaseConnectionTestResponse.failure(
                            "데이터베이스 연결 유효성 확인에 실패했습니다."
                    );
                }

                DatabaseMetaData metadata =
                        connection.getMetaData();

                return DatabaseConnectionTestResponse.success(
                        metadata.getDatabaseProductName(),
                        metadata.getDatabaseProductVersion()
                );
            }
        } catch (SQLException exception) {
            log.warn(
                    "Database connection test failed: type={}, host={}, port={}, sqlState={}, errorCode={}",
                    request.dbType(),
                    request.host(),
                    request.port(),
                    exception.getSQLState(),
                    exception.getErrorCode()
            );

            return DatabaseConnectionTestResponse.failure(
                    resolveFailureMessage(exception)
            );
        }
    }

    static Properties buildProperties(
            DatabaseConnectionRequest request
    ) {
        Properties properties = new Properties();

        properties.setProperty(
                "user",
                request.username()
        );
        properties.setProperty(
                "password",
                request.password()
        );

        if (request.dbType() == DatabaseType.ORACLE) {
            properties.setProperty(
                    "oracle.net.CONNECT_TIMEOUT",
                    String.valueOf(
                            NETWORK_TIMEOUT_MILLISECONDS
                    )
            );
            properties.setProperty(
                    "oracle.jdbc.ReadTimeout",
                    String.valueOf(
                            NETWORK_TIMEOUT_MILLISECONDS
                    )
            );
        }

        if (request.dbType() == DatabaseType.POSTGRESQL) {
            properties.setProperty(
                    "sslmode",
                    "require"
            );
            properties.setProperty(
                    "readOnly",
                    "true"
            );
            properties.setProperty(
                    "readOnlyMode",
                    "always"
            );
            properties.setProperty(
                    "currentSchema",
                    request.schemaName()
            );
            properties.setProperty(
                    "logServerErrorDetail",
                    "false"
            );
            properties.setProperty(
                    "connectTimeout",
                    String.valueOf(
                            LOGIN_TIMEOUT_SECONDS
                    )
            );
            properties.setProperty(
                    "socketTimeout",
                    String.valueOf(
                            LOGIN_TIMEOUT_SECONDS
                    )
            );
            properties.setProperty(
                    "ApplicationName",
                    "doenaai-connection-test"
            );
        }

        return properties;
    }

    static void configureReadOnly(
            Connection connection,
            DatabaseType databaseType
    ) throws SQLException {
        connection.setReadOnly(true);
        connection.setNetworkTimeout(
                Runnable::run,
                NETWORK_TIMEOUT_MILLISECONDS
        );

        if (
                databaseType == DatabaseType.POSTGRESQL &&
                !connection.isReadOnly()
        ) {
            throw new SQLException(
                    "PostgreSQL JDBC connection rejected read-only mode."
            );
        }
    }

    static boolean isPostgreSqlReadOnly(
            Connection connection
    ) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(LOGIN_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery(
                    "select current_setting('transaction_read_only')"
            )) {
                return resultSet.next() &&
                        "on".equalsIgnoreCase(resultSet.getString(1));
            }
        }
    }

    private String resolveFailureMessage(
            SQLException exception
    ) {
        String sqlState = exception.getSQLState();

        if (
                exception.getErrorCode() == 1017 ||
                (
                        sqlState != null &&
                        sqlState.startsWith("28")
                )
        ) {
            return "DB 계정 또는 비밀번호가 올바르지 않습니다.";
        }

        if (
                sqlState != null &&
                sqlState.startsWith("08")
        ) {
            return "DB 서버에 연결할 수 없습니다. 호스트, 포트와 네트워크 상태를 확인하세요.";
        }

        return "데이터베이스 연결에 실패했습니다. 접속 정보와 서비스명을 확인하세요.";
    }
}
