"""Validate gate artifacts; missing, aborted, or skipped results never count as a pass."""

import hashlib
import json
import re
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

REQUIRED = json.loads(Path(__file__).with_name("required-tests.json").read_text())


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def backend(folder):
    totals = {}
    for runner in ("surefire", "failsafe"):
        found = {}
        count = 0
        for path in sorted((folder / f"{runner}-reports").glob("TEST-*.xml")):
            suite = ET.parse(path).getroot()
            cases = suite.findall("testcase")
            require(cases, f"Suíte vazia: {path.name}")
            for attribute in ("failures", "errors", "skipped"):
                require(
                    int(suite.get(attribute, "0")) == 0, f"{path.name}: {attribute}"
                )
            methods = Counter()
            for case in cases:
                require(
                    not any(
                        case.find(tag) is not None
                        for tag in ("skipped", "failure", "error")
                    ),
                    f"Cenário não aprovado: {path.name}: {case.get('name')}",
                )
                methods[case.get("name").split("(")[0].split("[")[0]] += 1
            found[suite.get("name")] = methods
            count += len(cases)
        for name, methods in REQUIRED[runner].items():
            require(name in found, f"Suíte obrigatória ausente: {runner}: {name}")
            for method, minimum in methods.items():
                require(
                    found[name][method] >= minimum,
                    f"Cenário ausente: {name}.{method} (mínimo {minimum})",
                )
        require(count > 0, f"Runner vazio: {runner}")
        totals[runner] = {
            "tests": count,
            "failures": 0,
            "skips": 0,
            "suites": sorted(found),
        }
    summary = ET.parse(folder / "failsafe-reports/failsafe-summary.xml").getroot()
    require(summary.get("timeout") == "false", "Failsafe cancelado/timeout")
    for field in ("errors", "failures", "skipped", "flakes"):
        require(summary.findtext(field) == "0", f"Failsafe: {field}")
    require(
        int(summary.findtext("completed", "0")) == totals["failsafe"]["tests"],
        "Failsafe incompleto",
    )
    return totals


def jar(path, folder, profile):
    with ZipFile(path) as archive:
        entries = sorted(archive.namelist())
    prefix = "BOOT-INF/classes/demo/"
    demo = [entry for entry in entries if entry.startswith(prefix)]
    if profile == "normal":
        require(not demo, "JAR normal contém recursos demo")
    else:
        require(
            [entry for entry in demo if not entry.endswith("/")]
            == [prefix + "feed.xml"],
            "JAR demo deve conter somente a fixture fixa",
        )
    with ZipFile(path) as archive:
        prefix = "BOOT-INF/classes/web-ui/"
        require(
            prefix + "bundle.properties" in entries
            and prefix + "index.html" in entries,
            "JAR sem UI empacotada",
        )
        properties = dict(
            line.split("=", 1)
            for line in archive.read(prefix + "bundle.properties").decode().splitlines()
        )
        require(
            properties.get("mode") == profile, "UI incompatível com o perfil do JAR"
        )
        assets = properties.get("assets", "").split(",")
        require(
            any(name.endswith(".js") for name in assets)
            and any(name.endswith(".css") for name in assets)
            and all(
                re.fullmatch(r"[a-zA-Z0-9_-]+-[a-zA-Z0-9_-]{8,}\.(js|css)", name)
                and prefix + "assets/" + name in entries
                for name in assets
            ),
            "Inventário UI incompleto",
        )
        html = archive.read(prefix + "index.html").decode()
        require(
            f'name="news-ui-mode" content="{profile}"' in html
            and not re.search(
                r"nonce=|@vite|<style|<script(?![^>]*\bsrc=)", html, re.I
            ),
            "HTML incompatível com CSP produtiva",
        )
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    (folder / "jar-inventory.txt").write_text("\n".join(entries) + "\n")
    (folder / "jar.sha256").write_text(digest + "  news.jar\n")
    return {
        "sha256": digest,
        "demoEntries": demo,
        "entries": len(entries),
        "uiMode": profile,
        "uiAssets": assets,
    }


