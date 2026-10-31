#!/usr/bin/env python3
"""Validate and materialize the explicitly reviewed source delivery set."""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = "config/delivery-files.json"
GENERATED = {
    ".git",
    "node_modules",
    "target",
    "dist",
    "artifacts",
    "__pycache__",
    "coverage",
    ".ruff_cache",
    "test-results",
    "playwright-report",
}


def discover(root):
    """Find maintained paths without entering private/configuration or output trees."""
    result = set()
    # The repository intentionally keeps planning/evidence in ignored local docs/.
    # Public operational documentation lives in tracked root/scripts files.
    managed = {"backend", "frontend", "scripts", "tools", "config", ".github"}
    excluded = GENERATED | {
        ".agents",
        ".codex",
        ".aws",
        "private",
        "personal",
        "surefire-reports",
        "failsafe-reports",
    }
    for parent, directories, names in os.walk(root, followlinks=False):
        relative = Path(parent).relative_to(root)
        directories[:] = sorted(
            name
            for name in directories
            if name not in excluded and (relative.parts or name in managed)
        )
        for name in names:
            if name.startswith(".env") and name not in {
                ".env.example",
                ".env.local.example",
            }:
                continue
            if name in {".DS_Store", "Thumbs.db", ".git"} or name.endswith(
                (".log", ".dump", ".tar", ".zip", ".exec")
            ):
                continue
            result.add((relative / name).as_posix())
    return result


def files(root=ROOT, checkout=False):
    data = json.loads((root / MANIFEST).read_text())
    names = data["files"]
    if data.get("schemaVersion") != 1 or not names or len(names) != len(set(names)):
        raise RuntimeError("Invalid or empty source delivery manifest")
    for name in names:
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or set(path.parts) & GENERATED:
            raise RuntimeError(f"Forbidden delivery path: {name}")
        if path.name.startswith(".env") and path.name not in {
            ".env.example",
            ".env.local.example",
        }:
            raise RuntimeError(f"Personal environment file in delivery: {name}")
        target = root / name
        if (
            not target.is_file()
            or target.is_symlink()
            or not target.resolve().is_relative_to(root.resolve())
        ):
            raise RuntimeError(f"Delivery file missing or symbolic: {name}")
    unlisted = discover(root) - set(names)
    if unlisted:
        raise RuntimeError(
            f"Maintained files missing from delivery manifest: {sorted(unlisted)}"
        )
    if (root / ".git").exists():
        tracked = set(
            subprocess.check_output(["git", "ls-files", "-z"], cwd=root)
            .decode()
            .split("\0")
        ) - {""}
        absent = tracked - set(names)
        if absent:
            raise RuntimeError(f"Tracked files omitted from delivery: {sorted(absent)}")
        if checkout and set(names) - tracked:
            raise RuntimeError(
                f"Checkout relies on untracked files: {sorted(set(names) - tracked)}"
            )
        ignored = subprocess.run(
            ["git", "check-ignore", "--stdin"],
            input="\n".join(names),
            cwd=root,
            text=True,
            capture_output=True,
            check=False,
        )
        if ignored.returncode not in (0, 1) or ignored.stdout.strip():
            raise RuntimeError(
                f"Delivery files ignored by Git: {ignored.stdout.strip()}"
            )
    return sorted(names)


def hashes(root=ROOT):
    return "".join(
        f"{hashlib.sha256((root / name).read_bytes()).hexdigest()}  {name}\n"
        for name in files(root)
    )


def snapshot(destination, root=ROOT):
    destination.mkdir(parents=True, exist_ok=False)
    for name in files(root):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / name, target)
    # The manifest is copied as source. No Git repository or staging area is created.
    return files(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkout", action="store_true")
    parser.add_argument("--snapshot", type=Path)
    parser.add_argument("--hashes", type=Path)
    args = parser.parse_args()
    names = snapshot(args.snapshot) if args.snapshot else files(checkout=args.checkout)
    if args.hashes:
        args.hashes.write_text(hashes())
    print(
        json.dumps(
            {
                "status": "passed",
                "files": len(names),
                "kind": "source-snapshot"
                if args.snapshot
                else "checkout"
                if args.checkout
                else "working-tree-manifest",
            }
        )
    )


if __name__ == "__main__":
    main()
