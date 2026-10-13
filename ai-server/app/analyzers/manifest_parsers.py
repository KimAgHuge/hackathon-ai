import json
import re
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional

import tomli
import yaml


@dataclass
class ManifestData:
    ecosystem: str
    manifest: Path
    dependencies: Dict[str, Optional[str]] = field(default_factory=dict)
    build_tools: List[str] = field(default_factory=list)
    languages: List[str] = field(default_factory=list)


def normalize_python_package(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name.strip().lower())


def parse_requirement(requirement: str) -> tuple[str, Optional[str]]:
    cleaned = requirement.split(";", 1)[0].strip()
    cleaned = re.sub(r"^\s*[-.]e\s+", "", cleaned)
    match = re.match(r"([A-Za-z0-9_.-]+)(?:\[[^\]]+\])?\s*(.*)", cleaned)
    if not match:
        return "", None
    name = normalize_python_package(match.group(1))
    version = match.group(2).strip() or None
    return name, version


def _string_version(value) -> Optional[str]:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        version = value.get("version")
        return str(version) if version is not None else None
    return str(value)


def parse_package_json(path: Path) -> ManifestData:
    content = json.loads(path.read_text(encoding="utf-8"))
    dependencies_map: Dict[str, Optional[str]] = {}
    for section in (
        "dependencies",
        "devDependencies",
        "peerDependencies",
        "optionalDependencies",
    ):
        for name, version in (content.get(section) or {}).items():
            dependencies_map[name.lower()] = _string_version(version)

    package_manager = str(content.get("packageManager", "")).split("@", 1)[0]
    tools = [package_manager] if package_manager else []
    lock_tools = {
        "package-lock.json": "npm",
        "npm-shrinkwrap.json": "npm",
        "pnpm-lock.yaml": "pnpm",
        "yarn.lock": "Yarn",
        "bun.lock": "Bun",
        "bun.lockb": "Bun",
    }
    for lock_name, tool in lock_tools.items():
        if (path.parent / lock_name).exists() and tool not in tools:
            tools.append(tool)
    if not tools:
        tools.append("npm")

    return ManifestData(
        ecosystem="node",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=tools,
        languages=["JavaScript"],
    )


def parse_maven(path: Path) -> ManifestData:
    root = ElementTree.parse(path).getroot()
    properties = {}
    properties_node = root.find("{*}properties")
    if properties_node is not None:
        for child in list(properties_node):
            properties[child.tag.split("}")[-1]] = (child.text or "").strip()

    def resolve(value: Optional[str]) -> Optional[str]:
        if not value:
            return None
        match = re.fullmatch(r"\$\{([^}]+)}", value.strip())
        return properties.get(match.group(1), value) if match else value

    dependencies_map = {}
    for dependency in root.findall(".//{*}dependency"):
        group_id = dependency.findtext("{*}groupId", default="").strip()
        artifact_id = dependency.findtext("{*}artifactId", default="").strip()
        if group_id and artifact_id:
            dependencies_map[f"{group_id}:{artifact_id}".lower()] = resolve(
                dependency.findtext("{*}version")
            )

    for plugin in root.findall(".//{*}plugin"):
        group_id = plugin.findtext("{*}groupId", default="org.apache.maven.plugins").strip()
        artifact_id = plugin.findtext("{*}artifactId", default="").strip()
        if artifact_id:
            dependencies_map[f"plugin:{group_id}:{artifact_id}".lower()] = resolve(
                plugin.findtext("{*}version")
            )

    return ManifestData(
        ecosystem="maven",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=["Maven"],
        languages=["Java"],
    )