def vitest(path):
    report = json.loads(path.read_text())
    require(report.get("success") is True, "Vitest não concluiu com sucesso")
    suites = report.get("testResults", [])
    count = 0
    found = {}
    for suite in suites:
        assertions = suite.get("assertionResults", [])
        require(
            suite.get("status") == "passed" and assertions,
            "Vitest: arquivo vazio ou cancelado",
        )
        require(
            all(case.get("status") == "passed" for case in assertions),
            "Vitest: falha/skip/abort",
        )
        found[suite["name"].replace("\\", "/")] = {
            case["fullName"] for case in assertions
        }
        count += len(assertions)
    for name, methods in REQUIRED["vitest"].items():
        matches = [tests for file, tests in found.items() if file.endswith("/" + name)]
        require(
            len(matches) == 1 and set(methods) <= matches[0],
            f"Vitest: cenários ausentes em {name}",
        )
    require(
        count > 0
        and report["numTotalTests"] == count
        and report["numPassedTests"] == count,
        "Vitest: contagem incompleta",
    )
    return {"tests": count, "failures": 0, "skips": 0, "suites": sorted(found)}


def playwright(path, profile="demo"):
    report = json.loads(path.read_text())
    require(not report.get("errors"), "Playwright: erro do runner")
    stats = report["stats"]
    require(
        stats["expected"] > 0
        and all(stats[key] == 0 for key in ("unexpected", "skipped", "flaky")),
        "Playwright: falha/skip/flaky ou descoberta vazia",
    )
    found = []

    def visit(suite):
        for spec in suite.get("specs", []):
            for test in spec["tests"]:
                require(
                    test["expectedStatus"] == "passed"
                    and test["status"] == "expected"
                    and len(test["results"]) == 1
                    and test["results"][0]["status"] == "passed",
                    "Playwright: cenário incompleto ou não aprovado",
                )
                found.append(spec["title"])
        for child in suite.get("suites", []):
            visit(child)

    for suite in report["suites"]:
        visit(suite)
    required = REQUIRED["playwright"][profile]
    if isinstance(required, str):
        required = [required]
    require(set(required) <= set(found), "Playwright: percurso central ausente")
    require(len(found) == stats["expected"], "Playwright: contagem incompleta")
    return {"tests": len(found), "failures": 0, "skips": 0, "scenarios": found}


def coverage_java(folder):
    result = {}
    for name in ("unit", "integration"):
        report = folder / ("coverage-" + name) / "jacoco.xml"
        root = ET.parse(report).getroot()
        classes = root.findall("./package/class")
        counters = {
            item.get("type"): {key: int(item.get(key)) for key in ("missed", "covered")}
            for item in root.findall("counter")
        }
        require(
            classes and counters.get("LINE") and sum(counters["LINE"].values()) > 0,
            f"Empty JaCoCo {name} inventory",
        )
        require((report.parent / "index.html").is_file(), f"JaCoCo {name} HTML missing")
        result[name] = {
            "classes": len(classes),
            "counters": counters,
            "report": str(report.relative_to(folder)),
        }
    result["limitations"] = (
        "Only the Surefire/Failsafe JVMs are instrumented. Packaged JAR, Docker, and other subprocesses are not instrumented or attributed to this coverage."
    )
    metrics = ET.parse(folder / "checkstyle-metrics.xml").getroot()
    units = metrics.findall("./file/error")
    require(units, "Missing or empty Java complexity report")
    result["complexity"] = {
        "status": "diagnostic",
        "measurements": len(units),
        "report": "checkstyle-metrics.xml",
    }
    return result


def coverage_frontend(folder, source):
    summary = json.loads((folder / "coverage-summary.json").read_text())
    inventory = json.loads((folder / "coverage-final.json").read_text())
    expected = {
        p.resolve()
        for p in source.rglob("*")
        if p.suffix in (".ts", ".tsx")
        and ".test." not in p.name
        and p.name not in ("main.tsx", "test-setup.ts")
    }
    discovered = {Path(name).resolve() for name in inventory}
    require(
        expected and expected <= discovered,
        f"Vitest coverage missing source files: {sorted(str(p) for p in expected - discovered)}",
    )
    for metric in ("lines", "functions", "branches"):
        require(
            metric in summary["total"] and summary["total"][metric]["total"] > 0,
            f"Vitest coverage missing {metric}",
        )
    require(
        (folder / "index.html").is_file() and (folder / "lcov.info").is_file(),
        "Vitest HTML/LCOV missing",
    )
    return {
        "files": len(discovered),
        "counters": summary["total"],
        "exclusions": [
            "src/main.tsx: DOM bootstrap",
            "src/test-setup.ts: test infrastructure",
            "**/*.test.ts(x): tests",
        ],
    }
