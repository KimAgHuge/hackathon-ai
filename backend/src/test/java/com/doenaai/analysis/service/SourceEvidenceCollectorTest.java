package com.doenaai.analysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SourceEvidenceCollectorTest {
    @TempDir
    Path checkout;

    private final SourceEvidenceCollector collector = new SourceEvidenceCollector();
    private final Map<String, Object> database = Map.of("tables", List.of(
            table("public", "users"), table("audit", "users"), table("sales", "orders"), table("audit", "events")));

    @Test
    void extractsQualifiedSqlAndRetainsEveryAmbiguousSchemaWithoutExposingValues() throws IOException {
        write("queries.sql", """
                -- SELECT * FROM audit.users;
                SELECT * FROM public.users u
                JOIN "sales"."orders" o ON u.id = o.user_id;
                INSERT INTO audit.events (message) VALUES ('TOP_SECRET_INSERT');
                UPDATE public.users SET name = 'SELECT * FROM audit.users';
                DELETE FROM audit.events WHERE message = 'TOP_SECRET_DELETE';
                SELECT * FROM users;
                """);

        var result = collector.collect(checkout, database);

        assertThat(result.relations()).hasSize(7);
        assertRelation(result, "public", "users", "queries.sql", 2, "SQL_SELECT", false);
        assertRelation(result, "sales", "orders", "queries.sql", 3, "SQL_SELECT", false);
        assertRelation(result, "audit", "events", "queries.sql", 4, "SQL_INSERT", false);
        assertRelation(result, "public", "users", "queries.sql", 5, "SQL_UPDATE", false);
        assertRelation(result, "audit", "events", "queries.sql", 6, "SQL_DELETE", false);
        assertRelation(result, "public", "users", "queries.sql", 7, "SQL_SELECT", true);
        assertRelation(result, "audit", "users", "queries.sql", 7, "SQL_SELECT", true);
        assertThat(result.relations().stream().filter(relation -> relation.get("line").equals(7)))
                .allSatisfy(relation -> assertThat(relation.get("ambiguous")).isEqualTo(true));
        assertThat(result.toString()).doesNotContain("TOP_SECRET", "user_id", "ON u.id", "SET name", "message");
    }

    @Test
    void matchesJpaAndSqlLiteralsWithActualLinesButNotCommentsOrLogicalEntityNames() throws IOException {
        write("src/Order.java", """
                // @Table(name = "users", schema = "audit")
                @Entity(name = "users")
                @Table(name = "orders", schema = "sales")
                class Order {
                    String sql = "SELECT * FROM \\"public\\".\\"users\\" WHERE token = 'NEVER_EXPORT_TOKEN'";
                    String label = "orders";
                }
                """);
        write("src/User.py", """
                class User:
                    __tablename__ = 'users'
                """);
        write("src/Record.ts", """
                @Entity({ name: 'events', schema: 'audit' })
                export class Record {}
                """);

        var result = collector.collect(checkout, database);

        assertThat(result.relations()).hasSize(5);
        assertRelation(result, "sales", "orders", "src/Order.java", 3, "ORM_TABLE", false);
        assertRelation(result, "public", "users", "src/Order.java", 5, "SQL_SELECT", false);
        assertRelation(result, "public", "users", "src/User.py", 2, "ORM_TABLE", true);
        assertRelation(result, "audit", "users", "src/User.py", 2, "ORM_TABLE", true);
        assertRelation(result, "audit", "events", "src/Record.ts", 1, "ORM_TABLE", false);
        assertThat(result.toString()).doesNotContain("NEVER_EXPORT_TOKEN", "String sql", "class Order");
    }

    @Test
    void extractsMyBatisStatementsWithoutExpandingExternalEntitiesOrXmlComments() throws IOException {
        Path external = write("external.txt", "SELECT * FROM audit.events");
        write("mappers/UserMapper.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE mapper [<!ENTITY stolen SYSTEM "%s">]>
                <mapper namespace="DO_NOT_EXPORT_NAMESPACE">
                  <!-- <select id="fake">SELECT * FROM audit.users</select> -->
                  <select id="find">
                    SELECT * FROM public.users
                    <where>id = #{id}</where>
                    &stolen;
                  </select>
                  <update id="save"><![CDATA[UPDATE sales.orders SET id = 1]]></update>
                  <resultMap id="notSql"><result column="SELECT * FROM audit.events"/></resultMap>
                </mapper>
                """.formatted(external.toUri()));

        var result = collector.collect(checkout, database);

        assertThat(result.relations()).hasSize(2);
        assertRelation(result, "public", "users", "mappers/UserMapper.xml", 6, "SQL_SELECT", false);
        assertRelation(result, "sales", "orders", "mappers/UserMapper.xml", 10, "SQL_UPDATE", false);
        assertThat(result.toString()).doesNotContain("DO_NOT_EXPORT_NAMESPACE", external.toUri().toString());
    }

    @Test
    void detectsMultipleEcosystemsUsingControlledNamesAndNeverEmitsDependencyValues() throws IOException {
        write("web/package.json", """
                {"name":"PRIVATE_PACKAGE_NAME","dependencies":{"react":"https://USER:SECRET_PASSWORD@host/pkg",
                "vue":"SECRET_VERSION", "react-impostor":"1.0", "PRIVATE_UNKNOWN_DEPENDENCY":"SECRET"}}
                """);
        write("api/requirements.txt", "FastAPI==0.1\nDjango>=4\n# flask==1\n");
        write("go/go.mod", "module example.invalid/private\nrequire github.com/gin-gonic/gin v1.0.0\n");
        write("rust/Cargo.toml", "[dependencies]\naxum = \"0.8\"\n");
        write("dotnet/Api.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"><ItemGroup><PackageReference Include=\"Microsoft.EntityFrameworkCore\" Version=\"PRIVATE_VERSION\"/></ItemGroup></Project>");

        var result = collector.collect(checkout, database);
        List<Map<String, Object>> frameworks = maps(result.sourceSummary().get("frameworks"));

        assertThat(frameworks).extracting(fact -> fact.get("name"))
                .contains("React", "Vue", "FastAPI", "Django", "Gin", "Axum", "ASP.NET Core", "Entity Framework Core")
                .doesNotContain("Flask", "PRIVATE_UNKNOWN_DEPENDENCY");
        assertThat(result.toString()).doesNotContain("PRIVATE_", "SECRET", "USER:", "example.invalid", "react-impostor");
        assertThat(frameworks).allSatisfy(fact -> assertThat(fact).doesNotContainKeys("declaredVersion", "resolvedVersion"));
    }

    @Test
    void rejectsExternalEntitiesInXmlManifestsWithoutEchoingParserDiagnostics() throws IOException {
        write("pom.xml", """
                <!DOCTYPE project [<!ENTITY secret SYSTEM "file:///DO_NOT_READ_SECRET">]>
                <project><dependencies><dependency><artifactId>&secret;</artifactId></dependency></dependencies></project>
                """);

        var result = collector.collect(checkout, database);

        assertThat(maps(result.sourceSummary().get("frameworks"))).isEmpty();
        assertThat(result.sourceSummary().get("detectionWarnings").toString()).contains("invalid");
        assertThat(result.toString()).doesNotContain("DO_NOT_READ_SECRET", "DOCTYPE", "file:///");
    }

    @Test
    void ignoresSecretFilesGeneratedDirectoriesAndBinaryFiles() throws IOException {
        write("src/Safe.java", "class Safe {}\n");
        for (String file : List.of(".env.sql", "application-local.java", "credentials.py", "build/Hidden.sql",
                "node_modules/Hidden.sql", "secrets/Hidden.sql", "src/generated/Hidden.sql", "assets/bundle.min.js")) {
            write(file, "SELECT * FROM audit.events; SECRET_FILE_CONTENT");
        }
        Files.write(checkout.resolve("Binary.java"), new byte[]{0, 1, 2, 3});
        Files.write(checkout.resolve("Invalid.java"), new byte[]{(byte) 0xc3, (byte) 0x28});

        var result = collector.collect(checkout, database);

        assertThat(result.relations()).isEmpty();
        assertThat(maps(result.sourceSummary().get("components"))).extracting(fact -> fact.get("path"))
                .containsExactly("src/Safe.java");
        assertThat(result.toString()).doesNotContain("SECRET_FILE_CONTENT", "Hidden", ".env", "credentials");
    }

    @Test
    void ignoresSymlinkFilesAndDirectories() throws IOException {
        write("src/Safe.java", "class Safe {}\n");
        Path hidden = write("outside.txt", "SELECT * FROM public.users;");
        write("secrets/Hidden.sql", "SELECT * FROM audit.events; SECRET_FILE_CONTENT");
        createSymbolicLinkOrSkip(checkout.resolve("linked.sql"), hidden);
        createSymbolicLinkOrSkip(checkout.resolve("linked-directory"), checkout.resolve("secrets"));

        var result = collector.collect(checkout, database);

        assertThat(result.relations()).isEmpty();
        assertThat(maps(result.sourceSummary().get("components"))).extracting(fact -> fact.get("path"))
                .containsExactly("src/Safe.java");
        assertThat(result.toString()).doesNotContain("SECRET_FILE_CONTENT", "linked", "Hidden");
    }

    @Test
    void refusesSymlinkCheckoutRoot() throws IOException {
        Path linkedRoot = checkout.resolve("linked");
        createSymbolicLinkOrSkip(linkedRoot, checkout);

        assertThatThrownBy(() -> collector.collect(linkedRoot, database))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("symbolic link");
    }

    @Test
    void reportsFileCountLimit() throws IOException {
        write("first.java", "class A {}");
        write("second.java", "class B {}");

        var result = new SourceEvidenceCollector(1, 1024, 4096, 100, 100).collect(checkout, database);

        assertThat(metrics(result)).containsEntry("truncated", true).containsEntry("scannedFileCount", 1);
        assertThat(result.sourceSummary().get("detectionWarnings").toString()).contains("file count limit");
    }

    @Test
    void reportsPerFileAndTotalByteLimits() throws IOException {
        write("first.sql", "SELECT * FROM public.users;");

        var oversized = new SourceEvidenceCollector(10, 8, 4096, 100, 100).collect(checkout, database);
        var total = new SourceEvidenceCollector(10, 1024, 8, 100, 100).collect(checkout, database);

        assertThat(metrics(oversized)).containsEntry("truncated", true).containsEntry("scannedFileCount", 0);
        assertThat(oversized.sourceSummary().get("detectionWarnings").toString()).contains("per-file byte limit");
        assertThat(metrics(total)).containsEntry("truncated", true).containsEntry("scannedFileCount", 0);
        assertThat(total.sourceSummary().get("detectionWarnings").toString()).contains("Total source byte limit");
        assertThat(oversized.relations()).isEmpty();
        assertThat(total.relations()).isEmpty();
    }

    @Test
    void boundsTraversalAndRelationshipOutput() throws IOException {
        write("first.sql", "SELECT * FROM public.users;\nSELECT * FROM audit.events;");
        var entries = new SourceEvidenceCollector(10, 1024, 4096, 1, 100).collect(checkout, database);
        var relations = new SourceEvidenceCollector(10, 1024, 4096, 100, 1).collect(checkout, database);

        assertThat(metrics(entries)).containsEntry("truncated", true);
        assertThat(entries.sourceSummary().get("detectionWarnings").toString()).contains("entry limit");
        assertThat(relations.relations()).hasSize(1);
        assertThat(metrics(relations)).containsEntry("truncated", true);
        assertThat(relations.sourceSummary().get("detectionWarnings").toString()).contains("relationship limit");
    }

    @Test
    void neverFabricatesRelationshipsForBareNamesCallsCommentsOrUnknownTables() throws IOException {
        write("src/UserService.java", """
                @Service
                class UserService {
                    // SELECT * FROM public.users
                    void users() { orders(); repository.events(); }
                    String name = "users";
                    String query = "SELECT * FROM public.not_in_metadata";
                    String unsupportedCatalog = "SELECT * FROM catalog.public.users";
                }
                """);

        var result = collector.collect(checkout, database);

        assertThat(result.relations()).isEmpty();
        assertThat(maps(result.sourceSummary().get("components"))).singleElement()
                .satisfies(component -> assertThat(component.get("type")).isEqualTo("SERVICE"));
        assertThat(result.sourceSummary().get("limitations").toString()).contains("No complete call graph", "Column-level", "dynamic SQL");
        assertThat(result.toString()).doesNotContain("orders()", "repository.events", "not_in_metadata", "catalog.public.users");
    }

    @Test
    void collectsPrismaMappedSchemaTablesButDoesNotGuessImplicitNames() throws IOException {
        write("schema.prisma", """
                model User {
                  id Int @id
                  @@map("users")
                  @@schema("audit")
                }
                model orders { id Int @id }
                """);

        var result = collector.collect(checkout, database);

        assertThat(result.relations()).hasSize(1);
        assertRelation(result, "audit", "users", "schema.prisma", 3, "ORM_TABLE", false);
    }

    @Test
    void quotedIdentifiersPreserveCaseAndDoNotCollapseDistinctDatabaseTables() throws IOException {
        write("quoted.sql", "SELECT * FROM \"public\".\"Users\";\n");
        Map<String, Object> caseSensitive = Map.of("tables", List.of(table("public", "users"), table("public", "Users")));

        var result = collector.collect(checkout, caseSensitive);

        assertThat(result.relations()).hasSize(1);
        assertRelation(result, "public", "Users", "quoted.sql", 1, "SQL_SELECT", false);
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileAlreadyExistsException | NoSuchFileException | NotDirectoryException exception) {
            // Fixture path mistakes should still fail instead of looking like unsupported symlinks.
            throw exception;
        } catch (UnsupportedOperationException | SecurityException | FileSystemException exception) {
            assumeTrue(false, "Symbolic links are unavailable on this filesystem: "
                    + exception.getClass().getSimpleName());
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path file = checkout.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> table(String schema, String name) {
        return Map.of("schema", schema, "name", name, "columns", List.of(), "primaryKey", List.of(), "foreignKeys", List.of(), "indexes", List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metrics(SourceEvidenceCollector.ScanResult result) {
        return (Map<String, Object>) result.sourceSummary().get("scanMetrics");
    }

    private static void assertRelation(SourceEvidenceCollector.ScanResult result, String schema, String table,
                                       String file, int line, String kind, boolean tentative) {
        assertThat(result.relations()).anySatisfy(relation -> assertThat(relation)
                .containsEntry("schema", schema).containsEntry("table", table).containsEntry("file", file)
                .containsEntry("line", line).containsEntry("kind", kind).containsEntry("tentative", tentative));
    }
}
