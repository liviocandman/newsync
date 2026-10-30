"""Pré-requisitos declarados; somente versões, sem despejar ambiente/segredos."""

import re
import subprocess
import sys


def command(*args):
    result = subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    print(result.stdout.strip())
    return result.stdout


if sys.version_info < (3, 11):
    raise SystemExit("O gate exige Python >= 3.11.")
print("Python", sys.version.split()[0])
java = command("java", "-version")
if not re.search(r'version "25\.', java):
    raise SystemExit("O projeto exige JDK 25.")
node = command("node", "--version")
match = re.fullmatch(r"v24\.(\d+)\.\d+\s*", node)
if not match or int(match[1]) < 15:
    raise SystemExit("O projeto exige Node >= 24.15 e < 25.")
command("npm", "--version")
engine = command("docker", "version", "--format", "{{.Server.Version}}")
if int(engine.split(".")[0]) < 28:
    raise SystemExit("Distribuição local exige Docker Engine >=28.")
command("docker", "compose", "version")
