#!/usr/bin/env python3
"""Install checksum-pinned gate tools in a private cache, without system pip."""

import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import tarfile
import urllib.request
import venv
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = json.loads((ROOT / "config/tools.json").read_text())
CACHE = Path(os.environ.get("NEWS_TOOLS_DIR", ROOT / "artifacts/.tools"))


def download(name):
    spec = TOOLS[name]
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = CACHE / spec["url"].rsplit("/", 1)[1]
    if not archive.exists():
        temporary = archive.with_suffix(".download")
        try:
            digest = hashlib.sha256()
            size = 0
            with (
                urllib.request.urlopen(spec["url"], timeout=60) as response,
                temporary.open("wb") as out,
            ):
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > 256 * 1024 * 1024:
                        raise RuntimeError(f"{name}: download exceeds the size limit")
                    digest.update(chunk)
                    out.write(chunk)
            if digest.hexdigest() != spec["sha256"]:
                raise RuntimeError(f"{name}: download checksum mismatch")
            temporary.replace(archive)
        finally:
            temporary.unlink(missing_ok=True)
    with archive.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != spec["sha256"]:
            raise RuntimeError(f"{name}: cached archive checksum mismatch")
    return archive


def provision(name):
    if platform.system() != "Linux" or platform.machine() not in {"x86_64", "AMD64"}:
        raise RuntimeError("Pinned gate tools require Linux/WSL x64")
    archive = download(name)
    version = TOOLS[name]["version"]
    if name == "ruff":
        environment = CACHE / f"python-quality-{version}"
        if not (environment / "pyvenv.cfg").exists():
            venv.EnvBuilder(with_pip=False).create(environment)
        python = environment / "bin/python"
        site = Path(
            subprocess.check_output(
                [
                    str(python),
                    "-c",
                    "import sysconfig; print(sysconfig.get_path('purelib'))",
                ],
                text=True,
                timeout=30,
            ).strip()
        )
        with ZipFile(archive) as wheel:
            # A wheel is untrusted archive data even after its checksum is verified.
            for member in wheel.infolist():
                path = Path(member.filename)
                if path.is_absolute() or ".." in path.parts:
                    raise RuntimeError("Invalid wheel path")
            wheel.extractall(site)
        binary = environment / "bin/ruff"
        binary.write_bytes((site / f"ruff-{version}.data/scripts/ruff").read_bytes())
        binary.chmod(0o700)
        return [str(python), "-m", "ruff"]
    binary = CACHE / f"trivy-{version}"
    with tarfile.open(archive) as bundle:
        member = bundle.getmember("trivy")
        if not member.isfile():
            raise RuntimeError("Invalid Trivy executable")
        with bundle.extractfile(member) as stream, binary.open("wb") as out:
            while chunk := stream.read(1024 * 1024):
                out.write(chunk)
    binary.chmod(0o700)
    return [str(binary)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tool", choices=TOOLS)
    parser.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = provision(args.tool)
    return subprocess.run(
        command + args.arguments, check=False, timeout=1800
    ).returncode


if __name__ == "__main__":
    sys.exit(main())
