package com.doenaai.project.connection.service;

import com.doenaai.project.connection.dto.DatabaseConnectionRequest;
import com.doenaai.project.connection.entity.DatabaseType;
import com.doenaai.project.connection.repository.ProjectDatabaseSourceRepository;
import com.doenaai.project.connection.security.CredentialCipher;
import com.doenaai.project.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseConnectionServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectDatabaseSourceRepository databaseSourceRepository;

    @Mock
    private CredentialCipher credentialCipher;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    private DatabaseConnectionService service;

    @BeforeEach
    void setUp() {
        service = new DatabaseConnectionService(
                projectService,
                databaseSourceRepository,
                credentialCipher
        );
    }

    @Test
    void postgresqlPropertiesRequireTlsAndReadOnlySession() {
        Properties properties = ReflectionTestUtils.invokeMethod(
                service,
                "buildProperties",
                postgresqlRequest()
        );

        assertThat(properties)
                .containsEntry("sslmode", "require")
                .containsEntry("readOnly", "true")
                .containsEntry("readOnlyMode", "always")
                .containsEntry("currentSchema", "public")
                .containsEntry("logServerErrorDetail", "false");
    }

    @Test
    void postgresqlReadOnlyVerificationAcceptsServerStateOn()
            throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(
                "select current_setting('transaction_read_only')"
        )).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("on");

        Boolean readOnly = ReflectionTestUtils.invokeMethod(
                service,
                "isPostgreSqlReadOnly",
                connection
        );

        assertThat(readOnly).isTrue();
    }

    @Test
    void postgresqlReadOnlyVerificationRejectsServerStateOff()
            throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(
                "select current_setting('transaction_read_only')"
        )).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString(1)).thenReturn("off");

        Boolean readOnly = ReflectionTestUtils.invokeMethod(
                service,
                "isPostgreSqlReadOnly",
                connection
        );

        assertThat(readOnly).isFalse();
    }

    @Test
    void readOnlyConfigurationFailureIsNotIgnored()
            throws SQLException {
        SQLException failure = new SQLException(
                "read-only mode rejected"
        );

        org.mockito.Mockito.doThrow(failure)
                .when(connection)
                .setReadOnly(true);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "configureReadOnly",
                connection,
                DatabaseType.POSTGRESQL
        )).hasRootCause(failure);

        verify(connection).setReadOnly(true);
    }

    private DatabaseConnectionRequest postgresqlRequest() {
        return new DatabaseConnectionRequest(
                DatabaseType.POSTGRESQL,
                "aws-1-ap-northeast-2.pooler.supabase.com",
                5432,
                "postgres",
                "public",
                "doenaai_ai_reader.project-ref",
                "test-password"
        );
    }
}
