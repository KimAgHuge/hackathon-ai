package com.doenaai.project.connection.service;

import com.doenaai.project.connection.dto.DatabaseConnectionRequest;
import com.doenaai.project.connection.entity.ConnectionMode;
import com.doenaai.project.connection.entity.DatabaseType;
import com.doenaai.project.connection.entity.ProjectDatabaseSource;
import com.doenaai.project.connection.repository.ProjectDatabaseSourceRepository;
import com.doenaai.project.connection.security.CredentialCipher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Collects schema metadata only. Never queries application rows or exports credentials. */
@Service
public class DatabaseMetadataCollector {

    static final int MAX_TABLES = 100;
    static final int MAX_COLUMNS = 100;
    static final int MAX_KEY_ROWS = 200;
    static final int MAX_INDEX_ROWS = 200;
    private static final int MAX_SCANNED_ROWS = 10_000;
    private static final int TIMEOUT_SECONDS = 5;
    private static final long COLLECTION_BUDGET_NANOS = Duration.ofSeconds(30).toNanos();

    private final ProjectDatabaseSourceRepository databaseSourceRepository;
    private final CredentialCipher credentialCipher;
    private final ConnectionFactory connectionFactory;

    @Autowired
    public DatabaseMetadataCollector(
            ProjectDatabaseSourceRepository databaseSourceRepository,
            CredentialCipher credentialCipher
    ) {
        this(databaseSourceRepository, credentialCipher, DatabaseMetadataCollector::openConnection);
    }

    DatabaseMetadataCollector(
            ProjectDatabaseSourceRepository databaseSourceRepository,
            CredentialCipher credentialCipher,
            ConnectionFactory connectionFactory
    ) {
        this.databaseSourceRepository = databaseSourceRepository;
        this.credentialCipher = credentialCipher;
        this.connectionFactory = connectionFactory;
    }