def parse_gradle(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    for line in content.splitlines():
        for quoted_value in re.findall(r"""['"]([^'"]+)['"]""", line):
            coordinate_parts = quoted_value.split(":", 2)
            if len(coordinate_parts) < 2:
                continue
            group_id, artifact_id = coordinate_parts[:2]
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", group_id):
                continue
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", artifact_id):
                continue
            version = (
                coordinate_parts[2]
                if len(coordinate_parts) == 3
                else None
            )
            dependencies_map[f"{group_id}:{artifact_id}".lower()] = version or None

    plugin_patterns = (
        re.compile(r"""\bid\s*(?:\(\s*)?['"]([^'"]+)['"]\s*\)?(?:\s+version\s+['"]([^'"]+)['"])?"""),
        re.compile(r"""\bid\s+['"]([^'"]+)['"]\s+version\s+['"]([^'"]+)['"]"""),
    )
    for pattern in plugin_patterns:
        for plugin_match in pattern.finditer(content):
            plugin_id = plugin_match.group(1)
            version = plugin_match.group(2) if plugin_match.lastindex and plugin_match.lastindex >= 2 else None
            dependencies_map[f"plugin:{plugin_id}".lower()] = version

    languages = ["Java"]
    if "kotlin(" in content or "org.jetbrains.kotlin" in content:
        languages.append("Kotlin")

    return ManifestData(
        ecosystem="gradle",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=["Gradle"],
        languages=languages,
    )


def parse_python_requirements(path: Path) -> ManifestData:
    dependencies_map = {}
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "-", "git+", "http:", "https:")):
            continue
        name, version = parse_requirement(line)
        if name:
            dependencies_map[name] = version
    return ManifestData(
        ecosystem="python",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=["pip"],
        languages=["Python"],
    )


def parse_pyproject(path: Path) -> ManifestData:
    content = tomli.loads(path.read_text(encoding="utf-8"))
    dependencies_map = {}
    for requirement in content.get("project", {}).get("dependencies", []) or []:
        name, version = parse_requirement(str(requirement))
        if name:
            dependencies_map[name] = version

    optional = content.get("project", {}).get("optional-dependencies", {}) or {}
    for requirements in optional.values():
        for requirement in requirements or []:
            name, version = parse_requirement(str(requirement))
            if name:
                dependencies_map[name] = version

    poetry = content.get("tool", {}).get("poetry", {})
    for section in ("dependencies", "dev-dependencies"):
        for name, version in (poetry.get(section) or {}).items():
            if name.lower() != "python":
                dependencies_map[normalize_python_package(name)] = _string_version(version)
    for group in (poetry.get("group") or {}).values():
        for name, version in (group.get("dependencies") or {}).items():
            dependencies_map[normalize_python_package(name)] = _string_version(version)

    tools = []
    build_backend = str(content.get("build-system", {}).get("build-backend", ""))
    if poetry or "poetry" in build_backend:
        tools.append("Poetry")
    if (path.parent / "uv.lock").exists():
        tools.append("uv")
    if not tools:
        tools.append("pip")

    return ManifestData(
        ecosystem="python",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=tools,
        languages=["Python"],
    )


def parse_pipfile(path: Path) -> ManifestData:
    content = tomli.loads(path.read_text(encoding="utf-8"))
    dependencies_map = {}
    for section in ("packages", "dev-packages"):
        for name, version in (content.get(section) or {}).items():
            dependencies_map[normalize_python_package(name)] = _string_version(version)
    return ManifestData(
        ecosystem="python",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=["Pipenv"],
        languages=["Python"],
    )


def parse_conda_environment(path: Path) -> ManifestData:
    content = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    dependencies_map = {}
    for dependency in content.get("dependencies", []) or []:
        if isinstance(dependency, str):
            name, _, version = dependency.partition("=")
            dependencies_map[normalize_python_package(name)] = version or None
        elif isinstance(dependency, dict):
            for requirement in dependency.get("pip", []) or []:
                name, version = parse_requirement(str(requirement))
                if name:
                    dependencies_map[name] = version
    return ManifestData(
        ecosystem="python",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=["Conda"],
        languages=["Python"],
    )


def parse_nuget_project(path: Path) -> ManifestData:
    root = ElementTree.parse(path).getroot()
    dependencies_map = {}
    sdk = root.attrib.get("Sdk")
    if sdk:
        dependencies_map[f"sdk:{sdk}".lower()] = None
    for package in root.findall(".//{*}PackageReference"):
        name = package.attrib.get("Include") or package.attrib.get("Update")
        version = package.attrib.get("Version") or package.findtext("{*}Version")
        if name:
            dependencies_map[name.lower()] = version
    language = {
        ".fsproj": "F#",
        ".vbproj": "Visual Basic .NET",
    }.get(path.suffix.lower(), "C#")
    return ManifestData(
        ecosystem="nuget",
        manifest=path,
        dependencies=dependencies_map,
        build_tools=["MSBuild", "dotnet"],
        languages=[language],
    )


