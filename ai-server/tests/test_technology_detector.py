import json
from pathlib import Path

from app.analyzers.technology_detector import TechnologyDetector


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def technology_names(report):
    return {technology.name for technology in report.technologies}


def module_at(report, path):
    return next(module for module in report.modules if module.path == path)


def test_detects_polyglot_monorepo_without_executing_builds(tmp_path):
    write(
        tmp_path / "web/package.json",
        json.dumps(
            {
                "dependencies": {
                    "next": "^15.1.0",
                    "react": "^19.0.0",
                    "drizzle-orm": "^0.40.0",
                    "unknown-web-library": "1.2.3",
                }
            }
        ),
    )
    write(
        tmp_path / "api/package.json",
        json.dumps(
            {
                "dependencies": {
                    "@nestjs/core": "^11.0.0",
                    "fastify": "^5.0.0",
                }
            }
        ),
    )
    write(
        tmp_path / "java-service/pom.xml",
        """<project>
          <modelVersion>4.0.0</modelVersion>
          <dependencies>
            <dependency>
              <groupId>io.quarkus</groupId>
              <artifactId>quarkus-rest</artifactId>
              <version>3.20.0</version>
            </dependency>
            <dependency>
              <groupId>jakarta.servlet</groupId>
              <artifactId>jakarta.servlet-api</artifactId>
              <version>6.1.0</version>
            </dependency>
          </dependencies>
        </project>""",
    )
    write(tmp_path / "java-service/src/main/webapp/index.jsp", "<html/>")
    write(
        tmp_path / "python-api/pyproject.toml",
        """[project]
name = "python-api"
dependencies = ["flask==3.1.0", "sqlalchemy>=2.0", "torch==2.8.0"]
""",
    )
    write(
        tmp_path / "go-api/go.mod",
        """module example.com/go-api
require github.com/gin-gonic/gin v1.10.0
""",
    )
    write(
        tmp_path / "rust-api/Cargo.toml",
        """[package]
name = "rust-api"
version = "0.1.0"
[dependencies]
axum = "0.8"
sea-orm = "1.1"
""",
    )
    write(
        tmp_path / "php-app/composer.json",
        json.dumps({"require": {"laravel/framework": "^12.0"}}),
    )
    write(
        tmp_path / "dotnet-api/api.csproj",
        """<Project Sdk="Microsoft.NET.Sdk.Web">
          <ItemGroup>
            <PackageReference Include="Microsoft.EntityFrameworkCore" Version="10.0.0" />
          </ItemGroup>
        </Project>""",
    )
    write(
        tmp_path / "ruby-app/Gemfile",
        """source "https://rubygems.org"
gem "rails", "~> 8.0"
""",
    )
    write(
        tmp_path / "elixir-app/mix.exs",
        """defp deps do
  [{:phoenix, "~> 1.7"}, {:ecto_sql, "~> 3.12"}]
end
""",
    )
    write(
        tmp_path / "flutter-app/pubspec.yaml",
        """name: flutter_app
dependencies:
  flutter:
    sdk: flutter
""",
    )
    write(
        tmp_path / "native-app/CMakeLists.txt",
        """cmake_minimum_required(VERSION 3.20)
find_package(Qt6 6.8 REQUIRED)
find_package(OpenCV 4.10 REQUIRED)
""",
    )
    write(
        tmp_path / "swift-api/Package.swift",
        """.package(url: "https://github.com/vapor/vapor.git", from: "4.110.0")""",
    )
    write(
        tmp_path / "vanilla-site/index.html",
        "<script src='app.js'></script>",
    )
    write(tmp_path / "vanilla-site/app.js", "document.body.dataset.ready = 'true'")
    write(tmp_path / "Dockerfile", "FROM scratch\n")
    write(tmp_path / "infra/main.tf", 'terraform { required_version = ">= 1.8" }\n')

    report = TechnologyDetector().scan(tmp_path)
    names = technology_names(report)

    assert {
        "Next.js",
        "React",
        "Drizzle ORM",
        "NestJS",
        "Fastify",
        "Quarkus",
        "Jakarta Servlet",
        "JSP",
        "Flask",
        "SQLAlchemy",
        "PyTorch",
        "Gin",
        "Axum",
        "SeaORM",
        "Laravel",
        "ASP.NET Core",
        "Entity Framework Core",
        "Ruby on Rails",
        "Phoenix",
        "Ecto",
        "Flutter",
        "Qt",
        "OpenCV",
        "Vapor",
        "Vanilla JavaScript",
        "Docker",
        "Terraform",
    }.issubset(names)

    web_module = module_at(report, "web")
    assert "unknown-web-library" in web_module.unclassified_dependencies
    assert web_module.build_tools == ["npm"]
    assert "TypeScript" not in web_module.languages
    assert report.scanned_file_count > 0


def test_malformed_manifest_is_reported_without_failing_scan(tmp_path):
    write(tmp_path / "broken/package.json", "{not-json")
    write(tmp_path / "working/requirements.txt", "fastapi==0.128.8\n")

    report = TechnologyDetector().scan(tmp_path)

    assert "FastAPI" in technology_names(report)
    assert any("broken/package.json" in warning for warning in report.warnings)


def test_source_summary_contains_evidence_and_module_boundaries(tmp_path):
    write(
        tmp_path / "frontend/package.json",
        json.dumps({"dependencies": {"svelte": "^5.0.0", "@sveltejs/kit": "^2.0.0"}}),
    )
    write(
        tmp_path / "backend/build.gradle",
        """plugins {
  id 'org.springframework.boot' version '4.1.0'
}
dependencies {
  implementation 'org.springframework.boot:spring-boot-starter-web'
}
""",
    )

    report = TechnologyDetector().scan(tmp_path)
    summary = report.to_source_summary()

    assert {module["path"] for module in summary["components"]} == {
        "frontend",
        "backend",
    }
    assert "SvelteKit" in {
        framework["name"] for framework in summary["frameworks"]
    }
    spring = next(
        framework
        for framework in summary["frameworks"]
        if framework["name"] == "Spring Boot"
    )
    assert spring["evidences"]
    assert spring["evidences"][0]["file"] == "backend/build.gradle"
