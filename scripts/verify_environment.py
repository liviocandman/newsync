"""Declared prerequisites; report versions without exposing the environment or secrets."""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


def command(*args, timeout=30):
    try:
        result = subprocess.run(
            args,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        raise SystemExit(
            f"Prerequisite command timed out after {timeout} seconds: {args[0]}"
        ) from None
    except OSError as error:
        raise SystemExit(
            f"Cannot run prerequisite {args[0]}: {error.strerror}"
        ) from None
    if result.stdout.strip():
        print(result.stdout.strip(), flush=True)
    if result.returncode:
        raise SystemExit(
            f"Prerequisite command failed (exit {result.returncode}): {args[0]}"
        )
    return result.stdout


def inspect(distribution=False):
    if sys.version_info < (3, 11):  # noqa: UP036 - Diagnose unsupported bootstrap interpreters explicitly.
        raise SystemExit("The gate requires Python >= 3.11.")
    versions = {"python": sys.version.split()[0]}
    print("Python", versions["python"], flush=True)
    java = command("java", "-version")
    if not re.search(r'^(?:openjdk|java)(?: version)? "?25(?:[.\s"+-]|$)', java, re.M):
        raise SystemExit("The project requires JDK 25.")
    versions["java"] = java.strip()
    node = command("node", "--version")
    match = re.fullmatch(r"v24\.(\d+)\.\d+\s*", node)
    if not match or int(match[1]) < 15:
        raise SystemExit("The project requires Node >= 24.15 and < 25.")
    versions["node"] = node.strip()
    versions["npm"] = command("npm", "--version").strip()
    engine = command("docker", "version", "--format", "{{.Server.Version}}")
    if not re.fullmatch(r"\d+(?:\.\d+)+(?:[-+][\w.-]+)?\s*", engine):
        raise SystemExit("Docker did not return a valid server version.")
    if int(engine.split(".")[0]) < 28:
        raise SystemExit("Loopback-isolated tests require Docker Engine >= 28.")
    versions["docker"] = engine.strip()
    if distribution:
        versions["compose"] = command("docker", "compose", "version").strip()
    # Git checkouts may not preserve executable bits from a Windows development tree.
    versions["maven"] = command(
        "sh",
        str(Path(__file__).resolve().parent.parent / "backend/mvnw"),
        "--version",
        timeout=120,
    ).strip()
    return versions


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", nargs="?", type=Path)
    parser.add_argument("--distribution", action="store_true")
    args = parser.parse_args(argv)
    versions = inspect(args.distribution)
    if args.report:
        args.report.write_text(json.dumps(versions, indent=2) + "\n")


if __name__ == "__main__":
    main()
