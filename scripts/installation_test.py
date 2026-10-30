#!/usr/bin/env python3
"""T-19: actual image, isolated Compose, upgrade and recovery into a new database."""

import argparse
import hashlib
import http.cookiejar
import json
import os
import re
import signal
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

import backup
import build_image
import reports

ROOT = Path(__file__).resolve().parent.parent
NODE_IMAGE = "node:24.22.1-bookworm-slim@sha256:be8204d4bd327363357f532187b752ff62759c31bedf98e9e18fc08c9cbd605c"


def command(*args, env=None, check=True, cwd=None):
    result = subprocess.run(args, env=env, cwd=cwd, capture_output=True, timeout=240)
    reports.require(
        not check or result.returncode == 0,
        f"T-19: {args[0]} {args[1] if len(args) > 1 else ''}: exit {result.returncode}",
    )
    return result


def docker(*args):
    return command("docker", *args).stdout.decode().strip()


def inspect(identifier):
    return json.loads(docker("inspect", identifier))[0]


def wait_for(probe, expected=True, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if probe() == expected:
            return
        time.sleep(0.5)
    raise RuntimeError("T-19: prazo excedido aguardando condição obrigatória")


class Http:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())
        )

    def request(self, path, method="GET", body=None, csrf=None):
        headers = {"Origin": "http://127.0.0.1:18080"}
        if csrf:
            headers[csrf["headerName"]] = csrf["token"]
        data = None if body is None else json.dumps(body).encode()
        if data:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            "http://127.0.0.1:18080" + path, data=data, headers=headers, method=method
        )
        try:
            response = self.opener.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None


class Installation:
    def __init__(self, project, root, image, feed_network, port=18080):
        self.project, self.root = project, root
        self.env = {
            k: v
            for k, v in os.environ.items()
            if re.fullmatch(r"PATH|HOME|USER|DOCKER_.*|LANG|LC_.*", k)
        }
        self.env.update(
            NEWS_APP_IMAGE=image,
            NEWS_APP_PORT=str(port),
            NEWS_COLLECTION_ENABLED="false",
            NEWS_DB_SECRET_FILE=str(root / "password"),
        )
        self.overlay = root / (project + ".json")
        label = {
            "news.b08.run": re.fullmatch(r"(news-b08-[a-f0-9]{16})-.+", project)[1]
        }
        self.overlay.write_text(
            json.dumps(
                {
                    "services": {
                        "app": {
                            "labels": label,
                            "networks": {"default": {}, "feeds": {}},
                            "environment": {"JDBC_PASSWORD": "wrong-development-value"},
                        },
                        "db": {"labels": label},
                    },
                    "networks": {
                        "feeds": {"external": True, "name": feed_network},
                        "default": {"labels": label},
                    },
                    "volumes": {"app_pg18": {"labels": label}},
                }
            )
        )

    def compose(self, *args, check=True):
        return command(
            "docker",
            "compose",
            "--env-file",
            "/dev/null",
            "-p",
            self.project,
            "-f",
            str(ROOT / "compose.app.yaml"),
            "-f",
            str(self.overlay),
            *args,
            env=self.env,
            check=check,
        )

    def id(self, service):
        value = self.compose("ps", "--all", "-q", service).stdout.decode().strip()
        return inspect(value)["Id"]

    def start_db(self):
        reports.require(
            not docker(
                "ps",
                "-aq",
                "--filter",
                "label=com.docker.compose.project=" + self.project,
            ),
            "Projeto de teste já existe",
        )
        reports.require(
            command(
                "docker", "volume", "inspect", self.project + "_app_pg18", check=False
            ).returncode
            != 0,
            "Volume de destino já existe; não reutilizar silenciosamente",
        )
        self.compose("up", "-d", "--wait", "--wait-timeout", "90", "db")
        self.db = backup.Database(self.id("db"), "news_personal", "news_personal")

    def start(self, image=None, collection=False):
        if image:
            self.env["NEWS_APP_IMAGE"] = image
        self.env["NEWS_COLLECTION_ENABLED"] = str(collection).lower()
        self.compose("up", "-d", "--wait", "--wait-timeout", "100", "--no-deps", "app")
        self.app = self.id("app")

    def stop(self):
        self.compose("stop", "app")
        state = inspect(self.app)["State"]
        reports.require(
            state["Status"] == "exited"
            and not any(state.get(k) for k in ("Running", "Paused", "Restarting")),
            "Parada do app não foi comprovada",
        )

    def cleanup(self):
        ids = docker(
            "ps", "-aq", "--filter", "label=com.docker.compose.project=" + self.project
        ).split()
        for identifier in ids:
            docker("rm", "--force", identifier)
        volumes = docker(
            "volume",
            "ls",
            "-q",
            "--filter",
            "label=com.docker.compose.project=" + self.project,
        ).split()
        for identifier in volumes:
            docker("volume", "rm", identifier)
        networks = docker(
            "network",
            "ls",
            "-q",
            "--filter",
            "label=com.docker.compose.project=" + self.project,
        ).split()
        for identifier in networks:
            docker("network", "rm", identifier)
        reports.require(
            not docker(
                "ps",
                "-aq",
                "--filter",
                "label=com.docker.compose.project=" + self.project,
            ),
            "Cleanup de contêiner incompleto",
        )
        reports.require(
            not docker(
                "volume",
                "ls",
                "-q",
                "--filter",
                "label=com.docker.compose.project=" + self.project,
            ),
            "Cleanup de volume incompleto",
        )


