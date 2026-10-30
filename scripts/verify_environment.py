"""Declared prerequisites; report versions without exposing the environment or secrets."""

import json
import re
import subprocess
import sys
from pathlib import Path


def command(*args):
    try:
        result = subprocess.run(
            args,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=30,
        )
    except subprocess.TimeoutExpired:
        raise SystemExit(
            f"Prerequisite command timed out after 30 seconds: {args[0]}"
        ) from None
    except FileNotFoundError:
        raise SystemExit(f"Required tool is missing from PATH: {args[0]}") from None
    if result.returncode:
        raise SystemExit(
            f"Prerequisite command failed (exit {result.returncode}): {args[0]}"
        )

    print(result.stdout.strip())
    return result.stdout


if sys.version_info < (3, 11):  # noqa: UP036 - Diagnose unsupported bootstrap interpreters explicitly.
    raise SystemExit("O gate exige Python >= 3.11.")
print("Python", sys.version.split()[0])
java = command("java", "-version")
if not re.search(r'version "25\.', java):
    raise SystemExit("O projeto exige JDK 25.")
node = command("node", "--version")
match = re.fullmatch(r"v24\.(\d+)\.\d+\s*", node)
if not match or int(match[1]) < 15:
    raise SystemExit("O projeto exige Node >= 24.15 e < 25.")
npm = command("npm", "--version")
engine = command("docker", "version", "--format", "{{.Server.Version}}")
if int(engine.split(".")[0]) < 28:
    raise SystemExit("Distribuição local exige Docker Engine >=28.")
compose = command("docker", "compose", "version")
maven = command(
    str(Path(__file__).resolve().parent.parent / "backend/mvnw"),
    "--version",
)
if len(sys.argv) > 1:
    Path(sys.argv[1]).write_text(
        json.dumps(
            {
                "python": sys.version.split()[0],
                "java": java.strip(),
                "node": node.strip(),
                "npm": npm.strip(),
                "docker": engine.strip(),
                "compose": compose.strip(),
                "maven": maven.strip(),
            },
            indent=2,
        )
        + "\n"
    )
