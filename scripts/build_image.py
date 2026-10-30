#!/usr/bin/env python3
"""Build a Linux amd64 image from the normal JAR with an explicitly approved hash."""

import argparse
import json
import re
import shutil
import signal
import subprocess
import tarfile
import tempfile
from pathlib import Path
from zipfile import ZipFile

import backup
import reports

ROOT = Path(__file__).resolve().parent.parent


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, timeout=1200).strip()


def build(jar, expected, output, revision, save_image=False):
    reports.require(
        re.fullmatch(r"[a-f0-9]{64}", expected) and backup.sha(jar) == expected,
        "JAR diverge do hash aprovado",
    )
    reports.require(
        re.fullmatch(r"[a-zA-Z0-9._-]{1,100}", revision), "Revisão inválida"
    )
    with ZipFile(jar) as archive:
        entries = archive.namelist()
        reports.require(
            not any(name.startswith("BOOT-INF/classes/demo/") for name in entries),
            "Imagem pessoal recusa fixture demo",
        )
        reports.require(
            archive.read("BOOT-INF/classes/web-ui/bundle.properties").startswith(
                b"mode=normal\n"
            ),
            "Imagem exige UI normal",
        )
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix="news-approved-image-") as directory:
        approved = Path(directory)
        context_tar = approved / "context.tar"
        subprocess.run(
            [
                "docker",
                "build",
                "--target=build-context",
                "--output",
                f"type=tar,dest={context_tar}",
                str(ROOT),
            ],
            check=True,
            timeout=300,
        )
        with tarfile.open(context_tar) as context:
            context_files = sorted(item.name for item in context if item.isfile())
        forbidden = {
            ".git",
            "artifacts",
            "node_modules",
            "target",
            "dist",
            "test-results",
            "docs",
        }
        reports.require(
            all(
                not (set(Path(name).parts) & forbidden)
                and not any(part.startswith(".env") for part in Path(name).parts)
                and not name.endswith((".dump", ".log"))
                for name in context_files
            ),
            "Contexto Docker inclui dados/saídas fora do build",
        )
        (output / "context-inventory.txt").write_text("\n".join(context_files) + "\n")
        context_tar.unlink()
        shutil.copy2(jar, approved / "news.jar")
        iid = approved / "image-id"
        subprocess.run(
            [
                "docker",
                "build",
                "--platform=linux/amd64",
                "--target=runtime",
                "--build-context",
                f"verified={approved}",
                "--build-arg",
                f"JAR_SHA256={expected}",
                "--build-arg",
                f"REVISION={revision}",
                "--iidfile",
                str(iid),
                str(ROOT),
            ],
            check=True,
            timeout=1200,
        )
        image_id = iid.read_text().strip()
        info = json.loads(docker("image", "inspect", image_id))[0]
        reports.require(
            info["Config"]["User"] == "10001:10001", "Runtime deve ser não root"
        )
        container = docker("create", image_id)
        try:
            extracted = approved / "extracted.jar"
            docker("cp", container + ":/app/news.jar", str(extracted))
            reports.require(
                backup.sha(extracted) == expected, "Imagem trocou o JAR aprovado"
            )
        finally:
            docker("rm", container)
        manifest = {
            "imageId": image_id,
            "repoDigests": info.get("RepoDigests") or [],
            "revision": revision,
            "jar": backup.jar_info(jar),
            "platform": "linux/amd64",
            "user": info["Config"]["User"],
            "entrypoint": info["Config"]["Entrypoint"],
            "healthcheck": info["Config"]["Healthcheck"],
        }
        reports.jar(jar, output, "normal")
        for name in ("compose.app.yaml", "Dockerfile", ".dockerignore"):
            shutil.copy2(ROOT / name, output / name)
        for name in ("backup.py", "backup-guide.md", "install-guide.md"):
            shutil.copy2(ROOT / "scripts" / name, output / name)
        if save_image:
            docker("image", "save", "--output", str(output / "image.tar"), image_id)
            archive = output / "image.tar"
            loaded = docker("image", "load", "--input", str(archive))
            reports.require(
                f"Loaded image ID: {image_id}" in loaded,
                "Load não conservou a identidade da imagem",
            )
            manifest["imageArchive"] = {
                "sha256": backup.sha(archive),
                "bytes": archive.stat().st_size,
                "loadVerified": True,
            }
        (output / "image.json").write_text(json.dumps(manifest, indent=2) + "\n")
        return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--save", action="store_true")
    args = parser.parse_args()
    build(
        args.jar.resolve(), args.sha256, args.output.resolve(), args.revision, args.save
    )


if __name__ == "__main__":

    def interrupted(_signal, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    main()
