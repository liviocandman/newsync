"""Regression checks for prerequisite diagnostics and the essential CI boundary."""

import contextlib
import io
import json
import os
import runpy
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import delivery
import reports
import verify_environment

ROOT = Path(__file__).resolve().parent.parent


class EnvironmentTests(unittest.TestCase):
    def test_shell_can_run_a_wrapper_without_an_executable_bit(self):
        with tempfile.TemporaryDirectory() as directory:
            wrapper = Path(directory) / "mvnw"
            wrapper.write_text("#!/bin/sh\nprintf 'Apache Maven synthetic\\n'\n")
            wrapper.chmod(0o644)
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertIn(
                    "Apache Maven",
                    verify_environment.command("sh", str(wrapper), "--version"),
                )

    def test_failed_command_preserves_the_tool_diagnostic(self):
        output = io.StringIO()
        with (
            patch.object(
                verify_environment.subprocess,
                "run",
                return_value=subprocess.CompletedProcess(
                    ["docker"], 1, "Synthetic daemon unavailable\n"
                ),
            ),
            contextlib.redirect_stdout(output),
            self.assertRaisesRegex(SystemExit, "exit 1.*docker"),
        ):
            verify_environment.command("docker", "version")
        self.assertIn("Synthetic daemon unavailable", output.getvalue())

    def test_prerequisites_use_shell_and_reserve_compose_for_distribution(self):
        for distribution in (False, True):
            with self.subTest(distribution=distribution):
                commands = []

                def command(*args, _commands=commands, **_kwargs):
                    _commands.append(args)
                    return {
                        "java": 'openjdk version "25.0.5"\n',
                        "node": "v24.22.1\n",
                        "npm": "11.19.1\n",
                        "docker": "Docker Compose v2.40.0\n"
                        if "compose" in args
                        else "28.5.1\n",
                        "sh": "Apache Maven 3.9.11\n",
                    }[args[0]]

                with (
                    patch.object(verify_environment, "command", side_effect=command),
                    contextlib.redirect_stdout(io.StringIO()),
                ):
                    versions = verify_environment.inspect(distribution)
                self.assertEqual("compose" in versions, distribution)
                self.assertEqual(commands[-1][0], "sh")
                self.assertTrue(commands[-1][1].endswith("backend/mvnw"))


