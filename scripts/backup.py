#!/usr/bin/env python3
"""Backup lógico B-06. Não lê .env, não inicia Java e nunca sobrescreve bancos."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import uuid
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parent.parent


class RecoveryError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise RecoveryError(message)


def run(command, *, stdin=None, stdout=None, operation="command"):
    # Saída de PostgreSQL pode conter dados pessoais: somente operação/exit no log.
    result = subprocess.run(command, stdin=stdin, stdout=stdout or subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=600)
    print(f"{operation}: exit={result.returncode}", file=sys.stderr, flush=True)
    require(result.returncode == 0, f"{operation} falhou (exit {result.returncode})")
    return result.stdout


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def jar_info(path):
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode()
        version = next((line.split(": ", 1)[1] for line in manifest.splitlines()
                        if line.startswith("Implementation-Version: ")), "unknown")
    return {"sha256": sha(path), "bytes": path.stat().st_size, "version": version}


def secure_directory(path):
    path = path.resolve()
    require(not (ROOT / "backend/pom.xml").is_file() or not path.is_relative_to(ROOT),
            "Guarde o pacote fora do checkout")
    # chmod em drvfs não comprova ACL Windows. Referência operacional: filesystem Linux.
    require(not re.match(r"^/mnt/[a-z]/", str(path)),
            "Use filesystem Linux; ACL Windows/DrvFS não é validada por este auxiliar")
    require(path.is_dir() and path.stat().st_uid == os.getuid(), "Diretório deve existir e pertencer ao operador")
    require(stat.S_IMODE(path.stat().st_mode) & 0o077 == 0, "Diretório exige modo 0700 ou mais restrito")
    return path


class Database:
    def __init__(self, container, database, user):
        for value in (container, database, user):
            require(re.fullmatch(r"[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}", value),
                    "Container/banco/usuário devem ser identificadores simples explícitos")
        self.container, self.database, self.user = container, database, user
        info = json.loads(run(["docker", "inspect", container], operation="docker-inspect"))[0]
        self.identity = {"containerId": info["Id"], "image": info["Image"], "database": database,
                         "user": user, "ports": info["NetworkSettings"]["Ports"],
                         "composeProject": (info["Config"].get("Labels") or {}).get("com.docker.compose.project")}

    def command(self, tool, *options):
        return ["docker", "exec", "-i", self.container, tool, *options]

    def sql(self, query):
        # Autenticação local provisionada no container; nenhuma senha em argv/logs.
        return run(self.command("psql", "-X", "-w", "-v", "ON_ERROR_STOP=1", "-A", "-t",
                                "-U", self.user, "-d", self.database, "-c", query),
                   operation="psql").decode().strip()

    def json(self, query):
        return json.loads(self.sql(query))

    def versions(self):
        server = self.sql("SHOW server_version")
        dump = run(self.command("pg_dump", "--version"), operation="pg-dump-version").decode().strip()
        restore = run(self.command("pg_restore", "--version"), operation="pg-restore-version").decode().strip()
        require(server.split(".")[0] == "18" and re.search(r"\) 18\.", dump)
                and re.search(r"\) 18\.", restore), "Somente PostgreSQL/pg_dump/pg_restore 18 são suportados")
        return {"server": server, "pgDump": dump, "pgRestore": restore}

    def stopped(self, pid):
        require(pid > 1, "Informe o PID real do Java encerrado")
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            pass
        else:
            raise RecoveryError("Processo de origem ainda existe; pare e aguarde o término")
        self.no_clients()

    def no_clients(self):
        require(self.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                         "AND backend_type='client backend' AND pid<>pg_backend_pid()") == "0",
                "Banco possui clientes conectados; encerre aplicações antes de continuar")

    def fingerprint(self):
        return self.json("SELECT json_build_array((SELECT system_identifier::text FROM pg_control_system()),"
                         "(SELECT oid::text FROM pg_database WHERE datname=current_database()),current_database())")

    def snapshot(self):
        tables = self.json("SELECT coalesce(json_agg(json_build_array(schemaname,tablename) ORDER BY schemaname,tablename),'[]') "
                           "FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema')")
        result = {"tables": {}}
        for schema, table in tables:
            identifier = '"' + schema.replace('"', '""') + '"."' + table.replace('"', '""') + '"'
            # Linhas completas lidas novamente; apenas hashes/contagens persistem no pacote.
            rows = self.sql(f"SELECT row_to_json(t)::text FROM {identifier} t ORDER BY row_to_json(t)::text COLLATE \"C\"")
            result["tables"][f"{schema}.{table}"] = {
                "count": int(self.sql(f"SELECT count(*) FROM {identifier}")),
                "sha256": hashlib.sha256(rows.encode()).hexdigest()}
        structures = {
            "columns": "SELECT table_schema,table_name,column_name,ordinal_position,data_type,udt_name,is_nullable,column_default FROM information_schema.columns WHERE table_schema NOT IN ('pg_catalog','information_schema') ORDER BY 1,2,4",
            "constraints": "SELECT n.nspname,c.relname,k.conname,pg_get_constraintdef(k.oid) FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2,3",
            "indexes": "SELECT schemaname,tablename,indexname,indexdef FROM pg_indexes WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2,3",
            "sequences": "SELECT schemaname,sequencename,data_type,start_value,min_value,max_value,increment_by,cycle,cache_size,last_value FROM pg_sequences WHERE schemaname NOT IN ('pg_catalog','information_schema') ORDER BY 1,2",
        }
        for key, query in structures.items():
            value = self.sql("SELECT row_to_json(s)::text FROM (" + query + ") s")
            result[key] = hashlib.sha256(value.encode()).hexdigest()
        result["flyway"] = self.json("SELECT json_agg(row_to_json(h) ORDER BY installed_rank) FROM "
                                     "(SELECT installed_rank,version,description,type,script,checksum,success "
                                     "FROM public.flyway_schema_history) h")
        require(result["flyway"] and all(row["success"] for row in result["flyway"]), "Histórico Flyway inválido")
        return result

    def empty(self):
        # Recusa inclusive views, funções, tipos ou schemas vazios fora de public.
        require(self.sql("SELECT count(*) FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') "
                         "AND nspname !~ '^pg_'") == "0", "Destino ocupado (schemas)")
        require(self.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                         "WHERE n.nspname='public'") == "0", "Destino ocupado (relações)")
        require(self.sql("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                         "WHERE n.nspname='public'") == "0", "Destino ocupado (funções)")
        require(self.sql("SELECT count(*) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace "
                         "WHERE n.nspname='public'") == "0", "Destino ocupado (tipos)")


def inventory(db, dump):
    with dump.open("rb") as stream:
        value = run(db.command("pg_restore", "--list"), stdin=stream, operation="pg-restore-list")
    require(b"TABLE DATA" in value and b"flyway_schema_history" in value, "Inventário incompleto")
    return hashlib.sha256(value).hexdigest()


def backup(db, directory, jar, pid):
    directory = secure_directory(directory)
    db.stopped(pid)
    versions = db.versions()
    before = db.snapshot()
    name = datetime.now(timezone.utc).strftime("news-%Y%m%dT%H%M%SZ-") + uuid.uuid4().hex
    pending = directory / (name + ".incomplete")
    pending.mkdir(mode=0o700)
    dump = pending / "archive.dump"
    with dump.open("xb") as stream:
        run(db.command("pg_dump", "-w", "-U", db.user, "-d", db.database, "--format=custom"),
            stdout=stream, operation="pg-dump")
        stream.flush()
        os.fsync(stream.fileno())
    require(dump.stat().st_size > 0, "Dump vazio")
    listing = inventory(db, dump)
    db.no_clients()
    require(db.snapshot() == before, "Origem mudou durante a captura; pacote permanece incompleto")
    manifest = {"format": 1, "createdUtc": datetime.now(timezone.utc).isoformat(),
                "source": db.identity, "sourceFingerprint": db.fingerprint(), "versions": versions,
                "jar": jar_info(jar), "dump": {"sha256": sha(dump), "bytes": dump.stat().st_size,
                                               "inventorySha256": listing}, "snapshot": before}
    (pending / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    # Instruções da revisão que criou o pacote; sem arquivos de configuração pessoal.
    (pending / "RESTORE.md").write_text((ROOT / "scripts/backup-guide.md").read_text())
    (pending / "backup.py").write_bytes(Path(__file__).read_bytes())
    (pending / "configuration.txt").write_text(
        "JDBC_URL\nJDBC_USERNAME\nJDBC_PASSWORD\nAPP_BASE_URL\napp.collection.enabled=false\n"
        "Credenciais recuperadas separadamente. Use o JAR de hash indicado no manifesto.\n")
    for file in pending.iterdir():
        file.chmod(0o600)
        with file.open("rb") as stream:
            os.fsync(stream.fileno())
    complete = directory / name
    pending.rename(complete)
    print(complete)
    return complete


def restore(db, package, jar, receipt):
    secure_directory(package.parent)
    require(not package.name.endswith(".incomplete"), "Pacote incompleto")
    require(not receipt.exists(), "Recibo já existe; use nome novo")
    manifest = json.loads((package / "manifest.json").read_text())
    require(manifest["format"] == 1, "Formato de manifesto incompatível")
    dump = package / "archive.dump"
    require(dump.stat().st_size == manifest["dump"]["bytes"] and sha(dump) == manifest["dump"]["sha256"],
            "Checksum/tamanho do dump divergente")
    require(jar_info(jar) == manifest["jar"], "Build incompatível com o backup")
    db.versions()
    require(manifest["versions"]["server"].split(".")[0] == "18"
            and re.search(r"\) 18\.", manifest["versions"]["pgDump"])
            and re.search(r"\) 18\.", manifest["versions"]["pgRestore"]), "Versão de origem incompatível")
    require(db.fingerprint() != manifest["sourceFingerprint"], "Destino igual à origem")
    db.no_clients()
    db.empty()
    require(inventory(db, dump) == manifest["dump"]["inventorySha256"], "Inventário divergente")
    with dump.open("rb") as stream:
        run(db.command("pg_restore", "-w", "-U", db.user, "-d", db.database,
                       "--no-owner", "--no-acl", "--single-transaction", "--exit-on-error"),
            stdin=stream, operation="pg-restore")
    require(db.snapshot() == manifest["snapshot"], "Conferência divergente; não iniciar Java neste destino")
    with receipt.open("x") as stream:
        json.dump({"status": "verified", "target": db.identity, "dumpSha256": sha(dump),
                   "jar": manifest["jar"], "snapshot": manifest["snapshot"]}, stream, indent=2)
    receipt.chmod(0o600)
    print("Restore conferido. Inicie somente o JAR compatível, com coleta desligada.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["backup", "restore"])
    parser.add_argument("--container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--stopped-pid", type=int)
    parser.add_argument("--package", type=Path)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    try:
        db = Database(args.container, args.database, args.user)
        if args.action == "backup":
            require(args.directory is not None and args.stopped_pid is not None,
                    "Backup exige --directory e --stopped-pid")
            backup(db, args.directory, args.jar, args.stopped_pid)
        else:
            require(args.package is not None and args.receipt is not None,
                    "Restore exige --package e --receipt")
            restore(db, args.package, args.jar, args.receipt)
    except (RecoveryError, OSError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        # Não imprimir exceções de subprocesso/configuração com potenciais segredos.
        message = str(error) if isinstance(error, RecoveryError) else type(error).__name__
        print("Recuperação não aprovada: " + message, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
