package com.doenaai.project.connection.service;

import com.doenaai.project.connection.dto.DatabaseConnectionRequest;
import com.doenaai.project.connection.entity.DatabaseType;
import com.doenaai.project.connection.entity.ProjectDatabaseSource;
import com.doenaai.project.connection.repository.ProjectDatabaseSourceRepository;
import com.doenaai.project.connection.security.CredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseMetadataCollectorTest {

    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final String SCHEMA = "app_data";

    @Mock private ProjectDatabaseSourceRepository repository;
    @Mock private CredentialCipher cipher;
    @Mock private DatabaseMetadataCollector.ConnectionFactory connectionFactory;
    @Mock private Connection connection;
    @Mock private DatabaseMetaData metadata;
    @Mock private Statement statement;

    private DatabaseMetadataCollector collector;

    @BeforeEach
    void setUp() {
        collector = new DatabaseMetadataCollector(repository, cipher, connectionFactory);
    }

    @Test
    void missingDatabaseConnectionFailsClearly() {
        when(repository.findByProjectIdAndPrimaryConnectionTrue(PROJECT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("분석에 필요한 DB 연결이 없습니다.")
                .hasNoCause();
        verifyNoInteractions(cipher, connectionFactory);
    }

    @Test
    void absentStoredCredentialsNeverOpenConnection() {
        storeSource(DatabaseType.POSTGRESQL, null);

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .hasMessage("저장된 DB 인증정보가 없습니다.")
                .hasNoCause();
        verifyNoInteractions(cipher, connectionFactory);
    }

    @Test
    void decryptionFailureDoesNotExposeCiphertextOrUnderlyingMessage() {
        storeSource(DatabaseType.POSTGRESQL, "encrypted-secret");
        when(cipher.decrypt("encrypted-secret")).thenThrow(new IllegalArgumentException("sensitive-ciphertext"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .hasMessage("저장된 DB 인증정보를 복호화하지 못했습니다.")
                .hasNoCause();
        verifyNoInteractions(connectionFactory);
    }

    @Test
    void blankDecryptedPasswordIsRejected() {
        storeSource(DatabaseType.POSTGRESQL, "encrypted-secret");
        when(cipher.decrypt("encrypted-secret")).thenReturn(" ");

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .hasMessage("저장된 DB 인증정보가 없습니다.")
                .hasNoCause();
        verifyNoInteractions(connectionFactory);
    }

    @Test
    void postgresServerReadOnlyOffFailsBeforeMetadataAndClosesResources() throws Exception {
        prepareConnection(DatabaseType.POSTGRESQL);
        when(connection.isReadOnly()).thenReturn(true);
        ResultSet readOnlyRows = serverReadOnly("off");

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("읽기 전용 권한")
                .hasNoCause();

        verify(connection, never()).getMetaData();
        verify(connection).close();
        verify(statement).setQueryTimeout(5);
        verify(statement).close();
        verify(readOnlyRows).close();
    }

    @Test
    void readOnlyConfigurationFailureNeverCollectsMetadata() throws Exception {
        prepareConnection(DatabaseType.POSTGRESQL);
        doThrow(new SQLException("private-host password=plain-secret"))
                .when(connection).setReadOnly(true);

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .hasMessageNotContaining("private-host")
                .hasMessageNotContaining("plain-secret")
                .hasNoCause();
        verify(connection, never()).getMetaData();
        verify(connection).close();
    }

    @Test
    void collectsOnlySelectedSchemaAndNeverExportsConnectionInformation() throws Exception {
        preparePostgresMetadata();
        ResultSet tableRows = tableRows(List.of(
                Map.of("TABLE_SCHEM", "appXdata", "TABLE_NAME", "wrong_schema", "TABLE_TYPE", "TABLE"),
                Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", "orders_%", "TABLE_TYPE", "TABLE")
        ));
        ResultSet columnRows = resultSet(List.of(
                column("appXdata", "orders_%", "outside_schema", 0),
                column(SCHEMA, "orders_X", "outside_table", 0),
                column(SCHEMA, "orders_%", "id", DatabaseMetaData.columnNoNulls),
                column(SCHEMA, "orders_%", "user_id", DatabaseMetaData.columnNullable)
        ));
        when(metadata.getColumns(null, "app\\_data", "orders\\_\\%", "%")).thenReturn(columnRows);
        ResultSet primaryRows = resultSet(List.of(
                Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", "orders_%", "KEY_SEQ", 2, "COLUMN_NAME", "user_id"),
                Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", "orders_%", "KEY_SEQ", 1, "COLUMN_NAME", "id")
        ));
        when(metadata.getPrimaryKeys(null, SCHEMA, "orders_%")).thenReturn(primaryRows);
        ResultSet foreignRows = resultSet(List.of(Map.of(
                "FKTABLE_SCHEM", SCHEMA, "FKTABLE_NAME", "orders_%", "FKCOLUMN_NAME", "user_id",
                "PKTABLE_SCHEM", SCHEMA, "PKTABLE_NAME", "users", "PKCOLUMN_NAME", "id"
        )));
        when(metadata.getImportedKeys(null, SCHEMA, "orders_%")).thenReturn(foreignRows);
        ResultSet indexRows = resultSet(List.of(
                indexRow("orders_%", "orders_unique", "user_id", 2),
                indexRow("orders_%", "orders_unique", "id", 1),
                Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", "orders_%", "TYPE", DatabaseMetaData.tableIndexStatistic)
        ));
        when(metadata.getIndexInfo(null, SCHEMA, "orders_%", false, true)).thenReturn(indexRows);

        Map<String, Object> summary = collector.collect(PROJECT_ID);

        assertThat(summary).containsOnlyKeys("type", "schema", "tables", "warnings", "truncated")
                .containsEntry("type", "POSTGRESQL").containsEntry("schema", SCHEMA)
                .containsEntry("warnings", List.of()).containsEntry("truncated", false);
        List<Map<String, Object>> tables = tables(summary);
        assertThat(tables).hasSize(1);
        assertThat(tables.getFirst())
                .containsEntry("name", "orders_%")
                .containsEntry("columns", List.of(
                        Map.of("name", "id", "type", "int8", "nullable", false),
                        Map.of("name", "user_id", "type", "int8", "nullable", true)))
                .containsEntry("primaryKey", List.of("id", "user_id"))
                .containsEntry("foreignKeys", List.of(Map.of("column", "user_id", "referencedSchema", SCHEMA,
                        "referencedTable", "users", "referencedColumn", "id")))
                .containsEntry("indexes", List.of(Map.of("name", "orders_unique", "columns", List.of("id", "user_id"), "unique", true)));
        assertThat(summary.toString()).doesNotContain("private-host", "reader-account", "plain-secret", "encrypted-secret",
                "outside_schema", "outside_table", "wrong_schema");
        verify(connectionFactory).open(new DatabaseConnectionRequest(
                DatabaseType.POSTGRESQL, "private-host", 5432, "private-database", SCHEMA, "reader-account", "plain-secret"));
        verify(tableRows).close();
        verify(columnRows).close();
        verify(primaryRows).close();
        verify(foreignRows).close();
        verify(indexRows).close();
        verify(connection).close();
        // The only explicit SELECT is the read-only setting check; all other access is JDBC metadata.
        verify(statement).executeQuery("select current_setting('transaction_read_only')");
        verify(connection).createStatement();
    }

    @Test
    void columnLimitIsReportedWithoutFetchingBusinessRows() throws Exception {
        preparePostgresMetadata();
        tableRows(List.of(Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", "large_table", "TABLE_TYPE", "TABLE")));
        List<Map<String, Object>> columns = IntStream.range(0, DatabaseMetadataCollector.MAX_COLUMNS + 1)
                .mapToObj(i -> column(SCHEMA, "large_table", "column_" + i, DatabaseMetaData.columnNoNulls)).toList();
        ResultSet columnsCursor = resultSet(columns);
        when(metadata.getColumns(null, "app\\_data", "large\\_table", "%")).thenReturn(columnsCursor);
        emptyKeysAndIndexes("large_table");

        Map<String, Object> summary = collector.collect(PROJECT_ID);

        assertThat((List<?>) tables(summary).getFirst().get("columns")).hasSize(DatabaseMetadataCollector.MAX_COLUMNS);
        assertThat(summary).containsEntry("truncated", true);
        assertThat(summary.get("warnings").toString()).contains("컬럼 수집 한도");
        verify(columnsCursor).close();
    }

    @Test
    void tableLimitIsReportedAndOnlyBoundedTablesAreQueried() throws Exception {
        preparePostgresMetadata();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i <= DatabaseMetadataCollector.MAX_TABLES; i++) {
            entries.add(Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", "t" + i, "TABLE_TYPE", "TABLE"));
        }
        tableRows(entries);
        when(metadata.getColumns(isNull(), eq("app\\_data"), anyString(), eq("%")))
                .thenAnswer(invocation -> resultSet(List.of()));
        when(metadata.getPrimaryKeys(isNull(), eq(SCHEMA), anyString())).thenAnswer(invocation -> resultSet(List.of()));
        when(metadata.getImportedKeys(isNull(), eq(SCHEMA), anyString())).thenAnswer(invocation -> resultSet(List.of()));
        when(metadata.getIndexInfo(isNull(), eq(SCHEMA), anyString(), eq(false), eq(true)))
                .thenAnswer(invocation -> resultSet(List.of()));

        Map<String, Object> summary = collector.collect(PROJECT_ID);

        assertThat(tables(summary)).hasSize(DatabaseMetadataCollector.MAX_TABLES);
        assertThat(summary).containsEntry("truncated", true);
        assertThat(summary.get("warnings").toString()).contains("테이블 수집 한도");
        verify(metadata, never()).getColumns(null, "app\\_data", "t100", "%");
    }

    @Test
    void metadataFailureIsSanitizedAndEveryOpenedResourceCloses() throws Exception {
        preparePostgresMetadata();
        ResultSet tablesCursor = tableRows(List.of(Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", "orders", "TABLE_TYPE", "TABLE")));
        ResultSet columnsCursor = mock(ResultSet.class);
        when(metadata.getColumns(null, "app\\_data", "orders", "%")).thenReturn(columnsCursor);
        when(columnsCursor.next()).thenThrow(new SQLException("host=private-host user=reader-account password=plain-secret"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .hasMessageNotContaining("private-host")
                .hasMessageNotContaining("reader-account")
                .hasMessageNotContaining("plain-secret")
                .hasNoCause();
        verify(tablesCursor).close();
        verify(columnsCursor).close();
        verify(connection).close();
    }

    @Test
    void emptySchemaWarnsAboutSchemaNameAndMetadataVisibility() throws Exception {
        preparePostgresMetadata();
        tableRows(List.of());

        Map<String, Object> summary = collector.collect(PROJECT_ID);

        assertThat(summary).containsEntry("tables", List.of()).containsEntry("truncated", false);
        assertThat(summary.get("warnings").toString()).contains("스키마명", "조회 권한");
    }

    @Test
    void oracleEnforcesReadOnlyTransactionBeforeCollectingMetadata() throws Exception {
        prepareConnection(DatabaseType.ORACLE);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.isValid(5)).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
        tableRows(List.of());

        Map<String, Object> summary = collector.collect(PROJECT_ID);

        assertThat(summary).containsEntry("type", "ORACLE").containsEntry("tables", List.of());
        var order = org.mockito.Mockito.inOrder(connection, statement, metadata);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setNetworkTimeout(any(), eq(5000));
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).createStatement();
        order.verify(statement).setQueryTimeout(5);
        order.verify(statement).execute("SET TRANSACTION READ ONLY");
        order.verify(statement).close();
        order.verify(connection).isValid(5);
        order.verify(connection).getMetaData();
        verify(connection).close();
    }

    @Test
    void oracleReadOnlyTransactionFailureNeverCollectsMetadata() throws Exception {
        prepareConnection(DatabaseType.ORACLE);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("SET TRANSACTION READ ONLY"))
                .thenThrow(new SQLException("private-host refused plain-secret"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID))
                .hasMessageContaining("읽기 전용 권한")
                .hasMessageNotContaining("private-host")
                .hasMessageNotContaining("plain-secret")
                .hasNoCause();
        verify(connection, never()).getMetaData();
        verify(statement).close();
        verify(connection).close();
    }

    private void prepareConnection(DatabaseType type) throws Exception {
        storeSource(type, "encrypted-secret");
        when(cipher.decrypt("encrypted-secret")).thenReturn("plain-secret");
        when(connectionFactory.open(any())).thenReturn(connection);
    }

    private void preparePostgresMetadata() throws Exception {
        prepareConnection(DatabaseType.POSTGRESQL);
        when(connection.isReadOnly()).thenReturn(true);
        serverReadOnly("on");
        when(connection.isValid(5)).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getSearchStringEscape()).thenReturn("\\");
    }

    private void storeSource(DatabaseType type, String encryptedPassword) {
        ProjectDatabaseSource source = new ProjectDatabaseSource(PROJECT_ID, UUID.randomUUID());
        source.updateConnection(type, "private-host", 5432, "private-database", SCHEMA, "reader-account", encryptedPassword);
        when(repository.findByProjectIdAndPrimaryConnectionTrue(PROJECT_ID)).thenReturn(Optional.of(source));
    }

    private ResultSet serverReadOnly(String setting) throws SQLException {
        ResultSet rows = resultSet(List.of(Map.of("1", setting)));
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("select current_setting('transaction_read_only')")).thenReturn(rows);
        return rows;
    }

    private ResultSet tableRows(List<Map<String, Object>> tables) throws SQLException {
        ResultSet rows = resultSet(tables);
        when(metadata.getTables(isNull(), eq("app\\_data"), eq("%"), any(String[].class))).thenReturn(rows);
        return rows;
    }

    private void emptyKeysAndIndexes(String table) throws SQLException {
        when(metadata.getPrimaryKeys(null, SCHEMA, table)).thenReturn(resultSet(List.of()));
        when(metadata.getImportedKeys(null, SCHEMA, table)).thenReturn(resultSet(List.of()));
        when(metadata.getIndexInfo(null, SCHEMA, table, false, true)).thenReturn(resultSet(List.of()));
    }

    private Map<String, Object> column(String schema, String table, String name, int nullable) {
        return Map.of("TABLE_SCHEM", schema, "TABLE_NAME", table, "COLUMN_NAME", name, "TYPE_NAME", "int8", "NULLABLE", nullable);
    }

    private Map<String, Object> indexRow(String table, String index, String column, int position) {
        return Map.of("TABLE_SCHEM", SCHEMA, "TABLE_NAME", table, "TYPE", DatabaseMetaData.tableIndexOther,
                "INDEX_NAME", index, "COLUMN_NAME", column, "NON_UNIQUE", false, "ORDINAL_POSITION", position);
    }

    private ResultSet resultSet(List<Map<String, Object>> rows) {
        AtomicInteger cursor = new AtomicInteger(-1);
        return mock(ResultSet.class, invocation -> {
            return switch (invocation.getMethod().getName()) {
                case "next" -> cursor.incrementAndGet() < rows.size();
                case "getString" -> (String) rows.get(cursor.get()).get(String.valueOf((Object) invocation.getArgument(0)));
                case "getInt" -> ((Number) rows.get(cursor.get()).getOrDefault(String.valueOf((Object) invocation.getArgument(0)), 0)).intValue();
                case "getShort" -> ((Number) rows.get(cursor.get()).getOrDefault(String.valueOf((Object) invocation.getArgument(0)), 0)).shortValue();
                case "getBoolean" -> rows.get(cursor.get()).getOrDefault(String.valueOf((Object) invocation.getArgument(0)), false);
                default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            };
        });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tables(Map<String, Object> summary) {
        return (List<Map<String, Object>>) summary.get("tables");
    }
}
