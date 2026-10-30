#!/usr/bin/env python3
"""Audit final artifacts and fail closed on missing inventories or stale databases."""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import UTC, datetime, timedelta
from pathlib import Path
from zipfile import ZipFile

from quality_tools import CACHE, ROOT, TOOLS, provision


def utc(value):
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("Timestamp must include a timezone")
    return result.astimezone(UTC)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def exceptions(path, now):
    data = json.loads(path.read_text())
    if data.get("schemaVersion") != 1 or not isinstance(data.get("exceptions"), list):
        raise ValueError("Invalid audit exception policy")
    for item in data["exceptions"]:
        for field in (
            "id",
            "package",
            "version",
            "target",
            "reason",
            "owner",
            "reviewedAt",
            "expiresAt",
        ):
            if not isinstance(item.get(field), str) or not item[field].strip():
                raise ValueError(f"Exception is missing {field}")
        start, end = utc(item["reviewedAt"]), utc(item["expiresAt"])
        if not start <= now < end or not timedelta(0) < end - start <= timedelta(
            days=30
        ):
            raise ValueError(f"Expired or invalid exception: {item['id']}")
    return data["exceptions"]


def decision(findings, policy, target):
    blocked, accepted = [], []
    for finding in findings:
        if finding["severity"].upper() not in {"HIGH", "CRITICAL"}:
            continue
        match = next(
            (
                item
                for item in policy
                if all(
                    item[key] == value
                    for key, value in {
                        "id": finding["id"],
                        "package": finding["package"],
                        "version": finding["version"],
                        "target": target,
                    }.items()
                )
            ),
            None,
        )
        (accepted if match else blocked).append(
            {**finding, **({"exception": match} if match else {})}
        )
    return {
        "status": "blocked" if blocked else "passed",
        "blocking": blocked,
        "accepted": accepted,
    }


def trivy_inventory(report, expected_image=None):
    if report.get("SchemaVersion") != 2 or not isinstance(report.get("Results"), list):
        raise ValueError("Invalid or incomplete Trivy JSON")
    if expected_image and report.get("Metadata", {}).get("ImageID") != expected_image:
        raise ValueError("Trivy scanned a different image ID")
    packages, findings, paths = [], [], set()
    for result in report["Results"]:
        paths.add(result.get("Target", ""))
        for package in result.get("Packages", []):
            if not package.get("Name") or not package.get("Version"):
                raise ValueError("Trivy package has no name or version")
            packages.append(package)
            paths.add(package.get("FilePath", ""))
        for finding in result.get("Vulnerabilities", []):
            findings.append(
                {
                    "id": finding["VulnerabilityID"],
                    "package": finding["PkgName"],
                    "version": finding["InstalledVersion"],
                    "severity": finding["Severity"],
                    "fixedVersion": finding.get("FixedVersion"),
                    "status": finding.get("Status"),
                }
            )
    if not packages:
        raise ValueError("Trivy package inventory is empty")
    return packages, findings, paths


def verify_libraries(jar, paths):
    with ZipFile(jar) as archive:
        expected = [
            name
            for name in archive.namelist()
            if name.startswith("BOOT-INF/lib/") and name.endswith(".jar")
        ]
    if not expected:
        raise ValueError("Final JAR has no BOOT-INF/lib libraries")
    reported_paths = {
        path.replace("\\", "/").replace("!", "/").strip("/") for path in paths
    }
    detected = [
        name
        for name in expected
        if any(path == name or path.endswith("/" + name) for path in reported_paths)
    ]
    unidentified = sorted(set(expected) - set(detected))
    if unidentified:
        raise ValueError(
            f"Trivy BOOT-INF/lib inventory incomplete: {len(unidentified)} of "
            f"{len(expected)} libraries unidentified: {', '.join(unidentified)}"
        )
    return {
        "embedded": len(expected),
        "discovered": len(detected),
        "unidentifiedLibraries": unidentified,
    }