def candidate(source, destination, broken=False):
    # Two identified rehearsal builds; no test migration enters the product.
    with (
        zipfile.ZipFile(source) as original,
        zipfile.ZipFile(destination, "w") as target,
    ):
        javascript = next(
            name
            for name in original.namelist()
            if name.startswith("BOOT-INF/classes/web-ui/assets/")
            and name.endswith(".js")
        )
        changed = original.read(javascript) + b"\n// B-08 upgrade test build\n"
        new_name = "index-" + hashlib.sha256(changed).hexdigest()[:16] + ".js"
        for item in original.infolist():
            payload = original.read(item.filename)
            if not broken and item.filename == javascript:
                target.writestr("BOOT-INF/classes/web-ui/assets/" + new_name, changed)
            else:
                if not broken and item.filename in (
                    "BOOT-INF/classes/web-ui/index.html",
                    "BOOT-INF/classes/web-ui/bundle.properties",
                ):
                    payload = payload.replace(
                        javascript.rsplit("/", 1)[1].encode(), new_name.encode()
                    )
                target.writestr(item, payload)
        target.writestr(
            "BOOT-INF/classes/b08-test-build.txt", "broken" if broken else "upgrade"
        )
        if broken:
            target.writestr(
                "BOOT-INF/classes/db/migration/V999__b08_test_failure.sql",
                "SELECT news_b08_intentional_missing_function();\n",
            )


