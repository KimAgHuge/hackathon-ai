import fnmatch
import json
import os
import xml.etree.ElementTree as ElementTree
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Set, Tuple

import yaml

from app.analyzers.manifest_parsers import ManifestData, parse_manifest, supported_manifest
from app.analyzers.technology_catalog import TECHNOLOGY_RULES, TechnologyRule
from app.models.source_facts import (
    DetectionEvidence,
    ModuleFact,
    RepositoryTechnologyReport,
    TechnologyCategory,
    TechnologyFact,
)

IGNORED_DIRECTORIES = {
    ".git",
    ".gradle",
    ".idea",
    ".mypy_cache",
    ".next",
    ".nuxt",
    ".pytest_cache",
    ".ruff_cache",
    ".tox",
    ".venv",
    ".vscode",
    "__pycache__",
    "artifacts",
    "build",
    "checkpoints",
    "coverage",
    "datasets",
    "dist",
    "node_modules",
    "out",
    "pods",
    "target",
    "training-runs",
    "vendor",
    "venv",
}

LANGUAGE_EXTENSIONS = {
    ".as": "ActionScript",
    ".c": "C",
    ".cc": "C++",
    ".clj": "Clojure",
    ".cljs": "ClojureScript",
    ".coffee": "CoffeeScript",
    ".cpp": "C++",
    ".cs": "C#",
    ".cu": "CUDA",
    ".dart": "Dart",
    ".elm": "Elm",
    ".erl": "Erlang",
    ".ex": "Elixir",
    ".exs": "Elixir",
    ".fs": "F#",
    ".fsx": "F#",
    ".go": "Go",
    ".groovy": "Groovy",
    ".h": "C/C++ Header",
    ".hpp": "C++ Header",
    ".hs": "Haskell",
    ".html": "HTML",
    ".java": "Java",
    ".js": "JavaScript",
    ".jsp": "JSP",
    ".jspx": "JSP",
    ".jsx": "JavaScript",
    ".kt": "Kotlin",
    ".kts": "Kotlin",
    ".lua": "Lua",
    ".m": "Objective-C",
    ".mm": "Objective-C++",
    ".php": "PHP",
    ".pl": "Perl",
    ".py": "Python",
    ".r": "R",
    ".razor": "Razor",
    ".rb": "Ruby",
    ".rs": "Rust",
    ".scala": "Scala",
    ".sh": "Shell",
    ".sql": "SQL",
    ".svelte": "Svelte",
    ".swift": "Swift",
    ".ts": "TypeScript",
    ".tsx": "TypeScript",
    ".vb": "Visual Basic .NET",
    ".vue": "Vue",
}

EXTRA_MANIFEST_NAMES = {
    "ant.xml": ("Ant", "Java"),
    "build.xml": ("Ant", "Java"),
    "makefile": ("Make", None),
    "meson.build": ("Meson", None),
    "workspace": ("Bazel", None),
    "workspace.bazel": ("Bazel", None),
    "build.bazel": ("Bazel", None),
    "podfile": ("CocoaPods", None),
    "deno.json": ("Deno", "TypeScript"),
    "deno.jsonc": ("Deno", "TypeScript"),
}