class CiGateTests(unittest.TestCase):
    def run_ci(self, root, environment_failure=False, mode="ci", backend_failure=False):
        for directory in ("frontend", "tools/quality"):
            path = root / directory
            path.mkdir(parents=True)
            (path / "package.json").write_text('{"devDependencies": {}}\n')
        (root / "backend").mkdir()
        (root / "backend/pom.xml").write_text("<project/>\n")
        gate = runpy.run_path(str(ROOT / "scripts/verify"))
        gate["main"].__globals__["ROOT"] = root
        commands = []
        hashes = {"normal": "normal-hash", "demo": "demo-hash"}

        def write(path, value):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(value))

        def start(command, **kwargs):
            commands.append(command)
            code = 0
            if len(command) > 1 and command[1].endswith("verify_environment.py"):
                write(Path(command[2]), {})
                if environment_failure:
                    kwargs["stdout"].write("Synthetic prerequisite failure\n")
                    code = 1
            elif command[:2] == ["sh", "./mvnw"]:
                target = root / "backend/target"
                shutil.rmtree(target, ignore_errors=True)
                target.mkdir()
                (target / "news.jar").write_bytes(b"synthetic JAR")
                if "verify" in command:
                    write(
                        target / "t20-result.json",
                        {"status": "passed", "toolExits": {"synthetic": 0}},
                    )
                    (target / "spotbugsXml.xml").write_text("<BugCollection/>\n")
            elif len(command) > 1 and command[1].endswith("dependency_audit.py"):
                output = Path(command[command.index("--output") + 1])
                targets = []
                for index, argument in enumerate(command):
                    if argument == "--jar":
                        profile = command[index + 1].split("=", 1)[0]
                        targets.append(
                            {"target": "jar:" + profile, "sha256": hashes[profile]}
                        )
                write(
                    output / "decision.json", {"status": "passed", "targets": targets}
                )
            elif len(command) > 1 and command[1].endswith("scan_secrets.py"):
                write(Path(command[2]), [])
            elif len(command) > 1 and command[1].endswith("quality_probes.py"):
                write(
                    Path(command[command.index("--output") + 1]), {"status": "passed"}
                )
            elif command[:3] == ["npm", "run", "test:e2e"]:
                output = root / "frontend/test-results"
                for profile in ("normal", "demo", "packaged-normal", "packaged-demo"):
                    write(output / f"playwright-{profile}.json", {})
                write(
                    output / "processes.json",
                    {
                        "runId": "synthetic-ci",
                        "viteExited": True,
                        "playwrightExited": True,
                    },
                )

            class Process:
                def wait(self, timeout=None):
                    return code

            return Process()

        output = io.StringIO()
        with (
            patch.dict(os.environ, {"NEWS_E2E_RUN_ID": "synthetic-ci"}),
            patch.object(subprocess, "Popen", side_effect=start),
            patch.object(delivery, "files", return_value=[]),
            patch.object(delivery, "hashes", return_value="synthetic-manifest\n"),
            patch.object(
                reports,
                "backend",
                side_effect=RuntimeError("Synthetic missing integration suite")
                if backend_failure
                else None,
                return_value={"tests": 1},
            ),
            patch.object(reports, "coverage_java", return_value={}),
            patch.object(
                reports,
                "jar",
                side_effect=lambda _path, _folder, profile: {"sha256": hashes[profile]},
            ),
            patch.object(reports, "vitest", return_value={"tests": 1}),
            patch.object(reports, "coverage_frontend", return_value={}),
            patch.object(reports, "playwright", return_value={"tests": 1}),
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(output),
        ):
            code = gate["main"](["--mode", mode, "--prepare-browser"])
        summary = json.loads(
            next((root / "artifacts").glob("*/summary.json")).read_text()
        )
        return code, summary, commands, output.getvalue()

    def test_ci_runs_essential_checks_without_distribution_rehearsal(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, commands, _output = self.run_ci(Path(directory))
        self.assertEqual(code, 0)
        self.assertEqual(summary["mode"], "ci")
        self.assertEqual(summary["acceptance"], "continuous-integration")
        stages = {stage["name"] for stage in summary["stages"]}
        self.assertTrue(
            {
                "backend-normal",
                "backend-demo",
                "audit-npm",
                "audit-jars",
                "vitest",
                "playwright",
                "cleanup",
            }
            <= stages
        )
        self.assertFalse(
            {
                "image",
                "installation",
                "audit-images",
                "metrics",
                "quality-probes",
                "source-snapshot",
                "cleanup-installation",
            }
            & stages
        )
        builds = [command for command in commands if command[:2] == ["sh", "./mvnw"]]
        self.assertEqual(len(builds), 2)
        self.assertIn("verify", builds[0])
        self.assertNotIn("-DskipTests", builds[0])
        self.assertIn("package", builds[1])
        self.assertIn("-Pdemo", builds[1])
        audit = next(
            command
            for command in commands
            if "scripts/dependency_audit.py" in command and "--jar" in command
        )
        self.assertEqual(audit.count("--jar"), 1)
        self.assertTrue(audit[audit.index("--jar") + 1].startswith("normal="))

    def test_environment_failure_is_visible_without_unrelated_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, commands, output = self.run_ci(
                Path(directory), environment_failure=True
            )
        self.assertEqual(code, 1)
        self.assertEqual(summary["status"], "failed")
        self.assertIn("Synthetic prerequisite failure", output)
        self.assertEqual(len(commands), 1)
        self.assertNotIn("cleanupError", summary)

    def test_missing_backend_suite_cannot_be_approved_in_ci(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, _commands, _output = self.run_ci(
                Path(directory), backend_failure=True
            )
        self.assertEqual(code, 1)
        self.assertIn("Synthetic missing integration suite", summary["error"])
        self.assertNotIn("backend-demo", {stage["name"] for stage in summary["stages"]})

    def test_full_mode_keeps_its_additional_validation_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            code, summary, commands, _output = self.run_ci(
                Path(directory), mode="full", backend_failure=True
            )
        self.assertEqual(code, 1)
        self.assertEqual(summary["acceptance"], "distribution")
        stages = {stage["name"] for stage in summary["stages"]}
        self.assertTrue(
            {"quality-probes", "source-snapshot", "snapshot-links"} <= stages
        )
        self.assertIn("--distribution", commands[0])


if __name__ == "__main__":
    unittest.main()