def parse_go_mod(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    in_require_block = False
    for raw_line in content.splitlines():
        line = raw_line.strip()
        if line.startswith("require ("):
            in_require_block = True
            continue
        if in_require_block and line == ")":
            in_require_block = False
            continue
        if line.startswith("require "):
            line = line.removeprefix("require ").strip()
        elif not in_require_block:
            continue
        parts = line.split()
        if parts:
            dependencies_map[parts[0].lower()] = parts[1] if len(parts) > 1 else None
    return ManifestData("go", path, dependencies_map, ["Go Modules"], ["Go"])


def parse_cargo(path: Path) -> ManifestData:
    content = tomli.loads(path.read_text(encoding="utf-8"))
    dependencies_map = {}
    for section in ("dependencies", "dev-dependencies", "build-dependencies"):
        for name, version in (content.get(section) or {}).items():
            dependencies_map[name.lower()] = _string_version(version)
    for target in (content.get("target") or {}).values():
        for section in ("dependencies", "dev-dependencies"):
            for name, version in (target.get(section) or {}).items():
                dependencies_map[name.lower()] = _string_version(version)
    return ManifestData("rust", path, dependencies_map, ["Cargo"], ["Rust"])


def parse_composer(path: Path) -> ManifestData:
    content = json.loads(path.read_text(encoding="utf-8"))
    dependencies_map = {}
    for section in ("require", "require-dev"):
        for name, version in (content.get(section) or {}).items():
            if name.lower() != "php":
                dependencies_map[name.lower()] = _string_version(version)
    return ManifestData("php", path, dependencies_map, ["Composer"], ["PHP"])


def parse_gemfile(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    pattern = re.compile(r"""^\s*gem\s+['"]([^'"]+)['"](?:\s*,\s*['"]([^'"]+)['"])?""", re.MULTILINE)
    for name, version in pattern.findall(content):
        dependencies_map[name.lower()] = version or None
    return ManifestData("ruby", path, dependencies_map, ["Bundler"], ["Ruby"])


def parse_mix(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    pattern = re.compile(r"""\{\s*:([A-Za-z0-9_]+)\s*,\s*["']([^"']+)["']""")
    for name, version in pattern.findall(content):
        dependencies_map[name.lower()] = version
    return ManifestData("elixir", path, dependencies_map, ["Mix"], ["Elixir"])


def parse_pubspec(path: Path) -> ManifestData:
    content = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    dependencies_map = {}
    for section in ("dependencies", "dev_dependencies"):
        for name, version in (content.get(section) or {}).items():
            dependencies_map[name.lower()] = _string_version(version)
    return ManifestData("dart", path, dependencies_map, ["Dart pub"], ["Dart"])


def parse_swift_package(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    pattern = re.compile(
        r"""\.package\s*\(\s*url:\s*["']([^"']+)["'][^)]*(?:from:\s*["']([^"']+)["'])?"""
    )
    for url, version in pattern.findall(content):
        name = Path(url.removesuffix(".git")).name.lower()
        dependencies_map[name] = version or None
    return ManifestData("swift", path, dependencies_map, ["Swift Package Manager"], ["Swift"])


def parse_cmake(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    pattern = re.compile(r"\bfind_package\s*\(\s*([A-Za-z0-9_+.-]+)(?:\s+([0-9][^\s)]*))?", re.IGNORECASE)
    for name, version in pattern.findall(content):
        dependencies_map[name.lower()] = version or None
    return ManifestData("cmake", path, dependencies_map, ["CMake"], ["C", "C++"])


def parse_sbt(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    pattern = re.compile(
        r"""["']([^"']+)["']\s*%%?\s*["']([^"']+)["']\s*%\s*["']([^"']+)["']"""
    )
    for group_id, artifact_id, version in pattern.findall(content):
        dependencies_map[f"{group_id}:{artifact_id}".lower()] = version
    return ManifestData("sbt", path, dependencies_map, ["sbt"], ["Scala"])


def parse_clojure(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    pattern = re.compile(r"""([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\s+\{?:mvn/version\s+["']([^"']+)["']""")
    for name, version in pattern.findall(content):
        dependencies_map[name.lower()] = version
    lein_pattern = re.compile(r"""\[([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)\s+["']([^"']+)["']\]""")
    for name, version in lein_pattern.findall(content):
        dependencies_map[name.lower()] = version
    tool = "Leiningen" if path.name == "project.clj" else "Clojure CLI"
    return ManifestData("clojure", path, dependencies_map, [tool], ["Clojure"])


def parse_r_description(path: Path) -> ManifestData:
    content = path.read_text(encoding="utf-8", errors="replace")
    dependencies_map = {}
    fields = re.findall(r"^(?:Imports|Depends|Suggests):\s*(.+(?:\n\s+.+)*)", content, re.MULTILINE)
    for field_value in fields:
        for dependency in field_value.replace("\n", " ").split(","):
            match = re.match(r"\s*([A-Za-z0-9.]+)(?:\s*\(([^)]+)\))?", dependency)
            if match:
                dependencies_map[match.group(1).lower()] = match.group(2)
    return ManifestData("r", path, dependencies_map, ["R package"], ["R"])


def parse_manifest(path: Path) -> Optional[ManifestData]:
    """파일명에 맞는 생태계 파서를 선택한다; 지원하지 않는 manifest면 None."""
    name = path.name
    lower_name = name.lower()
    if lower_name == "package.json":
        return parse_package_json(path)
    if lower_name == "pom.xml":
        return parse_maven(path)
    if lower_name in {"build.gradle", "build.gradle.kts"}:
        return parse_gradle(path)
    if lower_name == "pyproject.toml":
        return parse_pyproject(path)
    if lower_name.startswith("requirements") and path.suffix.lower() in {".txt", ".in"}:
        return parse_python_requirements(path)
    if lower_name == "pipfile":
        return parse_pipfile(path)
    if lower_name in {"environment.yml", "environment.yaml", "conda.yml", "conda.yaml"}:
        return parse_conda_environment(path)
    if path.suffix.lower() in {".csproj", ".fsproj", ".vbproj"}:
        return parse_nuget_project(path)
    if lower_name == "go.mod":
        return parse_go_mod(path)
    if lower_name == "cargo.toml":
        return parse_cargo(path)
    if lower_name == "composer.json":
        return parse_composer(path)
    if lower_name == "gemfile" or path.suffix.lower() == ".gemspec":
        return parse_gemfile(path)
    if lower_name == "mix.exs":
        return parse_mix(path)
    if lower_name == "pubspec.yaml":
        return parse_pubspec(path)
    if name == "Package.swift":
        return parse_swift_package(path)
    if name == "CMakeLists.txt":
        return parse_cmake(path)
    if lower_name == "build.sbt":
        return parse_sbt(path)
    if lower_name in {"deps.edn", "project.clj"}:
        return parse_clojure(path)
    if name == "DESCRIPTION":
        return parse_r_description(path)
    return None


def supported_manifest(path: Path) -> bool:
    name = path.name
    lower_name = name.lower()
    return (
        lower_name
        in {
            "package.json",
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "pyproject.toml",
            "pipfile",
            "environment.yml",
            "environment.yaml",
            "conda.yml",
            "conda.yaml",
            "go.mod",
            "cargo.toml",
            "composer.json",
            "gemfile",
            "mix.exs",
            "pubspec.yaml",
            "package.swift",
            "cmakelists.txt",
            "build.sbt",
            "deps.edn",
            "project.clj",
            "description",
        }
        or lower_name.startswith("requirements")
        and path.suffix.lower() in {".txt", ".in"}
        or path.suffix.lower() in {".csproj", ".fsproj", ".vbproj", ".gemspec"}
    )