def legacy_compatibility(args, root, image, network, run_id, projects):
    """Package created by the original B-06 helper, validated before upgrading to B-08."""
    source = Installation(run_id + "-legacy-source", root, image, network)
    projects.append(source)
    overlay = json.loads(source.overlay.read_text())
    overlay["services"]["db"]["ports"] = ["127.0.0.1::5432"]
    source.overlay.write_text(json.dumps(overlay))
    source.start_db()
    mapped = inspect(source.db.container)["NetworkSettings"]["Ports"]["5432/tcp"][0][
        "HostPort"
    ]
    env = {
        k: v
        for k, v in os.environ.items()
        if re.fullmatch(r"PATH|HOME|JAVA_HOME|LANG|LC_.*", k)
    }
    env.update(
        JDBC_URL=f"jdbc:postgresql://127.0.0.1:{mapped}/news_personal",
        JDBC_USERNAME="news_personal",
        JDBC_PASSWORD=(root / "password").read_text(),
        APP_BASE_URL="http://127.0.0.1:18080",
    )
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
    process = subprocess.Popen(
        [
            java,
            "-jar",
            str(args.legacy_jar.resolve()),
            "--server.port=18080",
            "--spring.profiles.active=normal",
            "--app.collection.enabled=false",
        ],
        env=env,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:

        def ready():
            reports.require(
                process.poll() is None, "JAR legado encerrou antes da prontidão"
            )
            try:
                with urllib.request.urlopen(
                    "http://127.0.0.1:18080/health/ready", timeout=2
                ) as response:
                    return response.status == 200
            except (OSError, urllib.error.URLError):
                return False

        wait_for(ready)
        source.db.sql(
            "INSERT INTO sources(id,feed_url,normalized_feed_url,title,status,created_at,updated_at) "
            "VALUES ('70000000-0000-0000-0000-000000000001','https://legacy.invalid/feed','https://legacy.invalid/feed','Legado','paused',now(),now()); "
            "INSERT INTO articles(id,source_id,dedup_key,title,sort_at,search_document,read_at,saved_at,first_seen_at,last_seen_at,updated_at) "
            "VALUES ('70000000-0000-0000-0000-000000000002','70000000-0000-0000-0000-000000000001','legacy','Legacy PostgreSQL',now(),to_tsvector('simple','Legacy PostgreSQL'),now(),now(),now(),now(),now())"
        )
    finally:
        process.terminate()
        process.wait(timeout=45)
    legacy_scripts = root / "legacy-checkout/scripts"
    legacy_scripts.mkdir(parents=True)
    for name in ("backup.py", "backup-guide.md"):
        payload = command(
            "git", "show", args.legacy_helper_ref + ":scripts/" + name, cwd=ROOT
        ).stdout
        (legacy_scripts / name).write_bytes(payload)
    captured = command(
        "python3",
        str(legacy_scripts / "backup.py"),
        "backup",
        "--container",
        source.db.container,
        "--database",
        "news_personal",
        "--user",
        "news_personal",
        "--jar",
        str(args.legacy_jar.resolve()),
        "--stopped-pid",
        str(process.pid),
        "--directory",
        str(root),
    )
    pack = Path(captured.stdout.decode().strip().splitlines()[-1])
    manifest = json.loads((pack / "manifest.json").read_text())
    reports.require(
        manifest["format"] == 1 and "deployment" not in manifest,
        "Pacote não é legado B-06",
    )
    target = Installation(run_id + "-legacy-target", root, image, network)
    projects.append(target)
    target.start_db()
    backup.restore(target.db, pack, args.legacy_jar, root / "legacy-verified.json")
    name = run_id + "-legacy-loopback"
    try:
        docker(
            "run",
            "-d",
            "--name",
            name,
            "--label",
            "news.b08.run=" + run_id,
            "--network",
            target.project + "_default",
            "--entrypoint",
            "java",
            "--read-only",
            "--tmpfs",
            "/tmp",
            "--mount",
            f"type=bind,source={args.legacy_jar.resolve()},target=/legacy.jar,readonly",
            "--mount",
            f"type=bind,source={root / 'password'},target=/run/secrets/spring.datasource.password,readonly",
            "-e",
            "JDBC_URL=jdbc:postgresql://db:5432/news_personal",
            "-e",
            "JDBC_USERNAME=news_personal",
            "-e",
            "APP_BASE_URL=http://127.0.0.1:8080",
            image,
            "-jar",
            "/legacy.jar",
            "--spring.profiles.active=normal",
            "--spring.config.import=configtree:/run/secrets/",
            "--app.collection.enabled=false",
        )
        wait_for(
            lambda: (
                command(
                    "docker",
                    "exec",
                    name,
                    "java",
                    "-cp",
                    "/app",
                    "ReadyProbe",
                    check=False,
                ).returncode
                == 0
            )
        )
        docker(
            "exec",
            name,
            "java",
            "-cp",
            "/app",
            "ReadyProbe",
            "/api/v1/search?q=PostgreSQL",
            "Legacy PostgreSQL",
        )
        reports.require(
            inspect(name)["NetworkSettings"]["Ports"] in ({}, {"8080/tcp": None}),
            "Legado publicou porta host",
        )
        docker("stop", "--time", "45", name)
    finally:
        docker("rm", "--force", name)
    reports.require(
        target.db.snapshot() == manifest["snapshot"],
        "Build legado alterou dados recuperados",
    )
    target.start()
    reports.require(
        Http().request("/api/v1/search?q=PostgreSQL")[1]["items"],
        "Transição B-06 para B-08 falhou",
    )
    reports.require(
        target.db.snapshot() == manifest["snapshot"],
        "Atualização do legado alterou dados",
    )
    target.stop()
    return {
        "status": "passed",
        "jar": manifest["jar"],
        "format": 1,
        "legacyHelperRevision": args.legacy_helper_ref,
        "legacyHelperSha256": backup.sha(legacy_scripts / "backup.py"),
        "recoveryRuntimeImage": image,
        "probe": "same-namespace-loopback",
        "updatedImage": image,
    }


def run(args):
    args.output.mkdir(parents=True, exist_ok=False)
    result = {"status": "running", "scenarios": [], "cleanup": False}
    projects, candidates = [], []
    run_id = "news-b08-" + uuid.uuid4().hex[:16]
    (args.output / "state.json").write_text(json.dumps({"runId": run_id}) + "\n")
    network, feed = run_id + "-feed", run_id + "-server"
    network_created = False
    feed_created = False
    try:
        result["versions"] = {
            "engine": docker("version", "--format", "{{.Server.Version}}"),
            "compose": docker("compose", "version", "--short"),
        }
        reports.require(
            int(result["versions"]["engine"].split(".")[0]) >= 28,
            "Engine >=28 obrigatório",
        )
        with socket.socket() as probe:
            # TIME_WAIT from the preceding JAR scenario is not a service listening on the port.
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            probe.bind(("127.0.0.1", 18080))
            probe.listen(1)
        image = json.loads(args.image.read_text())["imageId"]
        image_info = inspect(image)
        reports.require(
            image_info["Config"]["Labels"]["news.jar.sha256"] == backup.sha(args.jar),
            "JAR/imagem divergentes",
        )
        result["baseline"] = {"imageId": image, "jar": backup.jar_info(args.jar)}
        runtime = command(
            "docker", "run", "--rm", "--entrypoint", "java", image, "-version"
        )
        result["versions"]["runtimeJava"] = (
            (runtime.stdout + runtime.stderr).decode().strip()
        )
        docker(
            "run",
            "--rm",
            "--entrypoint",
            "sh",
            image,
            "-c",
            'test -z "$(command -v javac)" && test -z "$(command -v node)" && test -z "$(command -v npm)" && test -z "$(command -v mvn)"',
        )
        with tempfile.TemporaryDirectory(prefix="news-t19-") as temporary:
            root = Path(temporary)
            root.chmod(0o700)
            password = " " + uuid.uuid4().hex + " "
            (root / "password").write_text(password)
            (root / "password").chmod(
                0o444
            )  # Only the parent directory (0700) is accessible on the host.
            # Public subnet only on an internal infrastructure bridge: DNS and connections
            # use the production client without a URL or transport exception.
            docker(
                "network",
                "create",
                "--internal",
                "--subnet",
                "93.184.216.0/28",
                "--label",
                "news.b08.run=" + run_id,
                network,
            )
            network_created = True
            docker(
                "run",
                "-d",
                "--name",
                feed,
                "--network",
                network,
                "--ip",
                "93.184.216.2",
                "--network-alias",
                "feed.b08.invalid",
                "--label",
                "news.b08.run=" + run_id,
                "--mount",
                f"type=bind,source={ROOT / 'scripts/t19_feed.mjs'},target=/feed.mjs,readonly",
                NODE_IMAGE,
                "node",
                "/feed.mjs",
            )
            feed_created = True
            source = Installation(run_id + "-source", root, image, network)
            projects.append(source)
            source.start_db()
            source.start()
            app_info, db_info = inspect(source.app), inspect(source.db.container)
            reports.require(app_info["Config"]["User"] == "10001:10001", "App root")
            reports.require(
                app_info["NetworkSettings"]["Ports"]
                == {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]},
                "Publicação fora de loopback",
            )
            reports.require(
                not any(db_info["NetworkSettings"]["Ports"].values()),
                "Db publica porta host",
            )
            reports.require(
                password
                not in json.dumps(app_info["Config"]["Env"])
                + json.dumps(db_info["Config"]["Env"])
                + docker("history", "--no-trunc", image),
                "Secret em Env/history",
            )
            result["deployment"] = {
                "appPorts": app_info["NetworkSettings"]["Ports"],
                "dbPorts": db_info["NetworkSettings"]["Ports"],
                "user": app_info["Config"]["User"],
                "healthcheck": app_info["Config"]["Healthcheck"],
            }
            collision = run_id + "-occupied-port"
            try:
                docker(
                    "create",
                    "--name",
                    collision,
                    "--label",
                    "news.b08.run=" + run_id,
                    "--publish",
                    "127.0.0.1:18080:8080",
                    image,
                )
                reports.require(
                    command("docker", "start", collision, check=False).returncode != 0,
                    "Porta ocupada não impediu a instalação",
                )
                reports.require(
                    inspect(source.app)["State"]["Health"]["Status"] == "healthy",
                    "Teste de colisão afetou origem",
                )
            finally:
                docker("rm", "--force", collision)
            # Missing or unreadable secrets and invalid combinations must never become ready.
            negatives = []
            for kind, extra in (
                ("missing-secret", []),
                ("unreadable-secret", []),
                ("demo-container", ["--spring.profiles.include=demo"]),
                ("unknown-deployment", ["--app.deployment=unknown"]),
                ("non-loopback-origin", ["--app.base-url=http://example.org:8080"]),
            ):
                name = run_id + "-" + kind
                secret = (
                    root / "unreadable"
                    if kind == "unreadable-secret"
                    else root / "password"
                )
                if kind == "unreadable-secret":
                    secret.write_text(password)
                    secret.chmod(0o000)
                mounts = (
                    []
                    if kind == "missing-secret"
                    else [
                        "--mount",
                        f"type=bind,source={secret},target=/run/secrets/spring.datasource.password,readonly",
                    ]
                )
                try:
                    docker(
                        "run",
                        "-d",
                        "--name",
                        name,
                        "--label",
                        "news.b08.run=" + run_id,
                        "--network",
                        source.project + "_default",
                        "-e",
                        "JDBC_URL=jdbc:postgresql://db:5432/news_personal",
                        "-e",
                        "JDBC_USERNAME=news_personal",
                        "-e",
                        "APP_COLLECTION_ENABLED=false",
                        *mounts,
                        image,
                        *extra,
                    )
                    wait_for(
                        lambda name=name: inspect(name)["State"]["Status"] == "exited"
                    )
                    reports.require(
                        inspect(name)["State"]["ExitCode"] != 0,
                        "Configuração inválida iniciou app",
                    )
                    if kind == "missing-secret":
                        try:
                            source.db.stopped_container(inspect(name)["Id"], args.jar)
                        except backup.RecoveryError:
                            pass
                        else:
                            raise RuntimeError(
                                "Captura aceitou contêiner alheio parado"
                            )
                    negatives.append(kind)
                finally:
                    docker("rm", "--force", name)
                    if kind == "unreadable-secret":
                        secret.chmod(0o600)
            result["configurationNegatives"] = negatives
            client = Http()
            csrf = client.request("/api/v1/auth/csrf")[1]
            status, created = client.request(
                "/api/v1/sources",
                "POST",
                {"feedUrl": "http://feed.b08.invalid:8080/feed"},
                csrf,
            )
            reports.require(status == 201, "Cadastro na instalação nova falhou")
            source.stop()
            source.start(collection=True)
            wait_for(lambda: source.db.sql("SELECT count(*) FROM articles") == "1")
            source.stop()
            source.start(collection=False)
            # The browser uses only the image port, without Vite or personal fixtures.
            browser_env = {
                k: v
                for k, v in os.environ.items()
                if re.fullmatch(
                    r"PATH|HOME|USER|LANG|LC_.*|LD_LIBRARY_PATH|PLAYWRIGHT_.*|NODE_EXTRA_CA_CERTS",
                    k,
                )
            }
            browser_env.update(
                NEWS_E2E_MODE="image", NEWS_E2E_PACKAGED="1", NEWS_E2E_RUN_ID=run_id
            )
            browser = command(
                "node",
                str(ROOT / "frontend/node_modules/@playwright/test/cli.js"),
                "test",
                "image.spec.ts",
                env=browser_env,
                cwd=ROOT / "frontend",
                check=False,
            )
            (args.output / "browser.log").write_bytes(browser.stdout + browser.stderr)
            reports.require(
                browser.returncode == 0,
                "Navegador da imagem falhou; consulte browser.log",
            )
            result["browser"] = reports.playwright(
                ROOT / "frontend/test-results/playwright-image.json", "image"
            )
            # Operational columns also survive backup, upgrade, and restore in full.
            source.db.sql(
                "UPDATE sources SET status='paused', manual_requested_at=now(), retry_after_at=now()+interval '12 hours', "
                "consecutive_failures=2, etag='\"retained\"'"
            )
            old_csrf = client.request("/api/v1/auth/csrf")[1]
            reports.require(
                client.request(
                    "/api/v1/sources",
                    "POST",
                    {"feedUrl": "http://feed.b08.invalid:8080/eligible"},
                    old_csrf,
                )[0]
                == 201,
                "Fonte ativa de conferência não foi cadastrada",
            )
            article_id = source.db.sql("SELECT id FROM articles LIMIT 1")
            source.stop()
            source.start()
            status, error = client.request(
                f"/api/v1/articles/{article_id}/state",
                "PATCH",
                {"saved": False},
                old_csrf,
            )
            reports.require(
                status == 403 and error["code"] == "CSRF_INVALID",
                "Imagem preservou CSRF antigo",
            )
            fresh = client.request("/api/v1/auth/csrf")[1]
            reports.require(
                client.request(
                    f"/api/v1/articles/{article_id}/state",
                    "PATCH",
                    {"saved": True},
                    fresh,
                )[0]
                == 200,
                "Renovação CSRF falhou",
            )
            # Reject a running app or an incorrect ID before creating the dump.
            for wrong_id in (source.app, source.db.container):
                try:
                    source.db.stopped_container(wrong_id, args.jar)
                except backup.RecoveryError:
                    pass
                else:
                    raise RuntimeError("Captura aceitou app vivo/contêiner errado")
            source.stop()
            extracted = root / "deployed.jar"
            docker("cp", source.app + ":/app/news.jar", str(extracted))
            captured = command(
                "python3",
                str(args.image.parent / "backup.py"),
                "backup",
                "--container",
                source.db.container,
                "--database",
                "news_personal",
                "--user",
                "news_personal",
                "--jar",
                str(extracted),
                "--stopped-container",
                source.app,
                "--directory",
                str(root),
            )
            pack = Path(captured.stdout.decode().strip().splitlines()[-1])
            baseline = source.db.snapshot()
            requests = docker(
                "exec",
                feed,
                "node",
                "-e",
                "fetch('http://127.0.0.1:8080/stats').then(r=>r.text()).then(console.log)",
            )
            result["backup"] = json.loads((pack / "manifest.json").read_text())
            result["scenarios"].extend(
                [
                    "clean-install",
                    "public-feed-without-product-bypass",
                    "image-browser",
                    "restart-csrf",
                    "live-wrong-container-rejected",
                    "stopped-container-backup",
                ]
            )
            # Two rehearsal-only candidates, both derived from the approved product.
            for kind in ("upgrade", "broken"):
                path = root / (kind + ".jar")
                candidate(args.jar, path, kind == "broken")
                proof = build_image.build(
                    path, backup.sha(path), args.output / kind, "t19-" + kind
                )
                candidates.append(proof["imageId"])
                result[kind] = proof
            cache_log = (args.output / "cache-browser.log").open("wb")
            source.start(image)
            cache_browser = subprocess.Popen(
                [
                    "node",
                    str(ROOT / "frontend/scripts/cache-update.mjs"),
                    str(root),
                    str(args.output / "cache-update.json"),
                ],
                env=browser_env,
                stdout=cache_log,
                stderr=subprocess.STDOUT,
            )
            try:
                wait_for(lambda: (root / "browser-ready").is_file())
                source.stop()
                source.start(candidates[0])
                (root / "updated").write_text("updated")
                reports.require(
                    cache_browser.wait(timeout=100) == 0,
                    "Recarga do build novo no navegador falhou",
                )
                result["cacheUpdate"] = json.loads(
                    (args.output / "cache-update.json").read_text()
                )
            finally:
                if cache_browser.poll() is None:
                    cache_browser.terminate()
                    cache_browser.wait(timeout=15)
                cache_log.close()
            reports.require(
                source.db.snapshot() == baseline,
                "Atualização válida alterou dados/metadados",
            )
            reports.require(
                client.request("/api/v1/search?q=PostgreSQL")[1]["items"],
                "Busca falhou após atualizar",
            )
            source.stop()
            # Keep the candidate with the invalid migration stopped and not ready.
            source.env["NEWS_APP_IMAGE"] = candidates[1]
            source.compose("up", "-d", "--no-deps", "app")
            source.app = source.id("app")
            wait_for(lambda: inspect(source.app)["State"]["Status"] == "exited")
            failed = inspect(source.app)["State"]
            reports.require(
                failed["ExitCode"] != 0
                and failed.get("Health", {}).get("Status") != "healthy",
                "Migração inválida ficou pronta",
            )
            result["migrationFailure"] = {
                "containerId": source.app,
                "imageId": candidates[1],
                "exitCode": failed["ExitCode"],
                "health": failed.get("Health", {}).get("Status"),
            }
            logs = docker("logs", source.app)
            reports.require(
                "news_b08_intentional_missing_function" in logs
                and password not in logs,
                "Falha não foi na migração de teste",
            )
            recovery = Installation(run_id + "-recovery", root, image, network)
            projects.append(recovery)
            recovery.start_db()
            # Run the packaged copy outside the original checkout.
            receipt = root / "verified.json"
            restored = command(
                "python3",
                str(pack / "backup.py"),
                "restore",
                "--container",
                recovery.db.container,
                "--database",
                "news_personal",
                "--user",
                "news_personal",
                "--jar",
                str(extracted),
                "--package",
                str(pack),
                "--receipt",
                str(receipt),
            )
            result["restore"] = json.loads(receipt.read_text())
            reports.require(
                result["restore"]["status"] == "verified", "Restore sem recibo"
            )
            result["restoreExit"] = restored.returncode
            recovery.start()
            result["recoveredDeployment"] = {
                "appContainerId": recovery.app,
                "database": recovery.db.identity,
                "imageId": inspect(recovery.app)["Image"],
                "collectionEnabled": False,
            }
            reports.require(
                recovery.db.snapshot() == baseline, "Recuperação alterou snapshot"
            )
            reports.require(
                client.request("/api/v1/search?q=PostgreSQL")[1]["items"],
                "Busca recuperada vazia",
            )
            reports.require(
                client.request(f"/api/v1/articles/{article_id}")[1]["read"],
                "Marcação lido perdida",
            )
            reports.require(
                client.request(f"/api/v1/articles/{article_id}")[1]["saved"],
                "Marcação salvo perdida",
            )
            reports.require(
                inspect(source.app)["State"]["Status"] == "exited",
                "Origem religada durante restore",
            )
            reports.require(
                docker(
                    "exec",
                    feed,
                    "node",
                    "-e",
                    "fetch('http://127.0.0.1:8080/stats').then(r=>r.text()).then(console.log)",
                )
                == requests,
                "Houve coleta durante atualização/recuperação",
            )
            # Only after checking the snapshot, make the synthetic source eligible
            # and deliberately resume the one selected installation.
            recovery.stop()
            recovery.db.sql(
                "UPDATE sources SET status='active', retry_after_at=NULL, next_poll_at=now(), manual_requested_at=now()"
            )
            recovery.start(collection=True)
            wait_for(
                lambda: (
                    int(
                        json.loads(
                            docker(
                                "exec",
                                feed,
                                "node",
                                "-e",
                                "fetch('http://127.0.0.1:8080/stats').then(r=>r.text()).then(console.log)",
                            )
                        )["requests"]
                    )
                    > int(json.loads(requests)["requests"])
                )
            )
            wait_for(
                lambda: (
                    recovery.db.sql(
                        "SELECT count(*) FROM sources WHERE manual_requested_at IS NOT NULL"
                    )
                    == "0"
                )
            )
            reports.require(
                recovery.db.sql(
                    "SELECT count(*) FROM articles WHERE read_at IS NOT NULL AND saved_at IS NOT NULL"
                )
                == "1",
                "Retomada alterou identidade/marcações",
            )
            reports.require(
                inspect(source.app)["State"]["Status"] == "exited",
                "Retomada religou origem",
            )
            result["collection"] = {
                "requestsBeforeUpdate": json.loads(requests)["requests"],
                "requestsAfterVerification": json.loads(requests)["requests"],
                "requestsAfterResume": json.loads(
                    docker(
                        "exec",
                        feed,
                        "node",
                        "-e",
                        "fetch('http://127.0.0.1:8080/stats').then(r=>r.text()).then(console.log)",
                    )
                )["requests"],
                "sourceRemainedStopped": True,
            }
            recovery.stop()
            result["snapshot"] = baseline
            result["scenarios"].extend(
                [
                    "identified-upgrade",
                    "migration-failure-not-ready",
                    "packaged-helper-restore-new-cluster",
                    "previous-image-recovery",
                    "no-collection-during-recovery",
                    "deliberate-resume-recovered-installation",
                ]
            )
            result["legacy"] = (
                legacy_compatibility(args, root, image, network, run_id, projects)
                if args.legacy_jar
                else {"status": "not-requested"}
            )
            result["status"] = "passed"
    finally:
        if result["status"] != "passed":
            result["status"] = "failed"
        cleanup_errors = []
        for installation in reversed(projects):
            try:
                installation.cleanup()
            except Exception as error:
                cleanup_errors.append(
                    type(error).__name__ + ": " + installation.project
                )
        cleanup_commands = []
        if feed_created:
            cleanup_commands.append(["rm", "--force", feed])
        if network_created:
            cleanup_commands.append(["network", "rm", network])
        cleanup_commands.extend(["image", "rm", image_id] for image_id in candidates)
        for resource in cleanup_commands:
            try:
                docker(*resource)
            except Exception as error:
                cleanup_errors.append(type(error).__name__ + ": " + resource[-1])
        result["cleanup"] = not cleanup_errors
        if cleanup_errors:
            result.update(status="failed", cleanupErrors=cleanup_errors)
        (args.output / "t19.json").write_text(json.dumps(result, indent=2) + "\n")
        reports.require(
            not cleanup_errors, "Cleanup T-19 incompleto; consulte o relatório"
        )


def cleanup(output):
    state = output / "state.json"
    if not state.is_file():
        return
    run_id = json.loads(state.read_text())["runId"]
    reports.require(
        re.fullmatch(r"news-b08-[a-f0-9]{16}", run_id), "Identidade de cleanup inválida"
    )
    for kind, listing in (
        ("container", ["ps", "-aq"]),
        ("volume", ["volume", "ls", "-q"]),
        ("network", ["network", "ls", "-q"]),
    ):
        identifiers = docker(
            *listing, "--filter", "label=news.b08.run=" + run_id
        ).split()
        for identifier in identifiers:
            docker(
                kind, "rm", *(["--force"] if kind == "container" else []), identifier
            )
        reports.require(
            not docker(*listing, "--filter", "label=news.b08.run=" + run_id),
            "Recursos T-19 restantes após cleanup",
        )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--legacy-jar", type=Path)
    parser.add_argument(
        "--legacy-helper-ref", default="dfeabb75dc6c25994d768c5aa8b700876b04fe9c"
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cleanup", action="store_true")
    args = parser.parse_args()
    if args.cleanup:
        cleanup(args.output)
    else:
        reports.require(args.image and args.jar, "Informe --image e --jar")
        signal.signal(
            signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt())
        )
        run(args)
