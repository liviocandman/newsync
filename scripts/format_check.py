#!/usr/bin/env python3
"""Read-only formatting and line-ending checks over the reviewed delivery set."""

import subprocess
import sys
from pathlib import Path

import delivery

ROOT = delivery.ROOT
IMMUTABLE = (
    "docs/evidencias/B-",
    "docs/evidencias/F-01",
    "docs/evidencias/F-02",
    "docs/evidencias/revisao-fundacao.md",
    "docs/historico/",
    "backend/src/test/resources/",
    "backend/src/demo/resources/",
    "backend/src/main/resources/db/migration/",
)
WRAPPER = {
    "backend/mvnw",
    "backend/mvnw.cmd",
    "backend/.mvn/wrapper/maven-wrapper.properties",
}
EXTENSIONS = {
    ".ts",
    ".tsx",
    ".js",
    ".mjs",
    ".mts",
    ".css",
    ".html",
    ".json",
    ".yaml",
    ".yml",
    ".md",
    ".xml",
}


def line_endings(names):
    errors = []
    for name in names:
        path = ROOT / name
        if path.suffix in {".png", ".jpg", ".ico", ".patch"} or name.startswith(
            IMMUTABLE
        ):
            continue
        content = path.read_bytes()
        if b"\0" in content:
            continue
        try:
            content.decode("utf-8")
        except UnicodeDecodeError:
            errors.append(f"{name}: expected UTF-8 text")
            continue
        normalized = (
            content.replace(b"\r\n", b"\n") if name == "backend/mvnw.cmd" else content
        )
        if b"\r" in normalized or content and not content.endswith(b"\n"):
            errors.append(f"{name}: invalid line endings or missing final newline")
        if name == "backend/mvnw.cmd" and b"\n" in content.replace(b"\r\n", b""):
            errors.append(f"{name}: the Windows wrapper requires CRLF")
    return errors


def main():
    names = delivery.files()
    errors = line_endings(names)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    selected = [
        name
        for name in names
        if not name.startswith(IMMUTABLE)
        and name not in WRAPPER
        and Path(name).name != "package-lock.json"
        and (Path(name).suffix in EXTENSIONS or name == "backend/pom.xml")
    ]
    if not selected:
        raise RuntimeError("Prettier scope is empty")
    command = [
        "node",
        str(ROOT / "tools/quality/node_modules/.bin/prettier"),
        "--check",
        "--config",
        str(ROOT / ".prettierrc.json"),
        *selected,
    ]
    print(
        f"Checking line endings for {len(names)} delivery files; Prettier scope: {len(selected)} files",
        flush=True,
    )
    return subprocess.run(command, cwd=ROOT, check=False, timeout=180).returncode


if __name__ == "__main__":
    sys.exit(main())
