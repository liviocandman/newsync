import json
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

import reports


class ReportGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for runner in ("surefire", "failsafe"):
            folder = self.root / f"{runner}-reports"
            folder.mkdir()
            count = 0
            for name, methods in reports.REQUIRED[runner].items():
                suite = ET.Element("testsuite", name=name)
                for method, minimum in methods.items():
                    for index in range(minimum):
                        ET.SubElement(suite, "testcase", name=f"{method}(args)[{index}]")
                        count += 1
                ET.ElementTree(suite).write(folder / f"TEST-{name}.xml")
            if runner == "failsafe":
                summary = ET.Element("failsafe-summary", timeout="false")
                for key, value in dict(completed=count, errors=0, failures=0, skipped=0, flakes=0).items():
                    ET.SubElement(summary, key).text = str(value)
                ET.ElementTree(summary).write(folder / "failsafe-summary.xml")

    def test_valid_reports_then_missing_integration_suite_fails(self):
        self.assertEqual(reports.backend(self.root)["failsafe"]["tests"],
                         sum(sum(methods.values()) for methods in reports.REQUIRED["failsafe"].values()))
        (self.root / "failsafe-reports/TEST-br.com.agregador.DemoConfigurationIT.xml").unlink()
        with self.assertRaisesRegex(RuntimeError, "Suíte obrigatória ausente"):
            reports.backend(self.root)

    def test_skipped_parameterized_case_and_missing_negative_fail(self):
        path = self.root / "failsafe-reports/TEST-br.com.agregador.DemoConfigurationIT.xml"
        tree = ET.parse(path)
        case = tree.getroot().findall("testcase")[-1]
        ET.SubElement(case, "skipped")
        tree.write(path)
        with self.assertRaisesRegex(RuntimeError, "Cenário não aprovado"):
            reports.backend(self.root)
        tree.getroot().remove(case)
        tree.write(path)
        with self.assertRaisesRegex(RuntimeError, "Cenário ausente"):
            reports.backend(self.root)

    def test_demo_leak_and_extra_demo_resource_fail(self):
        path = self.root / "news.jar"
        with ZipFile(path, "w") as archive:
            archive.writestr("BOOT-INF/classes/demo/feed.xml", "fixture")
        with self.assertRaisesRegex(RuntimeError, "normal contém"):
            reports.jar(path, self.root, "normal")
        reports.jar(path, self.root, "demo")
        with ZipFile(path, "a") as archive:
            archive.writestr("BOOT-INF/classes/demo/extra.xml", "extra")
        with self.assertRaisesRegex(RuntimeError, "somente a fixture"):
            reports.jar(path, self.root, "demo")

    def test_positive_total_does_not_replace_vitest_scenarios(self):
        path = self.root / "vitest.json"
        path.write_text(json.dumps({"success": True, "testResults": []}))
        with self.assertRaisesRegex(RuntimeError, "cenários ausentes"):
            reports.vitest(path)

    def test_playwright_skip_is_rejected(self):
        path = self.root / "playwright.json"
        path.write_text(json.dumps({"stats": {"expected": 1, "unexpected": 0, "skipped": 1, "flaky": 0}}))
        with self.assertRaisesRegex(RuntimeError, "falha/skip"):
            reports.playwright(path)

    def test_missing_parser_suite_is_not_hidden_by_integration_total(self):
        (self.root / "surefire-reports/TEST-br.com.agregador.ingestion.FeedParserTest.xml").unlink()
        with self.assertRaisesRegex(RuntimeError, "Suíte obrigatória ausente"):
            reports.backend(self.root)

    def test_failsafe_timeout_rejects_otherwise_passing_reports(self):
        path = self.root / "failsafe-reports/failsafe-summary.xml"
        tree = ET.parse(path)
        tree.getroot().set("timeout", "true")
        tree.write(path)
        with self.assertRaisesRegex(RuntimeError, "cancelado/timeout"):
            reports.backend(self.root)

    def test_vitest_pending_case_is_rejected(self):
        suites = [{"name": "/frontend/" + name, "status": "passed",
                   "assertionResults": [{"fullName": method, "status": "passed"} for method in methods]}
                  for name, methods in reports.REQUIRED["vitest"].items()]
        count = sum(len(methods) for methods in reports.REQUIRED["vitest"].values())
        data = {"success": True, "testResults": suites, "numTotalTests": count, "numPassedTests": count}
        path = self.root / "vitest.json"
        path.write_text(json.dumps(data))
        self.assertEqual(reports.vitest(path)["tests"], count)
        suites[-1]["assertionResults"][-1]["status"] = "pending"
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(RuntimeError, "falha/skip/abort"):
            reports.vitest(path)

    def test_playwright_positive_total_does_not_replace_central_path(self):
        data = {"stats": {"expected": 1, "unexpected": 0, "skipped": 0, "flaky": 0}, "suites": [
            {"specs": [{"title": "outro teste", "tests": [{"expectedStatus": "passed", "status": "expected",
                                                        "results": [{"status": "passed"}]}]}]}]}
        path = self.root / "playwright.json"
        path.write_text(json.dumps(data))
        with self.assertRaisesRegex(RuntimeError, "percurso central ausente"):
            reports.playwright(path)


if __name__ == "__main__":
    unittest.main()
