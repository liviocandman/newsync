"""Negative checks for delivery membership and vulnerability gate decisions."""

import contextlib
import io
import json
import runpy
import subprocess
import tempfile
import unittest
from datetime import UTC, datetime, timedelta
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

import delivery
import dependency_audit as audit


class NpmGateTests(unittest.TestCase):
    def run_gate(self, root, approved):
        lock = {
            "packages": {"": {}, "node_modules/synthetic-runtime": {"version": "1.0.0"}}
        }
        for directory in ("frontend", "tools/quality"):
            path = root / directory
            path.mkdir(parents=True)
            (path / "package.json").write_text(json.dumps({"devDependencies": {}}))
            (path / "package-lock.json").write_text(json.dumps(lock))
        (root / "backend").mkdir()
        (root / "backend/pom.xml").write_text("<project/>\n")
        (root / "config").mkdir()
        now = datetime.now(UTC)
        policy = (
            [
                {
                    "id": "1234",
                    "package": "synthetic-runtime",
                    "version": "1.0.0",
                    "target": target,
                    "reason": "Synthetic policy regression fixture",
                    "owner": "synthetic-test-owner",
                    "reviewedAt": (now - timedelta(minutes=1)).isoformat(),
                    "expiresAt": (now + timedelta(days=1)).isoformat(),
                }
                for target in ("npm:frontend:production", "npm:frontend:pipeline")
            ]
            if approved
            else []
        )
        (root / "config/audit-exceptions.json").write_text(
            json.dumps({"schemaVersion": 1, "exceptions": policy})
        )
        gate = runpy.run_path(str(audit.ROOT / "scripts/verify"))
        gate["main"].__globals__["ROOT"] = root
        npm_commands = []

        def npm_scan(command, **kwargs):
            npm_commands.append(command)
            affected = kwargs["cwd"] == root / "frontend"
            report = {
                "auditReportVersion": 2,
                "vulnerabilities": {
                    "synthetic-runtime": {
                        "nodes": ["node_modules/synthetic-runtime"],
                        "via": [{"source": 1234}],
                        "severity": "high",
                    }
                }
                if affected
                else {},
            }
            return subprocess.CompletedProcess(
                command, int(affected), json.dumps(report), ""
            )

        def start(command, **_kwargs):
            code = 0
            if command[:2] == ["npm", "audit"]:
                code = 1
            elif command[:2] == ["sh", "./mvnw"] and "verify" in command:
                # Stop after the audit boundary; this test never starts backend or Docker processes.
                code = 73
            elif len(command) > 1 and command[1].endswith("verify_environment.py"):
                Path(command[2]).write_text("{}\n")
            elif len(command) > 1 and command[1].endswith("quality_probes.py"):
                Path(command[command.index("--output") + 1]).write_text("{}\n")
            elif len(command) > 1 and command[1].endswith("scan_secrets.py"):
                Path(command[2]).write_text("[]\n")
            elif len(command) > 1 and command[1].endswith("dependency_audit.py"):
                with (
                    patch("sys.argv", command[1:]),
                    patch.object(audit, "ROOT", root),
                    patch.object(audit.subprocess, "run", side_effect=npm_scan),
                    patch.object(
                        audit.subprocess, "check_output", return_value="11.12.1\n"
                    ),
                ):
                    code = audit.main()

            class Process:
                def wait(self, timeout=None):
                    return code

            return Process()

        with (
            patch.object(subprocess, "Popen", side_effect=start),
            patch.object(delivery, "files", return_value=[]),
            patch.object(
                delivery, "hashes", return_value="synthetic-source-manifest\n"
            ),
            contextlib.redirect_stdout(io.StringIO()),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(gate["main"](), 1)
        summary = json.loads(
            next((root / "artifacts").glob("*/summary.json")).read_text()
        )
        decision = json.loads(
            next((root / "artifacts").glob("*/audit-npm/decision.json")).read_text()
        )
        self.assertTrue(any("--omit=dev" in command for command in npm_commands))
        return summary, decision

    def test_valid_npm_exceptions_reach_backend_despite_raw_scanner_exit_one(self):
        with tempfile.TemporaryDirectory() as directory:
            summary, decision = self.run_gate(Path(directory), approved=True)
        self.assertEqual(summary["results"]["auditNpm"]["status"], "passed")
        self.assertIn(
            ("backend-normal", 73),
            [(stage["name"], stage["exitCode"]) for stage in summary["stages"]],
        )
        production = next(
            target
            for target in decision["targets"]
            if target["target"] == "npm:frontend:production"
        )
        self.assertEqual(production["scannerExit"], 1)
        self.assertEqual(production["status"], "passed")
        self.assertEqual(len(production["accepted"]), 1)

    def test_npm_finding_without_exception_stops_gate_before_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            summary, decision = self.run_gate(Path(directory), approved=False)
        self.assertEqual(decision["status"], "blocked")
        self.assertIn(
            ("audit-npm", 1),
            [(stage["name"], stage["exitCode"]) for stage in summary["stages"]],
        )
        self.assertFalse(
            any(stage["name"].startswith("backend-") for stage in summary["stages"])
        )


class AuditTests(unittest.TestCase):
    def test_successful_refresh_accepts_upstream_database_publication_cadence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("db", "java-db"):
                target = root / "trivy-db" / name
                target.mkdir(parents=True)
                (target / "metadata.json").write_text(
                    json.dumps(
                        {
                            "UpdatedAt": (
                                datetime.now(UTC) - timedelta(days=3)
                            ).isoformat()
                        }
                    )
                )
            output = root / "reports"
            output.mkdir()
            with (
                patch.object(audit, "CACHE", root),
                patch.object(
                    audit.subprocess,
                    "run",
                    return_value=subprocess.CompletedProcess([], 0),
                ),
            ):
                _cache, records = audit.databases(["synthetic-trivy"], output)
            self.assertEqual(records["java-db"]["source"], "updated")
            self.assertLess(records["java-db"]["ageSeconds"], 5)

    def test_failed_refresh_rejects_stale_or_changed_cache_confirmation(self):
        for stale, changed in ((True, False), (False, True)):
            with (
                self.subTest(stale=stale, changed=changed),
                tempfile.TemporaryDirectory() as directory,
            ):
                root = Path(directory)
                target = root / "trivy-db/db"
                target.mkdir(parents=True)
                metadata = target / "metadata.json"
                metadata.write_text(
                    json.dumps(
                        {
                            "UpdatedAt": (
                                datetime.now(UTC) - timedelta(days=3)
                            ).isoformat()
                        }
                    )
                )
                (target / "checked.json").write_text(
                    json.dumps(
                        {
                            "checkedAt": (
                                datetime.now(UTC) - timedelta(hours=25 if stale else 1)
                            ).isoformat(),
                            "metadataSha256": "changed"
                            if changed
                            else audit.sha(metadata),
                        }
                    )
                )
                output = root / "reports"
                output.mkdir()
                with (
                    patch.object(audit, "CACHE", root),
                    patch.object(
                        audit.subprocess,
                        "run",
                        return_value=subprocess.CompletedProcess([], 1),
                    ),
                    self.assertRaisesRegex(RuntimeError, "confirmed database cache"),
                ):
                    audit.databases(["synthetic-trivy"], output)

    def test_partially_identified_jar_cannot_pass_even_at_nine_of_ten(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "synthetic.jar"
            with ZipFile(jar, "w") as archive:
                for i in range(10):
                    archive.writestr(f"BOOT-INF/lib/library-{i}.jar", b"synthetic")
            for count in (5, 9):
                with self.subTest(discovered=count):
                    paths = {
                        f"synthetic.jar/BOOT-INF/lib/library-{i}.jar"
                        for i in range(count)
                    }
                    with self.assertRaisesRegex(ValueError, "library-9.jar"):
                        audit.verify_libraries(jar, paths)

    def test_library_filename_substring_is_not_identification(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "synthetic.jar"
            with ZipFile(jar, "w") as archive:
                archive.writestr("BOOT-INF/lib/library.jar", b"synthetic")
            for path in (
                "synthetic.jar/BOOT-INF/lib/other-library.jar",
                "synthetic.jar/nested/library.jar",
            ):
                with (
                    self.subTest(path=path),
                    self.assertRaisesRegex(
                        ValueError, "unidentified.*BOOT-INF/lib/library.jar"
                    ),
                ):
                    audit.verify_libraries(jar, {path})

    def test_fully_identified_embedded_inventory_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "synthetic.jar"
            with ZipFile(jar, "w") as archive:
                for i in range(10):
                    archive.writestr(f"BOOT-INF/lib/library-{i}.jar", b"synthetic")
            result = audit.verify_libraries(
                jar, {f"synthetic.jar/BOOT-INF/lib/library-{i}.jar" for i in range(10)}
            )
            self.assertEqual(
                result, {"embedded": 10, "discovered": 10, "unidentifiedLibraries": []}
            )

    def test_incomplete_jar_inventory_fails_the_audit_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / "synthetic.jar"
            with ZipFile(jar, "w") as archive:
                for i in range(10):
                    archive.writestr(f"BOOT-INF/lib/library-{i}.jar", b"synthetic")
            report = {
                "SchemaVersion": 2,
                "Results": [
                    {
                        "Target": "Java",
                        "Packages": [
                            {
                                "Name": f"library-{i}",
                                "Version": "1",
                                "FilePath": f"synthetic.jar/BOOT-INF/lib/library-{i}.jar",
                            }
                            for i in range(5)
                        ],
                    }
                ],
            }
            with (
                patch(
                    "sys.argv",
                    [
                        "audit",
                        "--output",
                        str(root / "report"),
                        "--jar",
                        f"normal={jar}",
                    ],
                ),
                patch.object(audit, "provision", return_value=["synthetic-trivy"]),
                patch.object(
                    audit.subprocess,
                    "check_output",
                    return_value=json.dumps(
                        {"Version": audit.TOOLS["trivy"]["version"]}
                    ),
                ),
                patch.object(audit, "databases", return_value=(root / "cache", {})),
                patch.object(audit, "scan", return_value=report),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                self.assertEqual(audit.main(), 2)
            result = json.loads((root / "report/decision.json").read_text())
            self.assertEqual(result["status"], "infrastructure-error")
            self.assertIn("5 of 10 libraries unidentified", result["error"])

    def test_high_finding_blocks_without_exception(self):
        finding = {
            "id": "CVE-SYNTHETIC",
            "package": "synthetic",
            "version": "1",
            "severity": "HIGH",
        }
        self.assertEqual(
            audit.decision([finding], [], "jar:normal")["status"], "blocked"
        )

    def test_exception_requires_exact_target_and_version(self):
        finding = {
            "id": "CVE-SYNTHETIC",
            "package": "synthetic",
            "version": "1",
            "severity": "CRITICAL",
        }
        policy = [{**finding, "target": "jar:normal"}]
        self.assertEqual(
            audit.decision([finding], policy, "jar:normal")["status"], "passed"
        )
        self.assertEqual(
            audit.decision([finding], policy, "jar:demo")["status"], "blocked"
        )

    def test_expired_and_overlong_exceptions_are_rejected(self):
        now = datetime.now(UTC)
        for start, end in (
            (now - timedelta(days=2), now - timedelta(days=1)),
            (now, now + timedelta(days=31)),
        ):
            with self.subTest(end=end), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "exceptions.json"
                item = {
                    key: "synthetic-test-data"
                    for key in ("id", "package", "version", "target", "reason", "owner")
                }
                item.update(reviewedAt=start.isoformat(), expiresAt=end.isoformat())
                path.write_text(json.dumps({"schemaVersion": 1, "exceptions": [item]}))
                with self.assertRaisesRegex(ValueError, "Expired or invalid"):
                    audit.exceptions(path, now)

    def test_missing_and_invalid_inventories_are_not_clean_scans(self):
        for report in ({}, {"SchemaVersion": 2, "Results": []}):
            with self.subTest(report=report), self.assertRaises(ValueError):
                audit.trivy_inventory(report)
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "synthetic.jar"
            with ZipFile(jar, "w") as archive:
                archive.writestr("BOOT-INF/lib/synthetic-1.jar", b"test")
            with self.assertRaisesRegex(ValueError, "BOOT-INF/lib"):
                audit.verify_libraries(jar, {"outer.jar"})

    def test_scanner_error_is_an_infrastructure_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with (
                patch(
                    "sys.argv",
                    ["audit", "--output", str(output), "--jar", "normal=synthetic.jar"],
                ),
                patch.object(
                    audit,
                    "provision",
                    side_effect=RuntimeError("synthetic scanner error"),
                ),
            ):
                self.assertEqual(audit.main(), 2)
            self.assertEqual(
                json.loads((output / "decision.json").read_text())["status"],
                "infrastructure-error",
            )


class DeliveryTests(unittest.TestCase):
    def test_local_planning_documents_are_not_required_by_source_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            (root / "scripts").mkdir()
            (root / "docs").mkdir()
            (root / "scripts/main.py").write_text("print('synthetic')\n")
            (root / "docs/local-review.md").write_text("# Local planning\n")
            names = [delivery.MANIFEST, "scripts/main.py"]
            (root / delivery.MANIFEST).write_text(
                json.dumps({"schemaVersion": 1, "files": names})
            )
            self.assertEqual(delivery.files(root), sorted(names))
            target = root / "artifacts/snapshot"
            delivery.snapshot(target, root)
            self.assertFalse((target / "docs").exists())

    def test_new_maintained_source_cannot_be_omitted_from_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            (root / "scripts").mkdir()
            (root / "scripts/unlisted.py").write_text("print('synthetic')\n")
            (root / delivery.MANIFEST).write_text(
                json.dumps({"schemaVersion": 1, "files": [delivery.MANIFEST]})
            )
            with self.assertRaisesRegex(RuntimeError, "scripts/unlisted.py"):
                delivery.files(root)

    def test_explicit_files_are_required_even_when_other_files_exist(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "config").mkdir()
            (root / "present.md").write_text("# Present\n")
            (root / delivery.MANIFEST).write_text(
                json.dumps({"schemaVersion": 1, "files": ["missing.md"]})
            )
            with self.assertRaisesRegex(RuntimeError, "missing"):
                delivery.files(root)


if __name__ == "__main__":
    unittest.main()
