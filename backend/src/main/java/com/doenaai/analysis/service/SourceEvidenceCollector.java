package com.doenaai.analysis.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, non-executing source inspection. Only allowlisted metadata leaves this class. */
@Service
public class SourceEvidenceCollector {
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".mvn", ".venv", "venv",
            "node_modules", "vendor", "build", "dist", "target", "out", "coverage", "generated",
            "generated-sources", "__pycache__", ".next", ".nuxt", ".cache", ".pytest_cache", "pods",
            "bin", "obj", "artifacts", "datasets", "checkpoints", "secrets", ".ssh", ".aws");
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("kts", "Kotlin"),
            Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"), Map.entry("mjs", "JavaScript"),
            Map.entry("cjs", "JavaScript"), Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"),
            Map.entry("py", "Python"), Map.entry("rb", "Ruby"), Map.entry("php", "PHP"),
            Map.entry("go", "Go"), Map.entry("rs", "Rust"), Map.entry("cs", "C#"), Map.entry("fs", "F#"),
            Map.entry("dart", "Dart"), Map.entry("swift", "Swift"), Map.entry("scala", "Scala"),
            Map.entry("groovy", "Groovy"), Map.entry("ex", "Elixir"), Map.entry("exs", "Elixir"),
            Map.entry("clj", "Clojure"), Map.entry("cljs", "ClojureScript"), Map.entry("c", "C"),
            Map.entry("h", "C/C++ Header"), Map.entry("cc", "C++"), Map.entry("cpp", "C++"),
            Map.entry("hpp", "C++"), Map.entry("m", "Objective-C"), Map.entry("mm", "Objective-C++"),
            Map.entry("vue", "Vue"), Map.entry("svelte", "Svelte"), Map.entry("html", "HTML"),
            Map.entry("jsp", "JSP"), Map.entry("razor", "Razor"), Map.entry("sql", "SQL"), Map.entry("xml", "XML"),
            Map.entry("prisma", "Prisma"), Map.entry("r", "R"), Map.entry("lua", "Lua"));
    private static final Map<String, String> MANIFESTS = Map.ofEntries(
            Map.entry("package.json", "npm"), Map.entry("composer.json", "Composer"),
            Map.entry("pom.xml", "Maven"), Map.entry("build.gradle", "Gradle"),
            Map.entry("build.gradle.kts", "Gradle"), Map.entry("requirements.txt", "pip"),
            Map.entry("pyproject.toml", "Python packaging"), Map.entry("pipfile", "Pipenv"),
            Map.entry("gemfile", "Bundler"), Map.entry("go.mod", "Go modules"),
            Map.entry("cargo.toml", "Cargo"), Map.entry("pubspec.yaml", "Pub"),
            Map.entry("package.swift", "Swift Package Manager"), Map.entry("mix.exs", "Mix"));
    private static final List<Technology> TECHNOLOGIES = List.of(
            tech("React", "JavaScript", "FRONTEND", "react"), tech("Vue", "JavaScript", "FRONTEND", "vue"),
            tech("Angular", "JavaScript", "FRONTEND", "@angular/core"),
            tech("Next.js", "JavaScript", "FULLSTACK", "next"), tech("Nuxt", "JavaScript", "FULLSTACK", "nuxt"),
            tech("Svelte", "JavaScript", "FRONTEND", "svelte"), tech("Express", "JavaScript", "BACKEND", "express"),
            tech("Fastify", "JavaScript", "BACKEND", "fastify"), tech("NestJS", "JavaScript", "BACKEND", "@nestjs/core"),
            tech("React Native", "JavaScript", "MOBILE", "react-native"),
            tech("Prisma", "JavaScript", "ORM", "@prisma/client", "prisma"),
            tech("TypeORM", "JavaScript", "ORM", "typeorm"), tech("Sequelize", "JavaScript", "ORM", "sequelize"),
            tech("Spring Boot", "JVM", "BACKEND", "org.springframework.boot", "spring-boot-starter"),
            tech("Spring", "JVM", "BACKEND", "spring-context", "spring-web"),
            tech("Quarkus", "JVM", "BACKEND", "io.quarkus", "quarkus-core"),
            tech("Micronaut", "JVM", "BACKEND", "io.micronaut", "micronaut-runtime"),
            tech("Hibernate", "JVM", "ORM", "hibernate-core"),
            tech("Django", "Python", "BACKEND", "django"), tech("Flask", "Python", "BACKEND", "flask"),
            tech("FastAPI", "Python", "BACKEND", "fastapi"), tech("SQLAlchemy", "Python", "ORM", "sqlalchemy"),
            tech("PyTorch", "Python", "AI_ML", "torch"), tech("TensorFlow", "Python", "AI_ML", "tensorflow"),
            tech("Rails", "Ruby", "FULLSTACK", "rails"), tech("Sinatra", "Ruby", "BACKEND", "sinatra"),
            tech("Gin", "Go", "BACKEND", "github.com/gin-gonic/gin"),
            tech("Echo", "Go", "BACKEND", "github.com/labstack/echo"), tech("GORM", "Go", "ORM", "gorm.io/gorm"),
            tech("Axum", "Rust", "BACKEND", "axum"), tech("Actix Web", "Rust", "BACKEND", "actix-web"),
            tech("Rocket", "Rust", "BACKEND", "rocket"), tech("Diesel", "Rust", "ORM", "diesel"),
            tech("Laravel", "PHP", "FULLSTACK", "laravel/framework"),
            tech("Symfony", "PHP", "BACKEND", "symfony/framework-bundle"),
            tech("ASP.NET Core", ".NET", "BACKEND", "microsoft.aspnetcore.app", "microsoft.net.sdk.web"),
            tech("Entity Framework Core", ".NET", "ORM", "microsoft.entityframeworkcore"),
            tech("Flutter", "Dart", "MOBILE", "flutter"), tech("Phoenix", "Elixir", "BACKEND", "phoenix"),
            tech("Vapor", "Swift", "BACKEND", "vapor/vapor"));
    private static final String IDENTIFIER = "(?:\"[^\"\\r\\n]{1,128}\"|`[^`\\r\\n]{1,128}`|\\[[^]\\r\\n]{1,128}]|[A-Za-z_][A-Za-z0-9_$]{0,127})";
    private static final String TABLE_REFERENCE = IDENTIFIER + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?";
    private static final Pattern SQL_REFERENCE = Pattern.compile(
            "(?i)\\b(from|join|insert\\s+into|update|delete\\s+from|merge\\s+into)\\s+(" + TABLE_REFERENCE + ")(?![A-Za-z0-9_$]|\\s*\\.)");
    private static final Pattern SELECT_WORD = Pattern.compile("(?i)\\bselect\\b");
    private static final Pattern SET_KEYWORD = Pattern.compile("(?i)\\s+set\\b");
    private static final Pattern TABLE_ANNOTATION = Pattern.compile(
            "(?s)@(?:[A-Za-z_][\\w]*\\.)*Table\\s*\\(([^)]{0,1000})\\)|\\[Table\\s*\\(([^)]{0,1000})\\)]");
    private static final Pattern ORM_ASSIGNMENT = Pattern.compile(
            "\\b(?:__tablename__|db_table|tableName|self\\.table_name)\\s*[:=]\\s*['\"](" + TABLE_REFERENCE + ")[\"']");
    private static final Pattern ENTITY_ANNOTATION = Pattern.compile("(?s)@Entity\\s*\\(([^)]{0,1000})\\)");
    private static final Pattern PRISMA_MODEL = Pattern.compile("(?s)\\bmodel\\s+\\w+\\s*\\{([^}]{0,16000})}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int maxFiles;
    private final int maxFileBytes;
    private final long maxTotalBytes;
    private final int maxEntries;
    private final int maxRelations;

    public SourceEvidenceCollector() {
        this(3_000, 262_144, 16_777_216L, 20_000, 10_000);
    }

    SourceEvidenceCollector(int maxFiles, int maxFileBytes, long maxTotalBytes, int maxEntries, int maxRelations) {
        if (maxFiles < 1 || maxFileBytes < 1 || maxTotalBytes < 1 || maxEntries < 1 || maxRelations < 1) {
            throw new IllegalArgumentException("Scan limits must be positive");
        }
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
        this.maxTotalBytes = maxTotalBytes;
        this.maxEntries = maxEntries;
        this.maxRelations = maxRelations;
    }

    public record ScanResult(Map<String, Object> sourceSummary, List<Map<String, Object>> relations) { }

    public ScanResult collect(Path checkout, Map<String, Object> databaseSummary) {
        State state = new State(databaseTables(databaseSummary));
        Path requestedRoot = checkout.toAbsolutePath().normalize();
        if (!Files.isDirectory(requestedRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(requestedRoot)) {
            throw new IllegalArgumentException("Checkout must be a regular directory, not a symbolic link");
        }
        final Path root;
        try { root = requestedRoot.toRealPath(); }
        catch (IOException exception) { throw new IllegalArgumentException("Checkout could not be inspected"); }
        try {
            Files.walkFileTree(root, Set.of(), 25, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (++state.visitedEntries > maxEntries) return stop(state, "Repository entry limit reached.");
                    if (!directory.equals(root) && EXCLUDED_DIRECTORIES.contains(lowerName(directory))) {
                        state.excludedFiles++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (++state.visitedEntries > maxEntries) return stop(state, "Repository entry limit reached.");
                    if (attributes.isDirectory()) {
                        state.truncated = true;
                        state.warnings.add("Directory depth limit reached.");
                        return FileVisitResult.CONTINUE;
                    }
                    String name = lowerName(file);
                    String language = LANGUAGES.get(extension(name));
                    boolean manifest = isManifest(name);
                    if (!attributes.isRegularFile() || excludedFile(name) || (!manifest && language == null)) {
                        state.excludedFiles++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (state.scannedFiles >= maxFiles) return stop(state, "Source file count limit reached.");
                    if (attributes.size() > maxFileBytes) {
                        state.truncated = true;
                        state.warnings.add("Files exceeding the per-file byte limit were excluded.");
                        state.excludedFiles++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (state.scannedBytes + attributes.size() > maxTotalBytes) return stop(state, "Total source byte limit reached.");
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (relative.length() > 1024 || relative.chars().anyMatch(Character::isISOControl)) {
                        state.excludedFiles++;
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        byte[] bytes = readBounded(file, (int) Math.min(maxFileBytes, maxTotalBytes - state.scannedBytes));
                        state.scannedBytes += bytes.length;
                        state.scannedFiles++;
                        String content = decodeText(bytes);
                        if (content == null) {
                            state.excludedFiles++;
                            state.warnings.add("Binary or non-UTF-8 source files were excluded.");
                            return FileVisitResult.CONTINUE;
                        }
                        if (language != null) state.languages.add(language);
                        Lexed lexed = lex(content, language);
                        state.components.add(Map.of("path", relative, "language", language == null ? "Manifest" : language,
                                "type", componentType(relative, language, lexed.code(), manifest), "line", 1));
                        if (manifest) inspectManifest(relative, name, content, state);
                        if (language != null) inspectRelations(relative, content, language, lexed, state);
                    } catch (LimitExceededException exception) {
                        state.truncated = true;
                        state.warnings.add("A file grew beyond a source byte limit and was excluded.");
                    } catch (IOException | SecurityException exception) {
                        state.warnings.add("Some source files could not be read.");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    state.warnings.add("Some repository paths could not be inspected.");
                    return ++state.visitedEntries > maxEntries ? stop(state, "Repository entry limit reached.") : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            state.truncated = true;
            state.warnings.add("Repository traversal was incomplete.");
        }
        state.components.sort(Comparator.comparing(component -> component.get("path").toString()));
        List<Map<String, Object>> frameworks = state.frameworks.values().stream().map(FrameworkFact::asMap).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("languages", List.copyOf(state.languages));
        summary.put("frameworks", frameworks);
        summary.put("technologies", frameworks);
        summary.put("buildTools", List.copyOf(state.buildTools));
        summary.put("components", List.copyOf(state.components));
        summary.put("detectionWarnings", List.copyOf(state.warnings));
        summary.put("scanMetrics", Map.of("scannedFileCount", state.scannedFiles, "scannedBytes", state.scannedBytes,
                "visitedEntryCount", state.visitedEntries, "excludedFileCount", state.excludedFiles,
                "truncated", state.truncated, "maxFiles", maxFiles, "maxFileBytes", maxFileBytes,
                "maxTotalBytes", maxTotalBytes, "maxRelations", maxRelations));
        summary.put("limitations", List.of(
                "Static lexical evidence only; declarations do not prove runtime usage.",
                "Column-level usage, dynamic SQL, implicit ORM naming and runtime schema/search-path selection are not fully resolved.",
                "Unqualified table names are tentative; all matching database schemas are retained.",
                "Frameworks in non-JSON/XML manifests are declaration-shaped heuristic matches.",
                "No complete call graph or transitive impact graph is inferred.",
                "Only supported UTF-8 source files and manifests within scan limits are inspected."));
        return new ScanResult(Map.copyOf(summary), List.copyOf(state.relations));
    }

    private void inspectRelations(String file, String content, String language, Lexed lexed, State state) {
        int[] lineOffsets = lineOffsets(content);
        if (language.equals("XML")) {
            // Text-only MyBatis statements: DTDs/entities are neither parsed nor expanded, and no URL is opened.
            String xml = maskPattern(content, Pattern.compile("(?s)<!--.*?-->"));
            Matcher statements = Pattern.compile("(?is)<(select|insert|update|delete)\\b[^>]{0,1000}>(.*?)</\\1\\s*>").matcher(xml);
            while (statements.find()) {
                String statement = maskPattern(statements.group(2), Pattern.compile("(?s)<!\\[CDATA\\[|]]>|<[^>]*>"));
                inspectSql(file, statement, lineAt(lineOffsets, statements.start(2)), state);
            }
            return;
        } else if (language.equals("SQL")) inspectSql(file, content, 1, state);
        else for (Literal literal : lexed.literals()) inspectSql(file,
                literal.value().replace("\\\"", "\"").replace("\\`", "`"), lineAt(lineOffsets, literal.start()), state);

        Matcher annotation = TABLE_ANNOTATION.matcher(lexed.commentsRemoved());
        while (annotation.find()) {
            if (lexed.inLiteral().get(annotation.start())) continue;
            String arguments = annotation.group(1) == null ? annotation.group(2) : annotation.group(1);
            String name = argument(arguments, "name");
            if (name == null && annotation.group(2) != null) name = firstString(arguments);
            addOrm(file, name, argument(arguments, "schema"), lineAt(lineOffsets, annotation.start()), state);
        }
        Matcher assignment = ORM_ASSIGNMENT.matcher(lexed.commentsRemoved());
        while (assignment.find()) {
            if (!lexed.inLiteral().get(assignment.start())) {
                addOrm(file, assignment.group(1), null, lineAt(lineOffsets, assignment.start()), state);
            }
        }
        Matcher entity = ENTITY_ANNOTATION.matcher(lexed.commentsRemoved());
        while (entity.find()) {
            if (!Set.of("JavaScript", "TypeScript").contains(language)) continue;
            if (lexed.inLiteral().get(entity.start())) continue;
            String arguments = entity.group(1);
            String name = argument(arguments, "name");
            if (name == null) name = firstString(arguments.stripLeading());
            addOrm(file, name, argument(arguments, "schema"), lineAt(lineOffsets, entity.start()), state);
        }
        if (language.equals("Prisma")) {
            Matcher model = PRISMA_MODEL.matcher(lexed.commentsRemoved());
            while (model.find()) {
                Matcher table = Pattern.compile("@@map\\s*\\(\\s*\"([^\"]{1,128})\"\\s*\\)").matcher(model.group(1));
                Matcher schema = Pattern.compile("@@schema\\s*\\(\\s*\"([^\"]{1,128})\"\\s*\\)").matcher(model.group(1));
                if (table.find()) addOrm(file, table.group(1), schema.find() ? schema.group(1) : null,
                        lineAt(lineOffsets, model.start(1) + table.start()), state);
            }
        }
    }

    private void inspectSql(String file, String sql, int firstLine, State state) {
        // Remove SQL value literals/comments without changing offsets; quoted identifiers remain intact.
        String safeSql = maskSqlValuesAndComments(sql);
        int[] lineOffsets = lineOffsets(sql);
        Matcher references = SQL_REFERENCE.matcher(safeSql);
        Matcher selects = SELECT_WORD.matcher(safeSql);
        boolean hasNextSelect = selects.find();
        int lastSelect = -1;
        int statementStart = 0;
        int checkedUntil = 0;
        while (references.find()) {
            if (state.relations.size() >= maxRelations) {
                state.truncated = true;
                state.warnings.add("Source relationship limit reached.");
                break;
            }
            for (int index = checkedUntil; index < references.start(); index++) {
                if (safeSql.charAt(index) == ';') statementStart = index + 1;
            }
            checkedUntil = references.end();
            while (hasNextSelect && selects.start() < references.start()) {
                lastSelect = selects.start();
                hasNextSelect = selects.find();
            }
            String operation = references.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            String kind;
            if (operation.equals("from") || operation.equals("join")) {
                if (lastSelect < statementStart) continue;
                kind = "SQL_SELECT";
            } else if (operation.equals("update")) {
                if (!SET_KEYWORD.matcher(safeSql).region(references.end(), safeSql.length()).lookingAt()) continue;
                kind = "SQL_UPDATE";
            } else if (operation.equals("delete from")) kind = "SQL_DELETE";
            else if (operation.equals("merge into")) kind = "SQL_MERGE";
            else kind = "SQL_INSERT";
            matchTable(file, references.group(2), firstLine + lineAt(lineOffsets, references.start(2)) - 1, kind, state);
        }
    }

    private void addOrm(String file, String name, String schema, int line, State state) {
        if (name == null) return;
        matchTable(file, schema == null ? name : quote(schema) + "." + quote(name), line, "ORM_TABLE", state);
    }

    private void matchTable(String file, String reference, int line, String kind, State state) {
        reference = reference.strip();
        Matcher identifiers = Pattern.compile(IDENTIFIER).matcher(reference);
        List<String> parts = new ArrayList<>();
        List<Boolean> quoted = new ArrayList<>();
        int end = 0;
        while (identifiers.find()) {
            String between = reference.substring(end, identifiers.start()).strip();
            if ((!parts.isEmpty() && !between.equals(".")) || (parts.isEmpty() && !between.isEmpty())) return;
            parts.add(unquote(identifiers.group()));
            quoted.add("\"`[".indexOf(identifiers.group().charAt(0)) >= 0);
            end = identifiers.end();
        }
        if (parts.isEmpty() || parts.size() > 2 || !reference.substring(end).isBlank()) return;
        String tableName = parts.getLast();
        boolean qualified = parts.size() == 2;
        List<DatabaseTable> matches = state.tables.stream().filter(table -> identifierMatches(table.name(), tableName, quoted.getLast())
                && (!qualified || identifierMatches(table.schema(), parts.getFirst(), quoted.getFirst()))).toList();
        for (DatabaseTable table : matches) {
            String key = file + ":" + line + ":" + kind + ":" + table.schema() + ":" + table.name();
            if (state.relationKeys.contains(key)) continue;
            if (state.relations.size() >= maxRelations) {
                state.truncated = true;
                state.warnings.add("Source relationship limit reached.");
                return;
            }
            state.relationKeys.add(key);
            boolean tentative = !qualified || matches.size() > 1 || !table.name().equals(tableName)
                    || (qualified && !table.schema().equals(parts.getFirst()));
            state.relations.add(Map.of("schema", table.schema(), "table", table.name(), "file", file,
                    "line", line, "kind", kind, "confidence", tentative ? 0.6 : 0.95,
                    "tentative", tentative, "ambiguous", matches.size() > 1, "evidenceType", "STATIC_EXPLICIT"));
        }
    }

    private void inspectManifest(String file, String name, String content, State state) {
        state.buildTools.add(MANIFESTS.getOrDefault(name, ".NET SDK"));
        Set<String> dependencies = new LinkedHashSet<>();
        boolean structured = name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".csproj") || name.endsWith(".fsproj");
        try {
            if (name.equals("package.json") || name.equals("composer.json")) {
                JsonNode root = JSON.readTree(content);
                for (String section : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies", "require", "require-dev")) {
                    JsonNode values = root.path(section);
                    if (values.isObject()) values.propertyNames().forEach(dependency -> dependencies.add(dependency.toLowerCase(Locale.ROOT)));
                }
            } else if (name.equals("pom.xml") || name.endsWith(".csproj") || name.endsWith(".fsproj")) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                var builder = factory.newDocumentBuilder();
                builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
                var document = builder.parse(new InputSource(new StringReader(content)));
                for (String tag : List.of("artifactId", "groupId", "PackageReference", "FrameworkReference", "Project")) {
                    NodeList nodes = document.getElementsByTagName(tag);
                    for (int i = 0; i < nodes.getLength(); i++) {
                        var node = nodes.item(i);
                        dependencies.add(node.getTextContent().strip().toLowerCase(Locale.ROOT));
                        for (String attribute : List.of("Include", "Sdk")) {
                            var value = node.getAttributes().getNamedItem(attribute);
                            if (value != null) dependencies.add(value.getNodeValue().toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } else {
                String searchable = lex(content, name.endsWith("gradle") ? "Java" : "Python").commentsRemoved().toLowerCase(Locale.ROOT);
                for (Technology technology : TECHNOLOGIES) for (String dependency : technology.dependencies()) {
                    // Bounded declaration-shaped matches, never execute build scripts or interpolate dependency values.
                    Pattern declaration = Pattern.compile("(?m)(?:^\\s*|['\"(,:=\\s])" + Pattern.quote(dependency)
                            + "(?=[\\s'\":=<>~!\\[),/]|$)");
                    if (declaration.matcher(searchable).find()) dependencies.add(dependency);
                }
            }
            for (Technology technology : TECHNOLOGIES) {
                boolean matched = technology.dependencies().stream().anyMatch(dependency -> dependencies.stream().anyMatch(value ->
                        value.equals(dependency) || (technology.ecosystem().equals("JVM")
                                && (value.startsWith(dependency + "-") || value.startsWith(dependency + ":")))
                                || (technology.ecosystem().equals(".NET") && value.startsWith(dependency + "."))));
                if (matched) {
                    FrameworkFact fact = state.frameworks.computeIfAbsent(technology.name(), ignored -> new FrameworkFact(technology));
                    fact.confidence = Math.max(fact.confidence, structured ? 0.9 : 0.7);
                    if (fact.evidences.size() < 20) fact.evidences.add(Map.of("file", file, "evidenceType",
                            structured ? "MANIFEST_DECLARATION" : "MANIFEST_HEURISTIC"));
                    else {
                        state.truncated = true;
                        state.warnings.add("Framework evidence per technology is limited to twenty manifests.");
                    }
                }
            }
        } catch (Exception exception) {
            // Never include parser diagnostics: they can echo secrets or complete source lines.
            state.warnings.add("Some manifests were invalid or used unsupported syntax.");
        }
    }

    private static Lexed lex(String content, String language) {
        char[] cleaned = content.toCharArray();
        char[] code = content.toCharArray();
        List<Literal> literals = new ArrayList<>();
        BitSet inLiteral = new BitSet(content.length());
        boolean hashComments = Set.of("Python", "Ruby", "PHP", "R", "Elixir", "Prisma").contains(language == null ? "" : language);
        for (int i = 0; i < content.length();) {
            char current = content.charAt(i);
            boolean lineComment = (hashComments && current == '#') || content.startsWith("//", i)
                    || ("SQL".equals(language) && content.startsWith("--", i));
            if (lineComment || content.startsWith("/*", i)) {
                int end = lineComment ? content.indexOf('\n', i) : content.indexOf("*/", i + 2);
                end = end < 0 ? content.length() : lineComment ? end : end + 2;
                blank(cleaned, i, end); blank(code, i, end); i = end;
            } else if (current == '\'' || current == '"' || current == '`') {
                int width = i + 2 < content.length() && content.charAt(i + 1) == current && content.charAt(i + 2) == current ? 3 : 1;
                int start = i;
                i += width;
                int valueStart = i;
                String delimiter = String.valueOf(current).repeat(width);
                while (i < content.length() && !content.startsWith(delimiter, i)) {
                    if (content.charAt(i) == '\\') i = Math.min(content.length(), i + 2);
                    else i++;
                }
                int valueEnd = i;
                i = Math.min(content.length(), i + width);
                literals.add(new Literal(content.substring(valueStart, valueEnd), valueStart));
                inLiteral.set(start, i); blank(code, start, i);
            } else i++;
        }
        return new Lexed(new String(cleaned), new String(code), literals, inLiteral);
    }

    private static String maskSqlValuesAndComments(String content) {
        char[] result = content.toCharArray();
        for (int i = 0; i < content.length();) {
            if (content.startsWith("--", i) || content.startsWith("/*", i)) {
                boolean line = content.startsWith("--", i);
                int end = line ? content.indexOf('\n', i) : content.indexOf("*/", i + 2);
                end = end < 0 ? content.length() : line ? end : end + 2;
                blank(result, i, end); i = end;
            } else if (content.charAt(i) == '\'') {
                int start = i++;
                while (i < content.length()) {
                    if (content.charAt(i) == '\\') i = Math.min(content.length(), i + 2);
                    else if (content.charAt(i++) == '\'') {
                        if (i < content.length() && content.charAt(i) == '\'') i++;
                        else break;
                    }
                }
                blank(result, start, i);
            } else if (content.charAt(i) == '"' || content.charAt(i) == '`' || content.charAt(i) == '[') {
                char end = content.charAt(i++) == '[' ? ']' : content.charAt(i - 1);
                while (i < content.length() && content.charAt(i) != end) i++;
                i = Math.min(content.length(), i + 1);
            } else i++;
        }
        return new String(result);
    }

    private static void blank(char[] content, int start, int end) {
        for (int i = start; i < end; i++) if (content[i] != '\n' && content[i] != '\r') content[i] = ' ';
    }

    private static String maskPattern(String content, Pattern pattern) {
        char[] masked = content.toCharArray();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) blank(masked, matcher.start(), matcher.end());
        return new String(masked);
    }

    private static byte[] readBounded(Path file, int limit) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(limit + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            if (buffer.position() > limit) throw new LimitExceededException();
            return Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    private static String decodeText(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) { return null; }
    }

    private static boolean excludedFile(String name) {
        return name.startsWith(".env") || name.startsWith("application-local") || name.startsWith("application-secret")
                || name.startsWith("application-prod") || name.contains("credential") || name.contains("secret")
                || name.equals("id_rsa") || name.equals("id_ed25519") || name.equals(".npmrc") || name.equals(".pypirc")
                || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".jks")
                || name.endsWith(".min.js") || name.endsWith(".min.css") || name.endsWith(".generated.java")
                || name.endsWith(".g.cs") || name.endsWith(".g.dart") || name.endsWith(".freezed.dart");
    }

    private static String componentType(String file, String language, String code, boolean manifest) {
        String lower = file.toLowerCase(Locale.ROOT);
        if (manifest) return "MANIFEST";
        if (lower.contains("/test/") || lower.contains("/tests/") || lower.startsWith("tests/")
                || lower.matches(".*(?:test|spec)\\.[^.]+$")) return "TEST";
        if (lower.contains("migration") && "SQL".equals(language)) return "MIGRATION";
        if (code.contains("@RestController") || code.contains("@Controller") || lower.matches(".*controller\\.[^.]+$")) return "CONTROLLER";
        if (code.contains("@Repository") || lower.matches(".*repository\\.[^.]+$")) return "REPOSITORY";
        if (code.contains("@Service") || lower.matches(".*service\\.[^.]+$")) return "SERVICE";
        if (code.contains("@Entity") || code.contains("__tablename__") || "Prisma".equals(language)) return "ENTITY";
        if (Set.of("Vue", "Svelte", "HTML", "JSP", "Razor").contains(language == null ? "" : language)) return "VIEW";
        return "SOURCE";
    }

    private static List<DatabaseTable> databaseTables(Map<String, Object> summary) {
        Set<DatabaseTable> result = new LinkedHashSet<>();
        if (summary != null && summary.get("tables") instanceof List<?> tables) for (Object item : tables) {
            if (item instanceof Map<?, ?> table && table.get("name") instanceof String name && table.get("schema") instanceof String schema
                    && !name.isBlank() && name.length() <= 128 && schema.length() <= 128
                    && name.chars().noneMatch(Character::isISOControl) && schema.chars().noneMatch(Character::isISOControl)) {
                result.add(new DatabaseTable(schema, name));
            }
        }
        return List.copyOf(result);
    }

    private static String argument(String arguments, String name) {
        Matcher matcher = Pattern.compile("(?i)\\b" + name + "\\s*[:=]\\s*['\"]([^'\"\\r\\n]{1,128})['\"]").matcher(arguments);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String firstString(String value) {
        Matcher matcher = Pattern.compile("^\\s*['\"]([^'\"\\r\\n]{1,128})['\"]").matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "") + "\""; }
    private static boolean identifierMatches(String databaseName, String sourceName, boolean quoted) {
        return quoted ? databaseName.equals(sourceName) : databaseName.equalsIgnoreCase(sourceName);
    }
    private static String unquote(String identifier) {
        return "\"`[".indexOf(identifier.charAt(0)) >= 0 ? identifier.substring(1, identifier.length() - 1) : identifier;
    }
    private static String lowerName(Path path) { return path.getFileName().toString().toLowerCase(Locale.ROOT); }
    private static String extension(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    private static boolean isManifest(String name) { return MANIFESTS.containsKey(name) || name.endsWith(".csproj") || name.endsWith(".fsproj"); }
    private static int[] lineOffsets(String content) {
        return java.util.stream.IntStream.concat(java.util.stream.IntStream.of(0),
                java.util.stream.IntStream.range(0, content.length()).filter(i -> content.charAt(i) == '\n').map(i -> i + 1)).toArray();
    }
    private static int lineAt(int[] offsets, int index) {
        int match = Arrays.binarySearch(offsets, index);
        return match >= 0 ? match + 1 : -match - 1;
    }
    private static FileVisitResult stop(State state, String warning) {
        state.truncated = true; state.warnings.add(warning); return FileVisitResult.TERMINATE;
    }
    private static Technology tech(String name, String ecosystem, String category, String... dependencies) {
        return new Technology(name, ecosystem, category, List.of(dependencies));
    }

    private record Technology(String name, String ecosystem, String category, List<String> dependencies) { }
    private record DatabaseTable(String schema, String name) { }
    private record Literal(String value, int start) { }
    private record Lexed(String commentsRemoved, String code, List<Literal> literals, BitSet inLiteral) { }
    private static final class LimitExceededException extends IOException { }
    private static final class FrameworkFact {
        private final Technology technology;
        private final List<Map<String, Object>> evidences = new ArrayList<>();
        private double confidence;
        private FrameworkFact(Technology technology) { this.technology = technology; }
        private Map<String, Object> asMap() {
            return Map.of("name", technology.name(), "ecosystem", technology.ecosystem(), "category", technology.category(),
                    "confidence", confidence, "evidences", List.copyOf(evidences));
        }
    }
    private static final class State {
        private final List<DatabaseTable> tables;
        private final Set<String> languages = new TreeSet<>();
        private final Set<String> buildTools = new TreeSet<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private final Map<String, FrameworkFact> frameworks = new LinkedHashMap<>();
        private final List<Map<String, Object>> components = new ArrayList<>();
        private final List<Map<String, Object>> relations = new ArrayList<>();
        private final Set<String> relationKeys = new LinkedHashSet<>();
        private int visitedEntries;
        private int scannedFiles;
        private int excludedFiles;
        private long scannedBytes;
        private boolean truncated;
        private State(List<DatabaseTable> tables) { this.tables = tables; }
    }
}
