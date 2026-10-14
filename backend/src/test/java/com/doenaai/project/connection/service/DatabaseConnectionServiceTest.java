package com.doenaai.project.connection.service;

import com.doenaai.project.connection.dto.DatabaseConnectionRequest;
import com.doenaai.project.connection.entity.DatabaseType;
import com.doenaai.project.connection.entity.ProjectDatabaseSource;
import com.doenaai.project.connection.repository.ProjectDatabaseSourceRepository;
import com.doenaai.project.connection.security.CredentialCipher;
import com.doenaai.project.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    void connectionLookupRequiresProjectReadAccess() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new AccessDeniedException("접근 거부"))
                .when(projectService).getProject(projectId, userId);

        assertThatThrownBy(() -> service.getConnection(projectId, userId))
                .isInstanceOf(AccessDeniedException.class);
        verify(databaseSourceRepository, never())
                .findByProjectIdAndPrimaryConnectionTrue(projectId);
    }

    @Test
    void connectionLookupExcludesStoredPassword() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ProjectDatabaseSource source = new ProjectDatabaseSource(projectId, userId);
        source.updateConnection(
                DatabaseType.POSTGRESQL,
                "db.example.test",
                5432,
                "postgres",
                "public",
                "reader",
                "encrypted-secret"
        );
        when(databaseSourceRepository.findByProjectIdAndPrimaryConnectionTrue(projectId))
                .thenReturn(Optional.of(source));

        var response = service.getConnection(projectId, userId).orElseThrow();

        verify(projectService).getProject(projectId, userId);
        assertThat(response.username()).isEqualTo("reader");
        assertThat(response.toString()).doesNotContain("encrypted-secret");
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
