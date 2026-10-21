package com.doenaai.analysis.service;

import com.doenaai.analysis.SourceEvidenceVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SourceConnectionCollectorTest {
    @TempDir Path checkout;

    @Test void indexesActualFilesWithoutAnExtensionCandidateListAndRetainsAmbiguity() {
        var collector = new SourceConnectionCollector();
        String page = "import { run } from './Store';\nrun();";
        collector.inspect("src/Page.jsx", "JavaScript", page, page);
        collector.inspect("src/Store.custom", "Other", "", "");
        assertThat(collector.build().get("links").toString()).contains("src/Store.custom", "tentative=true");

        var ambiguous = new SourceConnectionCollector();
        ambiguous.inspect("src/Page.tsx", "TypeScript", page, page);
        ambiguous.inspect("src/Store.js", "JavaScript", "", "");
        ambiguous.inspect("src/Store.py", "Python", "", "");
        assertThat((List<?>) ambiguous.build().get("links")).isEmpty();
        assertThat(ambiguous.build().get("unresolvedReferences").toString()).contains("AMBIGUOUS", "src/Store.js", "src/Store.py");
    }

    @Test void collectsReactAndPythonImportsAndActualSqlWithoutAssumingTheSameBackendLanguage() throws Exception {
        write("web/src/Page.jsx", "import { fetchRecords } from './recordApi';\nexport const Page = () => fetchRecords();");
        write("web/src/recordApi.tsx", "export const fetchRecords = () => client.get('/records');");
        write("server/app/routes.py", "from .service import RecordService\n@app.get('/records')\ndef listing():\n    return RecordService().read()\n");
        write("server/app/service.py", "from .store import RecordStore\nclass RecordService:\n    def read(self):\n        return RecordStore().read()\n");
        write("server/app/store.py", "class RecordStore:\n    def read(self):\n        return cursor.execute('select * from example.table_example')\n");
        var scan = new SourceEvidenceCollector().collect(checkout, Map.of("tables", List.of(Map.of("schema", "example", "name", "table_example"))));
        assertThat(scan.sourceSummary()).containsEntry("collectorVersion", SourceEvidenceVersion.CURRENT);
        var connections = (Map<?, ?>) scan.sourceSummary().get("sourceConnections");
        String links = connections.get("links").toString();
        assertThat(links).contains("web/src/Page.jsx", "web/src/recordApi.tsx", "server/app/service.py", "server/app/store.py");
        assertThat(connections.get("routes").toString()).contains("/records");
        assertThat(scan.relations().toString()).contains("example", "table_example", "server/app/store.py");
    }

    @Test void usesActualJavaTypeReferencesRatherThanRepositoryFilenames() throws Exception {
        write("src/example/Endpoint.java", "package example;\nclass Endpoint {\n private final Storage storage = null;\n}\n");
        write("src/example/Storage.java", "package example;\nclass Storage {}\n");
        write("src/example/UnusedRepository.java", "package example;\nclass UnusedRepository {}\n");
        var scan = new SourceEvidenceCollector().collect(checkout, Map.of());
        String links = ((Map<?, ?>) scan.sourceSummary().get("sourceConnections")).get("links").toString();
        assertThat(links).contains("src/example/Endpoint.java", "src/example/Storage.java").doesNotContain("UnusedRepository");
    }

    @Test void doesNotConnectAJavaTypeToAnUnrelatedPythonClassOfTheSameName() throws Exception {
        write("src/Endpoint.java", "class Endpoint { Storage storage; }");
        write("src/store.py", "class Storage:\n    pass\n");
        var scan = new SourceEvidenceCollector().collect(checkout, Map.of());
        assertThat((List<?>) ((Map<?, ?>) scan.sourceSummary().get("sourceConnections")).get("links")).isEmpty();
    }

    @Test void doesNotReadRoutesFromStringsOrExposeQuerySecrets() throws Exception {
        write("src/page.js", "const text = \"client.get('/not-a-call')\";\nclient.get('/records?token=private-value');\n");
        var scan = new SourceEvidenceCollector().collect(checkout, Map.of());
        assertThat(((Map<?, ?>) scan.sourceSummary().get("sourceConnections")).get("routes").toString())
                .doesNotContain("not-a-call", "private-value", "token");
    }

    private void write(String file, String content) throws Exception {
        var path = checkout.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