    public Map<String, Object> collect(UUID projectId) {
        ProjectDatabaseSource source = databaseSourceRepository
                .findByProjectIdAndPrimaryConnectionTrue(projectId)
                .orElseThrow(() -> new IllegalStateException("분석에 필요한 DB 연결이 없습니다."));
        validateSource(source);

        String password;
        try {
            password = credentialCipher.decrypt(source.getCredentialCiphertext());
        } catch (RuntimeException exception) {
            // Do not retain the cipher/driver exception as a cause: worker errors can be persisted.
            throw new IllegalStateException("저장된 DB 인증정보를 복호화하지 못했습니다.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("저장된 DB 인증정보가 없습니다.");
        }

        DatabaseConnectionRequest request = new DatabaseConnectionRequest(
                source.getDbType(), source.getHost(), source.getPort(), source.getDatabaseName(),
                source.getSchemaName(), source.getUsername(), password
        );
        CollectionState state = new CollectionState();
        try (Connection connection = connectionFactory.open(request)) {
            configureReadOnly(connection, source.getDbType());
            if (!connection.isValid(TIMEOUT_SECONDS)) {
                throw new SQLException("Connection validation failed.");
            }
            try {
                collectTables(connection, source.getSchemaName(), state);
            } catch (CollectionDeadlineExceeded exception) {
                state.truncate("DB 메타데이터 수집 시간 제한으로 일부 객체가 생략되었습니다.");
            }
        } catch (SQLException | ClassNotFoundException | RuntimeException exception) {
            throw new IllegalStateException("DB 메타데이터 수집에 실패했습니다. 연결과 읽기 전용 권한을 확인하세요.");
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", source.getDbType().name());
        summary.put("schema", source.getSchemaName());
        summary.put("tables", state.tables);
        summary.put("warnings", new ArrayList<>(state.warnings));
        summary.put("truncated", state.truncated);
        return summary;
    }

    private void validateSource(ProjectDatabaseSource source) {
        if (source.getConnectionMode() != ConnectionMode.DB_CONNECTION
                || source.getDbType() == null || !source.getDbType().isSupported()) {
            throw new IllegalStateException("분석에 사용할 수 있는 DB 연결이 없습니다.");
        }
        if (source.getCredentialCiphertext() == null || source.getCredentialCiphertext().isBlank()) {
            throw new IllegalStateException("저장된 DB 인증정보가 없습니다.");
        }
        // Validate again: imported or legacy records may not have passed the request DTO constraints.
        if (source.getHost() == null || !source.getHost().matches("[A-Za-z0-9][A-Za-z0-9.-]*")
                || source.getPort() == null || source.getPort() < 1 || source.getPort() > 65535
                || source.getDatabaseName() == null || !source.getDatabaseName().matches("[A-Za-z0-9_$#.-]+")
                || source.getSchemaName() == null || source.getSchemaName().isBlank()
                || source.getUsername() == null || source.getUsername().isBlank()) {
            throw new IllegalStateException("저장된 DB 연결정보가 올바르지 않습니다.");
        }
    }

    private static Connection openConnection(DatabaseConnectionRequest request)
            throws SQLException, ClassNotFoundException {
        Class.forName(request.dbType().driverClassName());
        DriverManager.setLoginTimeout(TIMEOUT_SECONDS);
        return DriverManager.getConnection(
                request.dbType().jdbcUrl(request.host(), request.port(), request.databaseName()),
                DatabaseConnectionService.buildProperties(request)
        );
    }

    private void configureReadOnly(Connection connection, DatabaseType databaseType) throws SQLException {
        DatabaseConnectionService.configureReadOnly(connection, databaseType);
        if (databaseType == DatabaseType.POSTGRESQL
                && !DatabaseConnectionService.isPostgreSqlReadOnly(connection)) {
            throw new SQLException("PostgreSQL server did not enable read-only mode.");
        }
        if (databaseType == DatabaseType.ORACLE) {
            // Oracle's JDBC readOnly flag is only a hint; enforce an actual read-only transaction.
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(TIMEOUT_SECONDS);
                statement.execute("SET TRANSACTION READ ONLY");
            }
        }
    }

    private void collectTables(Connection connection, String schema, CollectionState state) throws SQLException {
        state.checkDeadline();
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        String escape = metadata.getSearchStringEscape();
        String schemaPattern = exactPattern(schema, escape);
        // Close the table cursor before issuing more metadata queries (important for some drivers).
        List<TableReference> references = new ArrayList<>();
        try (ResultSet rows = metadata.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            int scanned = 0;
            while (rows.next()) {
                state.checkDeadline();
                if (++scanned > MAX_SCANNED_ROWS) {
                    state.truncate("테이블 메타데이터 검색 한도로 일부 객체가 생략되었습니다.");
                    break;
                }
                String name = rows.getString("TABLE_NAME");
                String type = rows.getString("TABLE_TYPE");
                if (!Objects.equals(schema, rows.getString("TABLE_SCHEM")) || name == null
                        || !("TABLE".equals(type) || "VIEW".equals(type))) {
                    continue;
                }
                if (references.size() == MAX_TABLES) {
                    state.truncate("테이블 수집 한도(100개)로 일부 객체가 생략되었습니다.");
                    break;
                }
                references.add(new TableReference(name, type));
            }
        }
        if (references.isEmpty()) {
            state.warnings.add("선택한 스키마에서 조회 가능한 테이블/뷰가 없습니다. 스키마명과 메타데이터 조회 권한을 확인하세요.");
        }
        for (TableReference reference : references) {
            state.checkDeadline();
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("schema", schema);
            table.put("name", reference.name());
            table.put("type", reference.type());
            table.put("columns", collectColumns(metadata, catalog, schema, reference.name(), escape, state));
            table.put("primaryKey", collectPrimaryKey(metadata, catalog, schema, reference.name(), state));
            table.put("foreignKeys", collectForeignKeys(metadata, catalog, schema, reference.name(), state));
            table.put("indexes", collectIndexes(metadata, catalog, schema, reference.name(), state));
            state.tables.add(table);
        }
    }

    private List<Map<String, Object>> collectColumns(
            DatabaseMetaData metadata, String catalog, String schema, String table, String escape, CollectionState state
    ) throws SQLException {
        state.checkDeadline();
        List<Map<String, Object>> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(catalog, exactPattern(schema, escape), exactPattern(table, escape), "%")) {
            int scanned = 0;
            while (rows.next()) {
                state.checkDeadline();
                if (++scanned > MAX_SCANNED_ROWS) {
                    state.truncate("컬럼 메타데이터 검색 한도로 일부 컬럼이 생략되었습니다.");
                    break;
                }
                if (!matchesTable(rows, schema, table, "TABLE_SCHEM", "TABLE_NAME")) {
                    continue;
                }
                if (columns.size() == MAX_COLUMNS) {
                    state.truncate("테이블별 컬럼 수집 한도(100개)로 일부 컬럼이 생략되었습니다.");
                    break;
                }
                Map<String, Object> column = new LinkedHashMap<>();
                column.put("name", rows.getString("COLUMN_NAME"));
                column.put("type", rows.getString("TYPE_NAME"));
                int nullable = rows.getInt("NULLABLE");
                column.put("nullable", nullable == DatabaseMetaData.columnNullableUnknown
                        ? null : nullable == DatabaseMetaData.columnNullable);
                columns.add(column);
            }
        }
        return columns;
    }

