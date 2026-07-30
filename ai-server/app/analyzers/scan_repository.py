import argparse
import json
from pathlib import Path

from app.analyzers.technology_detector import TechnologyDetector


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Statically detect technologies in a local repository."
    )
    parser.add_argument("repository", type=Path)
    parser.add_argument(
        "--source-summary",
        action="store_true",
        help="Print the compact sourceSummary payload used by the LLM API.",
    )
    args = parser.parse_args()

    report = TechnologyDetector().scan(args.repository)
    output = (
        report.to_source_summary()
        if args.source_summary
        else report.model_dump(mode="json", by_alias=True)
    )
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
