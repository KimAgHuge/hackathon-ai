import argparse
import secrets
from typing import Literal


ENV_NAME = "DOENAAI_AI_INTERNAL_API_KEY"
KEY_PREFIX = "doenaai_"
DEFAULT_ENTROPY_BYTES = 48
MINIMUM_ENTROPY_BYTES = 32

OutputFormat = Literal["all", "env", "shell", "raw", "powershell"]


def generate_internal_api_key(
    entropy_bytes: int = DEFAULT_ENTROPY_BYTES,
) -> str:
    """Generate a URL-safe internal API key with at least 256 bits of entropy."""
    if entropy_bytes < MINIMUM_ENTROPY_BYTES:
        raise ValueError(
            f"entropy_bytes must be at least {MINIMUM_ENTROPY_BYTES}"
        )

    return f"{KEY_PREFIX}{secrets.token_urlsafe(entropy_bytes)}"


def format_internal_api_key(
    api_key: str,
    output_format: OutputFormat = "all",
) -> str:
    env_value = f"{ENV_NAME}={api_key}"
    shell_value = f"export {ENV_NAME}='{api_key}'"
    powershell_value = f"$env:{ENV_NAME}='{api_key}'"

    if output_format == "all":
        return "\n".join(
            (
                "# .env file",
                env_value,
                "# macOS/Linux shell",
                shell_value,
                "# Windows PowerShell",
                powershell_value,
            )
        )
    if output_format == "raw":
        return api_key
    if output_format == "powershell":
        return powershell_value
    if output_format == "shell":
        return shell_value
    if output_format == "env":
        return env_value

    raise ValueError(f"unsupported output format: {output_format}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Generate a local secret shared by Spring Boot and the FastAPI "
            "AI server. The key is printed only and is not saved to a file."
        )
    )
    parser.add_argument(
        "--format",
        choices=("all", "env", "shell", "raw", "powershell"),
        default="all",
        help="output format (default: all)",
    )
    args = parser.parse_args()

    api_key = generate_internal_api_key()
    print(format_internal_api_key(api_key, args.format))


if __name__ == "__main__":
    main()
