package com.doenaai.analysis.service;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** 읽은 소스의 타입 참조·가져오기·주소 선언만 수집한다. 실행 경로나 완전한 호출 그래프는 증명하지 않는다. */
final class SourceConnectionCollector {
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|record|enum)\\s+([A-Za-z_]\\w*)");
    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([A-Za-z_][\\w.]*)\\s*;");
    private static final Pattern JAVA_IMPORT = Pattern.compile("\\bimport\\s+([A-Za-z_][\\w.]*)\\s*;");
    private static final Pattern JS_IMPORT = Pattern.compile("\\bimport\\s+([^;]{1,500}?)\\s+from\\s*['\"]([^'\"\\r\\n]{1,300})['\"]");
    private static final Pattern ROUTE = Pattern.compile("@(?:Get|Post|Put|Patch|Delete|Request)Mapping\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?[\"]([^\"\\r\\n]{1,300})[\"]");
    private static final Pattern HTTP = Pattern.compile("\\b[A-Za-z_]\\w*\\.(get|post|put|patch|delete)\\s*\\(\\s*['\"`]([^'\"`\\r\\n]{1,300})['\"`]");
    private final List<Unit> units = new ArrayList<>();
    private final List<Map<String, Object>> routes = new ArrayList<>();
    private final Set<String> inspectedLanguages = new TreeSet<>();
    private final Set<String> unsupportedLanguages = new TreeSet<>();
    private boolean truncated;

    void inspect(String file, String language, String code, String commentsRemoved) {
        if (!Set.of("Java", "JavaScript", "TypeScript", "Vue", "Svelte", "Python").contains(language)) {
            unsupportedLanguages.add(language);
            units.add(new Unit(file, language, "", Map.of(), Map.of(), Map.of()));
            return;
        }
        inspectedLanguages.add(language);
        var declarations = new LinkedHashMap<String, Integer>();
        var imports = new LinkedHashMap<String, String>();
        String namespace = "";
        if (language.equals("Java")) {
            var pkg = PACKAGE.matcher(code);
            if (pkg.find()) namespace = pkg.group(1);
            var types = TYPE.matcher(code);
            while (types.find() && declarations.size() < 20) declarations.put(types.group(1), line(code, types.start()));
            var imported = JAVA_IMPORT.matcher(code);
            while (imported.find() && imports.size() < 100) imports.put(imported.group(1).substring(imported.group(1).lastIndexOf('.') + 1), imported.group(1));
        }
        if (language.equals("Python")) {
            var definitions = Pattern.compile("\\b(?:class|def)\\s+([A-Za-z_]\\w*)").matcher(code);
            while (definitions.find() && declarations.size() < 50) declarations.put(definitions.group(1), line(code, definitions.start()));
            var from = Pattern.compile("(?m)^[ \\t]*from\\s+([.A-Za-z_][.\\w]*)\\s+import\\s+([^\\r\\n]{1,500})").matcher(code);
            while (from.find() && imports.size() < 100) {
                for (String value : from.group(2).split(",")) {
                    String[] names = value.strip().split("\\s+as\\s+");
                    if (names[0].matches("[A-Za-z_]\\w*")) imports.put(names[names.length - 1], from.group(1) + "#" + names[0]);
                }
            }
            var direct = Pattern.compile("(?m)^[ \\t]*import\\s+([A-Za-z_][.\\w]*)(?:\\s+as\\s+([A-Za-z_]\\w*))?").matcher(code);
            while (direct.find() && imports.size() < 100) imports.put(direct.group(2) == null ? direct.group(1).split("\\.")[0] : direct.group(2), direct.group(1));
        }
        var imported = JS_IMPORT.matcher(commentsRemoved);
        while (imported.find() && imports.size() < 100) {
            if (!code.startsWith("import", imported.start())) continue;
            var names = Pattern.compile("[A-Za-z_]\\w*").matcher(imported.group(1));
            while (names.find()) if (!Set.of("as", "type").contains(names.group())) imports.put(names.group(), imported.group(2));
        }
        char[] masked = code.toCharArray();
        var importLines = Pattern.compile("(?m)^[ \\t]*(?:package|import|from)\\b[^;\\r\\n]*;?").matcher(code);
        while (importLines.find()) Arrays.fill(masked, importLines.start(), importLines.end(), ' ');
        String withoutImports = new String(masked);
        var symbols = new LinkedHashMap<String, Integer>();
        var identifiers = Pattern.compile(language.equals("Java") ? "\\b[A-Z][A-Za-z0-9_]*\\b" : "\\b[A-Za-z_][A-Za-z0-9_]*\\b").matcher(withoutImports);
        while (identifiers.find()) {
            String symbol = identifiers.group();
            if (language.equals("Java") || imports.containsKey(symbol)) {
                if (symbols.size() >= 512) { truncated = true; break; }
                symbols.putIfAbsent(symbol, line(code, identifiers.start()));
            }
        }
        units.add(new Unit(file, language, namespace, declarations, imports, symbols));
        collectRoutes(file, code, commentsRemoved, ROUTE, "API_MAPPING", 1);
        collectRoutes(file, code, commentsRemoved, HTTP, language.equals("Python") ? "PYTHON_CALL_OR_ROUTE" : "HTTP_CALL", 2);
    }

    private void collectRoutes(String file, String code, String text, Pattern pattern, String kind, int group) {
        var matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (code.charAt(matcher.start()) == ' ') continue;
            String route = matcher.group(group);
            // 주소의 쿼리·외부 호스트·원문 값은 수집하지 않는다.
            if (!route.matches("/[A-Za-z0-9_./:{}$-]{0,299}")) continue;
            if (routes.size() >= 150) { truncated = true; break; }
            routes.add(Map.of("file", file, "line", line(text, matcher.start()), "kind", kind, "route", route));
        }
    }

    Map<String, Object> build() {
        var types = new HashMap<String, List<Unit>>();
        var modulePaths = new LinkedHashMap<String, List<Unit>>();
        units.sort(Comparator.comparing(Unit::language).thenComparing(Unit::file));
        for (var unit : units) {
            if (unit.language().equals("Java")) {
                unit.declarations().keySet().forEach(name -> types.computeIfAbsent(unit.namespace() + "." + name, ignored -> new ArrayList<>()).add(unit));
            }
            modulePaths.computeIfAbsent(unit.file(), ignored -> new ArrayList<>()).add(unit);
            String stem = moduleStem(unit.file());
            if (!stem.equals(unit.file())) modulePaths.computeIfAbsent(stem, ignored -> new ArrayList<>()).add(unit);
        }
        var links = new ArrayList<Map<String, Object>>();
        var unresolved = new ArrayList<Map<String, Object>>();
        for (var unit : units) {
            int added = 0;
            for (var entry : unit.symbols().entrySet()) {
                List<Unit> candidates;
                boolean tentative = false;
                if (unit.language().equals("Python")) {
                    String imported = unit.imports().get(entry.getKey());
                    if (imported == null) continue;
                    if (imported.chars().anyMatch(Character::isISOControl)) { truncated = true; continue; }
                    String[] parts = imported.split("#", 2);
                    String module = parts[0];
                    if (module.startsWith(".")) {
                        int dots = 0;
                        while (dots < module.length() && module.charAt(dots) == '.') dots++;
                        Path base = Path.of(unit.file()).getParent();
                        if (base == null) base = Path.of("");
                        for (int step = 1; step < dots; step++) base = base.resolve("..");
                        module = base.resolve(module.substring(dots).replace('.', '/')).normalize().toString().replace('\\', '/');
                    } else module = module.replace('.', '/');
                    String path = module;
                    String child = parts.length == 2 ? path + "/" + parts[1] : "";
                    var childMatches = child.isEmpty() ? List.<Unit>of() : moduleCandidates(modulePaths, child, true);
                    candidates = childMatches.isEmpty() ? moduleCandidates(modulePaths, path, true) : childMatches;
                    tentative = true;
                } else if (unit.language().equals("Java")) {
                    String name = unit.imports().getOrDefault(entry.getKey(), unit.namespace() + "." + entry.getKey());
                    candidates = types.getOrDefault(name, List.of());
                } else {
                    String imported = unit.imports().get(entry.getKey());
                    if (imported == null) continue;
                    if (imported.chars().anyMatch(Character::isISOControl)) { truncated = true; continue; }
                    if (imported.startsWith(".")) {
                        Path parent = Path.of(unit.file()).getParent();
                        String path = (parent == null ? Path.of("") : parent).resolve(imported).normalize().toString().replace('\\', '/');
                        candidates = moduleCandidates(modulePaths, path, false);
                        // 생략된 확장자의 실제 해석은 빌드·실행 설정에 따라 달라질 수 있다.
                        tentative = candidates.size() != 1 || !candidates.getFirst().file().equals(path);
                    } else if (imported.startsWith("@/")) {
                        String suffix = "/src/" + imported.substring(2);
                        candidates = moduleCandidates(modulePaths, suffix, true);
                        tentative = true;
                    } else continue;
                }
                if (candidates.size() != 1) {
                    if (unresolved.size() < 100 && (!unit.language().equals("Java") || candidates.size() > 1)) {
                        unresolved.add(Map.of("file", unit.file(), "line", entry.getValue(), "symbol", entry.getKey(),
                                "reason", candidates.size() > 1 ? "AMBIGUOUS" : "UNRESOLVED",
                                "candidates", candidates.stream().limit(5).map(Unit::file).toList()));
                    } else if (unresolved.size() >= 100) truncated = true;
                    continue;
                }
                if (candidates.getFirst().file().equals(unit.file())) continue;
                if (added++ >= 12 || links.size() >= 600) { truncated = true; break; }
                var target = candidates.getFirst();
                links.add(Map.of("file", unit.file(), "line", entry.getValue(), "targetFile", target.file(),
                        "targetLine", target.declarations().getOrDefault(entry.getKey(), 1), "symbol", entry.getKey(),
                        "kind", unit.language().equals("Java") ? "DECLARED_TYPE_REFERENCE" : "STATIC_IMPORT_REFERENCE",
                        "tentative", tentative));
            }
        }
        links.sort(Comparator.comparing(item -> item.get("file").toString() + ":" + item.get("targetFile")));
        routes.sort(Comparator.comparing(item -> item.get("file").toString() + ":" + item.get("line")));
        return Map.of("version", 1, "links", links, "routes", List.copyOf(routes), "truncated", truncated,
                "inspectedLanguages", List.copyOf(inspectedLanguages), "unsupportedLanguages", List.copyOf(unsupportedLanguages),
                "unresolvedReferences", unresolved,
                "limitations", List.of("정적 타입·가져오기·주소 선언만 확인합니다. 실제 호출과 접근 권한을 증명하지 않습니다.",
                        "별칭 경로는 설정을 해석하지 않은 후보이며, 동적 주소와 지원하지 않는 언어 연결은 확인하지 못합니다."));
    }

    private static String moduleStem(String file) {
        // 확장자 후보를 만들어 붙이지 않는다. 실제 읽은 파일의 전체 경로와 마지막 확장자를 뺀 경로만 대조한다.
        int slash = file.lastIndexOf('/');
        int dot = file.lastIndexOf('.');
        return dot > slash + 1 ? file.substring(0, dot) : file;
    }

    private static List<Unit> moduleCandidates(Map<String, List<Unit>> paths, String path, boolean suffix) {
        if (!suffix) return paths.getOrDefault(path, List.of());
        var candidates = new LinkedHashSet<Unit>();
        String ending = path.startsWith("/") ? path : "/" + path;
        paths.forEach((key, values) -> { if (key.equals(path) || key.endsWith(ending)) candidates.addAll(values); });
        return List.copyOf(candidates);
    }

    private static int line(String text, int position) { return 1 + (int) text.substring(0, position).chars().filter(c -> c == '\n').count(); }
    private record Unit(String file, String language, String namespace, Map<String, Integer> declarations,
                        Map<String, String> imports, Map<String, Integer> symbols) {}
}
