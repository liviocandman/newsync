#!/usr/bin/env python3
"""Exercise pinned lint/format engines against temporary, intentionally invalid sources."""

import argparse
import json
import shutil
import signal
import subprocess
import tempfile
from pathlib import Path

from quality_tools import ROOT, provision


def probe(command, rule, *, source=None, cwd=ROOT):
    result = subprocess.run(
        command,
        input=source,
        capture_output=True,
        text=True,
        cwd=cwd,
        check=False,
        timeout=180,
    )
    if result.returncode != 1 or rule not in result.stdout + result.stderr:
        raise RuntimeError(
            f"Negative probe did not detect {rule}: exit {result.returncode}"
        )
    return {
        "command": command,
        "exitCode": result.returncode,
        "expectedRule": rule,
        "diagnostic": result.stdout.strip(),
        "stderr": result.stderr.strip(),
    }


def typescript_probe(temporary):
    project = temporary / "frontend"
    names = json.loads((ROOT / "config/delivery-files.json").read_text())["files"]
    for name in names:
        if name.startswith("frontend/"):
            target = temporary / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, target)
    (project / "node_modules").symlink_to(
        ROOT / "frontend/node_modules", target_is_directory=True
    )
    script = project / "scripts/f03-undefined-probe.mts"
    script.write_text("void undefined_f03_mts_probe;\nexport {};\n")
    result = probe(
        [
            "node",
            str(ROOT / "frontend/node_modules/typescript/bin/tsc"),
            "--project",
            str(project / "tsconfig.json"),
            "--noEmit",
        ],
        "TS2304",
    )
    if (
        script.name not in result["diagnostic"]
        or "undefined_f03_mts_probe" not in result["diagnostic"]
    ):
        raise RuntimeError(
            "The frontend typecheck did not check the temporary .mts script"
        )
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = {}
    with tempfile.TemporaryDirectory(prefix="caderno-quality-probes-") as directory:
        temporary = Path(directory)
        eslint = str(ROOT / "tools/quality/node_modules/.bin/eslint")
        results["hooks"] = probe(
            [
                "node",
                eslint,
                "--max-warnings=0",
                "--stdin",
                "--stdin-filename",
                "frontend/src/F03Probe.tsx",
                "--format",
                "json",
            ],
            "react-hooks/rules-of-hooks",
            source="import { useEffect } from 'react';\nexport function Probe({ enabled }) { if (enabled) useEffect(() => {}, []); return null; }\n",
        )
        results["python"] = probe(
            [
                *provision("ruff"),
                "check",
                "--output-format=json",
                "--stdin-filename",
                "scripts/f03_probe.py",
                "-",
            ],
            "F821",
            source="print(undefined_f03_probe)\n",
        )
        results["typescriptScripts"] = typescript_probe(temporary)
        pom = temporary / "pom.xml"
        pom.write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            "<groupId>local.tools</groupId><artifactId>quality-probe</artifactId><version>1</version>"
            "<dependencies><dependency><groupId>com.puppycrawl.tools</groupId><artifactId>checkstyle</artifactId>"
            "<version>12.3.1</version></dependency></dependencies></project>\n"
        )
        classpath = temporary / "classpath.txt"
        resolve = [
            str(ROOT / "backend/mvnw"),
            "-B",
            "-f",
            str(pom),
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
            "-Dmdep.outputFile=" + str(classpath),
        ]
        resolved = subprocess.run(
            resolve, capture_output=True, text=True, check=False, timeout=180
        )
        if resolved.returncode or not classpath.is_file():
            raise RuntimeError("Could not resolve the pinned Checkstyle probe engine")
        java = temporary / "F03Probe.java"
        java.write_text("class bad_name {}\n")
        results["java"] = probe(
            [
                "java",
                "-cp",
                classpath.read_text().strip(),
                "com.puppycrawl.tools.checkstyle.Main",
                "-c",
                str(ROOT / "config/checkstyle.xml"),
                str(java),
            ],
            "TypeName",
        )
        js = temporary / "format-probe.js"
        js.write_bytes(b"const value=1;\r\n")
        results["formatAndCrlf"] = probe(
            [
                "node",
                str(ROOT / "tools/quality/node_modules/.bin/prettier"),
                "--check",
                "--config",
                str(ROOT / ".prettierrc.json"),
                str(js),
            ],
            "format-probe.js",
        )
        # Keep the evidence independent of a private temporary directory or SDK cache path.
        for result in results.values():
            result["command"] = [
                part.replace(str(temporary), "<temporary>").replace(
                    str(ROOT), "<source>"
                )
                for part in result["command"]
            ]
            for key in ("diagnostic", "stderr"):
                result[key] = (
                    result[key]
                    .replace(str(temporary), "<temporary>")
                    .replace(str(ROOT), "<source>")
                )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps({"status": "passed", "probes": results}, indent=2) + "\n"
    )
    print("Temporary Hook, Python, TypeScript/MTS, Java, formatting/CRLF probes passed")


if __name__ == "__main__":

    def interrupted(_signal, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    main()
