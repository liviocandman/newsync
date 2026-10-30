#!/usr/bin/env python3
"""Pinned Gitleaks over distributable working-tree sources, never personal .env or build outputs."""

import hashlib
import io
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION = "8.30.1"
ARCHIVE_SHA256 = "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"
URL = f"https://github.com/gitleaks/gitleaks/releases/download/v{VERSION}/gitleaks_{VERSION}_linux_x64.tar.gz"


def scanner():
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "AMD64"):
        raise RuntimeError("O gate de referência exige Linux/WSL x64 para o scanner fixado.")
    cache = ROOT / "artifacts/.tools"
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / f"gitleaks-{VERSION}.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(URL, timeout=30) as response:
            payload = response.read(32 * 1024 * 1024 + 1)
        if len(payload) > 32 * 1024 * 1024 or hashlib.sha256(payload).hexdigest() != ARCHIVE_SHA256:
            raise RuntimeError("Arquivo do scanner não corresponde ao SHA-256 fixado.")
        archive.write_bytes(payload)
    payload = archive.read_bytes()
    if hashlib.sha256(payload).hexdigest() != ARCHIVE_SHA256:
        raise RuntimeError("Cache do scanner não corresponde ao SHA-256 fixado.")
    # Extract only this regular file; no archive paths are written to the workspace.
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as bundle:
        member = bundle.getmember("gitleaks")
        if not member.isfile():
            raise RuntimeError("Binário do scanner inválido.")
        binary = bundle.extractfile(member).read()
    executable = cache / f"gitleaks-{VERSION}"
    executable.write_bytes(binary)
    executable.chmod(0o700)
    return executable


def main():
    report = Path(sys.argv[1]).resolve()
    report.parent.mkdir(parents=True, exist_ok=True)
    executable = scanner()
    ignored = shutil.ignore_patterns("target", "node_modules", "dist", "test-results",
                                     "playwright-report", "__pycache__", "*.log")
    with tempfile.TemporaryDirectory(prefix="news-secrets-") as directory:
        staged = Path(directory)
        for name in ("backend", "frontend", "scripts", ".github"):
            shutil.copytree(ROOT / name, staged / name, ignore=ignored)
        for name in ("README.md", "compose.yaml", "compose.local.yaml", ".gitignore",
                     ".env.example", ".env.local.example", "Dockerfile", ".dockerignore", "compose.app.yaml"):
            shutil.copy2(ROOT / name, staged / name)
        result = subprocess.run([str(executable), "dir", str(staged), "--no-banner", "--redact=100",
                                 "--log-level=error", "--report-format=json", "--report-path=" + str(report)],
                                check=False, timeout=120)
        print(f"Gitleaks {VERSION}: exit {result.returncode}; relatório redigido em {report}", flush=True)
        return result.returncode


if __name__ == "__main__":
    sys.exit(main())