def databases(scanner, output):
    cache = CACHE / "trivy-db"
    records = {}
    for name, flag in (
        ("db", "--download-db-only"),
        ("java-db", "--download-java-db-only"),
    ):
        log = output / f"{name}-update.log"
        try:
            with log.open("w") as stream:
                code = subprocess.run(
                    [
                        *scanner,
                        "--cache-dir",
                        str(cache),
                        "image",
                        flag,
                        "--no-progress",
                    ],
                    stdout=stream,
                    stderr=subprocess.STDOUT,
                    check=False,
                    timeout=600,
                ).returncode
        except subprocess.TimeoutExpired:
            code = 124
        metadata_file = cache / name / "metadata.json"
        if not metadata_file.is_file():
            raise RuntimeError(
                f"{name}: update failed and no database metadata is available (exit {code})"
            )
        metadata = json.loads(metadata_file.read_text())
        age = datetime.now(UTC) - utc(metadata["UpdatedAt"])
        if not timedelta(0) <= age <= timedelta(hours=24):
            raise RuntimeError(
                f"{name}: database cache exceeds 24 hours or has a future timestamp"
            )
        records[name] = {
            "metadata": metadata,
            "metadataSha256": sha(metadata_file),
            "updateExit": code,
            "source": "updated" if code == 0 else "valid-cache",
            "ageSeconds": round(age.total_seconds()),
        }
    return cache, records


def scan(scanner, cache, kind, target, output):
    command = [
        *scanner,
        "--cache-dir",
        str(cache),
        kind,
        "--scanners",
        "vuln",
        "--list-all-pkgs",
        "--skip-db-update",
        "--skip-java-db-update",
        "--format",
        "json",
        "--no-progress",
        "--output",
        str(output),
        "--timeout",
        "10m",
    ]
    if kind == "image":
        command += ["--image-src", "docker"]
    command.append(str(target))
    with output.with_suffix(".log").open("w") as log:
        code = subprocess.run(
            command, stdout=log, stderr=subprocess.STDOUT, check=False, timeout=660
        ).returncode
    if code != 0:
        raise RuntimeError(f"Trivy operational failure (exit {code}): {output.name}")
    return json.loads(output.read_text())


def npm_reports(output, policy):
    results = []
    for name, directory in (
        ("frontend", ROOT / "frontend"),
        ("quality-tools", ROOT / "tools/quality"),
    ):
        lock = json.loads((directory / "package-lock.json").read_text())
        for scope in ("production", "pipeline"):
            if name == "quality-tools" and scope == "production":
                continue
            packages = {
                key: value
                for key, value in lock["packages"].items()
                if key and (scope == "pipeline" or not value.get("dev"))
            }
            if not packages:
                raise ValueError(f"Empty npm inventory: {name}:{scope}")
            command = ["npm", "audit", "--json", "--audit-level=high"] + (
                ["--omit=dev"] if scope == "production" else []
            )
            result = subprocess.run(
                command,
                cwd=directory,
                capture_output=True,
                text=True,
                check=False,
                timeout=300,
            )
            raw = output / f"npm-{name}-{scope}.json"
            raw.write_text(result.stdout)
            report = json.loads(result.stdout)
            if (
                result.returncode not in (0, 1)
                or report.get("auditReportVersion") != 2
                or "error" in report
                or not isinstance(report.get("vulnerabilities"), dict)
            ):
                raise RuntimeError(f"npm audit infrastructure error: {name}:{scope}")
            findings = []
            for package, vulnerability in report["vulnerabilities"].items():
                # Evaluate transitive impacts too, using the installed version from every affected node.
                for node in vulnerability["nodes"]:
                    if node not in packages:
                        raise ValueError(
                            f"npm finding absent from lock inventory: {node}"
                        )
                    for advisory in vulnerability["via"] or [f"unresolved:{package}"]:
                        advisory_id = (
                            str(advisory.get("source"))
                            if isinstance(advisory, dict)
                            else f"transitive:{advisory}"
                        )
                        findings.append(
                            {
                                "id": advisory_id,
                                "package": package,
                                "version": packages[node]["version"],
                                "severity": vulnerability["severity"],
                                "node": node,
                                "development": packages[node].get("dev", False),
                            }
                        )
            target = f"npm:{name}:{scope}"
            results.append(
                {
                    "target": target,
                    "lockSha256": sha(directory / "package-lock.json"),
                    "packages": len(packages),
                    "findings": findings,
                    "report": raw.name,
                    "command": command,
                    "scannerExit": result.returncode,
                    **decision(findings, policy, target),
                }
            )
    return results