    private List<String> collectPrimaryKey(
            DatabaseMetaData metadata, String catalog, String schema, String table, CollectionState state
    ) throws SQLException {
        state.checkDeadline();
        Map<Integer, String> columns = new TreeMap<>();
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, table)) {
            int count = 0;
            while (rows.next()) {
                state.checkDeadline();
                if (++count > MAX_KEY_ROWS) {
                    state.truncate("키 메타데이터 수집 한도로 일부 키가 생략되었습니다.");
                    break;
                }
                if (matchesTable(rows, schema, table, "TABLE_SCHEM", "TABLE_NAME")) {
                    columns.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
                }
            }
        }
        return new ArrayList<>(columns.values());
    }

    private List<Map<String, Object>> collectForeignKeys(
            DatabaseMetaData metadata, String catalog, String schema, String table, CollectionState state
    ) throws SQLException {
        state.checkDeadline();
        List<Map<String, Object>> keys = new ArrayList<>();
        try (ResultSet rows = metadata.getImportedKeys(catalog, schema, table)) {
            int count = 0;
            while (rows.next()) {
                state.checkDeadline();
                if (++count > MAX_KEY_ROWS) {
                    state.truncate("키 메타데이터 수집 한도로 일부 키가 생략되었습니다.");
                    break;
                }
                if (!matchesTable(rows, schema, table, "FKTABLE_SCHEM", "FKTABLE_NAME")) {
                    continue;
                }
                Map<String, Object> key = new LinkedHashMap<>();
                key.put("column", rows.getString("FKCOLUMN_NAME"));
                key.put("referencedSchema", rows.getString("PKTABLE_SCHEM"));
                key.put("referencedTable", rows.getString("PKTABLE_NAME"));
                key.put("referencedColumn", rows.getString("PKCOLUMN_NAME"));
                keys.add(key);
            }
        }
        return keys;
    }

    private List<Map<String, Object>> collectIndexes(
            DatabaseMetaData metadata, String catalog, String schema, String table, CollectionState state
    ) throws SQLException {
        state.checkDeadline();
        Map<String, IndexColumns> indexes = new LinkedHashMap<>();
        // approximate=true avoids requesting fresh table statistics; statistics are never exported.
        try (ResultSet rows = metadata.getIndexInfo(catalog, schema, table, false, true)) {
            int count = 0;
            while (rows.next()) {
                state.checkDeadline();
                if (++count > MAX_INDEX_ROWS) {
                    state.truncate("인덱스 메타데이터 수집 한도로 일부 인덱스가 생략되었습니다.");
                    break;
                }
                if (!matchesTable(rows, schema, table, "TABLE_SCHEM", "TABLE_NAME")
                        || rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null) {
                    continue;
                }
                IndexColumns index = indexes.get(name);
                if (index == null) {
                    index = new IndexColumns(!rows.getBoolean("NON_UNIQUE"), new TreeMap<>());
                    indexes.put(name, index);
                }
                if (column != null) {
                    index.columns().put(rows.getInt("ORDINAL_POSITION"), column);
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        indexes.forEach((name, index) -> result.add(Map.of(
                "name", name,
                "columns", new ArrayList<>(index.columns().values()),
                "unique", index.unique()
        )));
        return result;
    }

    private boolean matchesTable(ResultSet rows, String schema, String table, String schemaColumn, String tableColumn)
            throws SQLException {
        return Objects.equals(schema, rows.getString(schemaColumn))
                && Objects.equals(table, rows.getString(tableColumn));
    }

    private static String exactPattern(String identifier, String escape) throws SQLException {
        if (escape == null || escape.isEmpty()) {
            if (identifier.contains("_") || identifier.contains("%")) {
                throw new SQLException("The driver does not support exact metadata patterns.");
            }
            return identifier;
        }
        return identifier.replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(DatabaseConnectionRequest request) throws SQLException, ClassNotFoundException;
    }

    private record TableReference(String name, String type) {}

    private record IndexColumns(boolean unique, Map<Integer, String> columns) {}

    private static final class CollectionState {
        private final long startedAt = System.nanoTime();
        private final List<Map<String, Object>> tables = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private boolean truncated;

        private void checkDeadline() {
            if (System.nanoTime() - startedAt > COLLECTION_BUDGET_NANOS) {
                throw new CollectionDeadlineExceeded();
            }
        }

        private void truncate(String warning) {
            truncated = true;
            warnings.add(warning);
        }
    }

    private static final class CollectionDeadlineExceeded extends RuntimeException {}
}