class TechnologyDetector:
    """저장소 파일과 manifest를 정적으로 읽어 언어·도구·프레임워크 근거를 만든다."""

    def __init__(
        self,
        max_files: int = 100_000,
        max_depth: int = 20,
        max_manifest_bytes: int = 2_000_000,
    ):
        self.max_files = max_files
        self.max_depth = max_depth
        self.max_manifest_bytes = max_manifest_bytes

    def scan(self, repository_path: Path) -> RepositoryTechnologyReport:
        """repository_path 아래를 제한 범위로 스캔해 모듈별 기술 보고서를 반환한다."""
        root = repository_path.expanduser().resolve()
        if not root.is_dir():
            raise ValueError(f"Repository path is not a directory: {root}")

        files, directories, warnings = self._collect_paths(root)
        manifest_paths = [path for path in files if self._is_manifest(path)]
        module_directories = {path.parent for path in manifest_paths}
        module_directories.update(
            self._discover_source_only_modules(
                root,
                files,
                module_directories,
            )
        )
        module_directories.update(
            self._discover_marker_only_modules(
                root,
                files + directories,
                module_directories,
            )
        )
        if not module_directories:
            module_directories.add(root)

        files_by_module = self._assign_files_to_modules(
            root,
            files,
            module_directories,
        )
        manifests_by_module = defaultdict(list)
        for manifest_path in manifest_paths:
            manifests_by_module[manifest_path.parent].append(manifest_path)

        modules = []
        for module_directory in sorted(module_directories, key=lambda path: str(path)):
            module = self._scan_module(
                root,
                module_directory,
                manifests_by_module[module_directory],
                files_by_module.get(module_directory, []),
                directories,
                warnings,
            )
            modules.append(module)

        repository_technologies = self._merge_repository_technologies(modules)
        return RepositoryTechnologyReport(
            root_name=root.name,
            modules=modules,
            languages=self._ordered_unique(
                language for module in modules for language in module.languages
            ),
            build_tools=self._ordered_unique(
                tool for module in modules for tool in module.build_tools
            ),
            technologies=repository_technologies,
            scanned_file_count=len(files),
            warnings=self._ordered_unique(warnings),
        )

    def _collect_paths(
        self,
        root: Path,
    ) -> Tuple[List[Path], List[Path], List[str]]:
        files: List[Path] = []
        directories: List[Path] = []
        warnings: List[str] = []
        stop = False

        for current_root, directory_names, file_names in os.walk(
            root,
            topdown=True,
            followlinks=False,
        ):
            current = Path(current_root)
            depth = len(current.relative_to(root).parts)
            directory_names[:] = [
                name
                for name in directory_names
                if name.lower() not in IGNORED_DIRECTORIES
                and not (current / name).is_symlink()
            ]
            if depth >= self.max_depth:
                directory_names[:] = []
                warnings.append(
                    f"Maximum scan depth reached at {current.relative_to(root)}."
                )

            for directory_name in directory_names:
                directories.append(current / directory_name)

            for file_name in file_names:
                path = current / file_name
                if path.is_symlink():
                    continue
                files.append(path)
                if len(files) >= self.max_files:
                    warnings.append(
                        f"Maximum file count {self.max_files} reached; scan was truncated."
                    )
                    stop = True
                    break
            if stop:
                break

        return files, directories, warnings

    def _discover_source_only_modules(
        self,
        root: Path,
        files: List[Path],
        existing_modules: Set[Path],
    ) -> Set[Path]:
        candidates: Set[Path] = set()
        for file_path in files:
            if file_path.suffix.lower() not in LANGUAGE_EXTENSIONS:
                continue
            if any(
                module == file_path.parent or module in file_path.parents
                for module in existing_modules
            ):
                continue
            relative_parts = file_path.relative_to(root).parts
            candidate = (
                root / relative_parts[0]
                if len(relative_parts) > 1
                else root
            )
            candidates.add(candidate)
        return candidates

    def _discover_marker_only_modules(
        self,
        root: Path,
        paths: List[Path],
        existing_modules: Set[Path],
    ) -> Set[Path]:
        marker_globs = [
            marker_glob
            for rule in TECHNOLOGY_RULES
            for marker_glob in rule.marker_globs
        ]
        candidates: Set[Path] = set()
        for path in paths:
            if any(
                module == path.parent or module in path.parents
                for module in existing_modules
            ):
                continue
            relative = path.relative_to(root).as_posix()
            if not any(
                fnmatch.fnmatchcase(relative.lower(), marker.lower())
                or fnmatch.fnmatchcase(path.name.lower(), marker.lower())
                for marker in marker_globs
            ):
                continue
            relative_parts = path.relative_to(root).parts
            candidates.add(
                root
                if len(relative_parts) == 1 or relative_parts[0].startswith(".")
                else root / relative_parts[0]
            )
        return candidates

    def _is_manifest(self, path: Path) -> bool:
        return (
            supported_manifest(path)
            or path.name.lower() in EXTRA_MANIFEST_NAMES
        )

    def _assign_files_to_modules(
        self,
        root: Path,
        files: List[Path],
        module_directories: Set[Path],
    ) -> Dict[Path, List[Path]]:
        result: Dict[Path, List[Path]] = defaultdict(list)
        sorted_modules = sorted(
            module_directories,
            key=lambda path: len(path.relative_to(root).parts),
            reverse=True,
        )
        for file_path in files:
            owner = next(
                (
                    module
                    for module in sorted_modules
                    if file_path == module or module in file_path.parents
                ),
                None,
            )
            if owner is not None:
                result[owner].append(file_path)
        return result

    def _scan_module(
        self,
        root: Path,
        module_directory: Path,
        manifests: List[Path],
        module_files: List[Path],
        directories: List[Path],
        warnings: List[str],
    ) -> ModuleFact:
        parsed_manifests: List[ManifestData] = []
        extra_tools = []
        extra_languages = []

        for manifest in manifests:
            relative_manifest = manifest.relative_to(root).as_posix()
            try:
                if manifest.stat().st_size > self.max_manifest_bytes:
                    warnings.append(
                        f"Manifest skipped because it exceeds the size limit: {relative_manifest}"
                    )
                    continue
                parsed = parse_manifest(manifest)
                if parsed:
                    parsed_manifests.append(parsed)
                else:
                    tool_and_language = EXTRA_MANIFEST_NAMES.get(manifest.name.lower())
                    if tool_and_language:
                        tool, language = tool_and_language
                        extra_tools.append(tool)
                        if language:
                            extra_languages.append(language)
            except (
                OSError,
                UnicodeError,
                ValueError,
                ElementTree.ParseError,
                json.JSONDecodeError,
                yaml.YAMLError,
            ) as exc:
                warnings.append(
                    f"Could not parse {relative_manifest}: {type(exc).__name__}"
                )

        module_relative_files = [
            path.relative_to(module_directory).as_posix()
            for path in module_files
        ]
        module_relative_directories = [
            path.relative_to(module_directory).as_posix()
            for path in directories
            if module_directory in path.parents
        ]
        marker_paths = module_relative_files + module_relative_directories

        technology_facts, classified_dependencies = self._detect_technologies(
            root,
            parsed_manifests,
            marker_paths,
            module_files,
        )

        all_dependencies = {
            dependency
            for manifest in parsed_manifests
            for dependency in manifest.dependencies
        }
        languages = self._ordered_unique(
            language
            for manifest in parsed_manifests
            for language in manifest.languages
        )
        languages.extend(
            language
            for language in extra_languages
            if language not in languages
        )
        language_counts = Counter(
            LANGUAGE_EXTENSIONS.get(path.suffix.lower())
            for path in module_files
            if LANGUAGE_EXTENSIONS.get(path.suffix.lower())
        )
        for language, _count in language_counts.most_common():
            if language not in languages:
                languages.append(language)

        build_tools = self._ordered_unique(
            tool
            for manifest in parsed_manifests
            for tool in manifest.build_tools
        )
        build_tools.extend(
            tool for tool in extra_tools if tool not in build_tools
        )
        for technology in technology_facts:
            if (
                technology.category == TechnologyCategory.BUILD
                and technology.name not in build_tools
            ):
                build_tools.append(technology.name)

        self._add_vanilla_javascript_if_applicable(
            root,
            module_files,
            technology_facts,
        )

        return ModuleFact(
            path=self._relative_module_path(root, module_directory),
            ecosystems=self._ordered_unique(
                manifest.ecosystem for manifest in parsed_manifests
            ),
            languages=languages,
            build_tools=build_tools,
            manifests=sorted(
                manifest.relative_to(root).as_posix() for manifest in manifests
            ),
            technologies=sorted(
                technology_facts,
                key=lambda technology: (
                    technology.category.value,
                    technology.name.lower(),
                ),
            ),
            dependency_count=len(all_dependencies),
            unclassified_dependencies=sorted(
                all_dependencies - classified_dependencies
            )[:200],
        )

    def _detect_technologies(
        self,
        root: Path,
        manifests: List[ManifestData],
        marker_paths: List[str],
        module_files: List[Path],
    ) -> Tuple[List[TechnologyFact], Set[str]]:
        facts: Dict[Tuple[str, str], TechnologyFact] = {}
        classified_dependencies: Set[str] = set()

        for rule in TECHNOLOGY_RULES:
            for ecosystem, patterns in rule.dependency_patterns:
                for manifest in manifests:
                    if manifest.ecosystem != ecosystem:
                        continue
                    for dependency, version in manifest.dependencies.items():
                        if any(
                            fnmatch.fnmatchcase(dependency.lower(), pattern.lower())
                            for pattern in patterns
                        ):
                            classified_dependencies.add(dependency)
                            self._merge_fact(
                                facts,
                                TechnologyFact(
                                    name=rule.name,
                                    category=rule.category,
                                    ecosystem=ecosystem,
                                    declared_version=version,
                                    confidence=1.0,
                                    evidences=[
                                        DetectionEvidence(
                                            file=manifest.manifest.relative_to(root).as_posix(),
                                            evidence_type="DEPENDENCY",
                                            value=dependency,
                                        )
                                    ],
                                ),
                            )

            for marker_glob in rule.marker_globs:
                matched_path = next(
                    (
                        path
                        for path in marker_paths
                        if fnmatch.fnmatchcase(path.lower(), marker_glob.lower())
                        or fnmatch.fnmatchcase(
                            Path(path).name.lower(),
                            marker_glob.lower(),
                        )
                    ),
                    None,
                )
                if matched_path:
                    marker_file = next(
                        (
                            path
                            for path in module_files
                            if path.name == Path(matched_path).name
                        ),
                        None,
                    )
                    evidence_file = (
                        marker_file.relative_to(root).as_posix()
                        if marker_file
                        else matched_path
                    )
                    self._merge_fact(
                        facts,
                        TechnologyFact(
                            name=rule.name,
                            category=rule.category,
                            ecosystem=self._rule_primary_ecosystem(rule),
                            confidence=0.75,
                            evidences=[
                                DetectionEvidence(
                                    file=evidence_file,
                                    evidence_type="MARKER",
                                    value=marker_glob,
                                )
                            ],
                        ),
                    )

        return list(facts.values()), classified_dependencies

    @staticmethod
    def _merge_fact(
        facts: Dict[Tuple[str, str], TechnologyFact],
        incoming: TechnologyFact,
    ) -> None:
        key = (incoming.name, incoming.ecosystem)
        existing = facts.get(key)
        if existing is None:
            facts[key] = incoming
            return
        existing.confidence = max(existing.confidence, incoming.confidence)
        existing.declared_version = (
            existing.declared_version or incoming.declared_version
        )
        existing.resolved_version = (
            existing.resolved_version or incoming.resolved_version
        )
        evidence_keys = {
            (evidence.file, evidence.evidence_type, evidence.value)
            for evidence in existing.evidences
        }
        for evidence in incoming.evidences:
            evidence_key = (
                evidence.file,
                evidence.evidence_type,
                evidence.value,
            )
            if evidence_key not in evidence_keys:
                existing.evidences.append(evidence)

    @staticmethod
    def _rule_primary_ecosystem(rule: TechnologyRule) -> str:
        if rule.dependency_patterns:
            return rule.dependency_patterns[0][0]
        return "file"

    @staticmethod
    def _add_vanilla_javascript_if_applicable(
        root: Path,
        module_files: List[Path],
        facts: List[TechnologyFact],
    ) -> None:
        has_html = any(path.suffix.lower() in {".html", ".htm"} for path in module_files)
        js_file = next(
            (
                path
                for path in module_files
                if path.suffix.lower() in {".js", ".mjs", ".cjs"}
            ),
            None,
        )
        frontend_frameworks = {
            fact.name
            for fact in facts
            if fact.category in {
                TechnologyCategory.FRONTEND,
                TechnologyCategory.FULLSTACK,
            }
        }
        if has_html and js_file and not frontend_frameworks:
            facts.append(
                TechnologyFact(
                    name="Vanilla JavaScript",
                    category=TechnologyCategory.FRONTEND,
                    ecosystem="browser",
                    confidence=0.65,
                    evidences=[
                        DetectionEvidence(
                            file=js_file.relative_to(root).as_posix(),
                            evidence_type="SOURCE_FILE",
                            value=js_file.suffix.lower(),
                        )
                    ],
                )
            )

    @staticmethod
    def _merge_repository_technologies(
        modules: List[ModuleFact],
    ) -> List[TechnologyFact]:
        merged: Dict[Tuple[str, str], TechnologyFact] = {}
        for module in modules:
            for technology in module.technologies:
                TechnologyDetector._merge_fact(merged, technology.model_copy(deep=True))
        return sorted(
            merged.values(),
            key=lambda technology: (
                technology.category.value,
                technology.name.lower(),
            ),
        )

    @staticmethod
    def _relative_module_path(root: Path, module: Path) -> str:
        relative = module.relative_to(root).as_posix()
        return relative if relative != "." else "."

    @staticmethod
    def _ordered_unique(values: Iterable[str]) -> List[str]:
        return list(dict.fromkeys(value for value in values if value))