def image_targets(manifest):
    app = json.loads(manifest.read_text())["imageId"]
    environment = {
        **os.environ,
        "NEWS_APP_IMAGE": app,
        "NEWS_DB_SECRET_FILE": "/nonexistent-f03-placeholder",
    }
    config = json.loads(
        subprocess.check_output(
            [
                "docker",
                "compose",
                "--env-file",
                "/dev/null",
                "-f",
                str(ROOT / "compose.app.yaml"),
                "config",
                "--format",
                "json",
            ],
            env=environment,
            text=True,
            timeout=30,
        )
    )
    targets = []
    for service in ("app", "db"):
        reference = config["services"][service]["image"]
        info = json.loads(
            subprocess.check_output(
                ["docker", "image", "inspect", reference], text=True, timeout=30
            )
        )[0]
        if service == "app" and info["Id"] != app:
            raise ValueError("Compose app image differs from the distribution")
        if service == "db" and (
            "@sha256:" not in reference
            or not any(
                reference.split("@", 1)[1] == digest.split("@", 1)[1]
                for digest in info.get("RepoDigests", [])
            )
        ):
            raise ValueError("PostgreSQL image is not the digest selected in Compose")
        targets.append((service, info["Id"], reference, info.get("RepoDigests", [])))
    return targets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--jar",
        action="append",
        default=[],
        help="normal=/absolute/final.jar or demo=...",
    )
    parser.add_argument("--images", type=Path, help="Distribution image.json")
    parser.add_argument("--npm", action="store_true")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    summary = {
        "utc": datetime.now(UTC).isoformat(),
        "status": "running",
        "targets": [],
        "limitations": "Known-vulnerability detection depends on scanner identification and database coverage; it does not establish absolute absence of vulnerabilities.",
    }
    code = 2
    try:
        policy = exceptions(ROOT / "config/audit-exceptions.json", datetime.now(UTC))
        if not args.npm and not args.jar and not args.images:
            raise ValueError("No audit targets selected")
        if args.npm:
            summary["npmVersion"] = subprocess.check_output(
                ["npm", "--version"], text=True, timeout=30
            ).strip()
            summary["targets"].extend(npm_reports(args.output, policy))
        if args.jar or args.images:
            scanner = provision("trivy")
            version = json.loads(
                subprocess.check_output(
                    [*scanner, "--version", "--format", "json"], text=True, timeout=30
                )
            )
            if version.get("Version") != TOOLS["trivy"]["version"]:
                raise ValueError("Unexpected Trivy version")
            summary["scanner"] = version
            cache, summary["databases"] = databases(scanner, args.output)
            for entry in args.jar:
                label, path = entry.split("=", 1)
                if label not in {"normal", "demo"}:
                    raise ValueError("Unknown JAR profile")
                jar = Path(path).resolve(strict=True)
                target = "jar:" + label
                raw = args.output / f"{label}.json"
                with tempfile.TemporaryDirectory(
                    prefix="caderno-final-jar-"
                ) as directory:
                    staged = Path(directory)
                    shutil.copy2(jar, staged / jar.name)
                    report = scan(scanner, cache, "rootfs", staged, raw)
                packages, findings, paths = trivy_inventory(report)
                libraries = verify_libraries(jar, paths)
                summary["targets"].append(
                    {
                        "target": target,
                        "sha256": sha(jar),
                        "packages": len(packages),
                        "libraries": libraries,
                        "findings": findings,
                        "report": raw.name,
                        **decision(findings, policy, target),
                    }
                )
            if args.images:
                for service, image_id, reference, digests in image_targets(args.images):
                    raw = args.output / f"image-{service}.json"
                    report = scan(scanner, cache, "image", image_id, raw)
                    packages, findings, _paths = trivy_inventory(report, image_id)
                    target = f"image:{service}:{image_id}"
                    summary["targets"].append(
                        {
                            "target": target,
                            "imageId": image_id,
                            "reference": reference,
                            "repoDigests": digests,
                            "packages": len(packages),
                            "findings": findings,
                            "report": raw.name,
                            **decision(findings, policy, target),
                        }
                    )
        code = (
            1 if any(item["status"] == "blocked" for item in summary["targets"]) else 0
        )
        summary["status"] = "blocked" if code else "passed"
    except (
        RuntimeError,
        ValueError,
        OSError,
        KeyError,
        subprocess.SubprocessError,
        TypeError,
        AttributeError,
        IndexError,
    ) as error:
        summary.update(status="infrastructure-error", error=str(error))
        print(str(error), file=sys.stderr)
    finally:
        (args.output / "decision.json").write_text(json.dumps(summary, indent=2) + "\n")
    return code


if __name__ == "__main__":
    sys.exit(main())
