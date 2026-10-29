import { spawn, execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';
import { stopProcess } from '../e2e/support/stop-process.ts';

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const runId = process.env.NEWS_E2E_RUN_ID ?? randomUUID();
if (!/^[a-zA-Z0-9-]{1,100}$/.test(runId))
  throw new Error('Identificação E2E inválida.');
const label = `news.f02.run=${runId}`;
const env = Object.fromEntries(
  Object.entries(process.env).filter(([key]) =>
    /^(PATH|HOME|USER|JAVA_HOME|TMPDIR|CI|LANG|LC_.*|LD_LIBRARY_PATH|PLAYWRIGHT_.*|DOCKER_.*|NODE_EXTRA_CA_CERTS|npm_config_cache|NEWS_.*)$/.test(
      key,
    ),
  ),
);
env.NEWS_E2E_RUN_ID = runId;

function docker(...args) {
  return execFileSync('docker', args, {
    encoding: 'utf8',
    timeout: 30_000,
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}
function cleanup() {
  const ids = docker('ps', '-aq', '--filter', `label=${label}`)
    .split(/\s+/)
    .filter(Boolean);
  if (ids.length) docker('rm', '--force', ...ids);
  const remaining = docker('ps', '-aq', '--filter', `label=${label}`);
  if (remaining) throw new Error('Cleanup E2E incompleto.');
  mkdirSync(resolve(frontend, 'test-results'), { recursive: true });
  writeFileSync(
    resolve(frontend, 'test-results/cleanup.json'),
    JSON.stringify({ runId, remainingContainers: 0 }) + '\n',
  );
}

if (process.argv.includes('--cleanup')) {
  if (!process.env.NEWS_E2E_RUN_ID)
    throw new Error('Cleanup exige NEWS_E2E_RUN_ID explícito.');
  cleanup();
} else {
  // O probe usa bind, sem consultar HTTP de qualquer serviço preexistente.
  const probes = [];
  try {
    for (const port of [18080, 5174]) {
      const probe = createServer();
      probes.push(probe);
      probe.listen(port, '127.0.0.1');
      await once(probe, 'listening');
    }
  } finally {
    await Promise.all(
      probes.map((probe) => new Promise((resolve) => probe.close(resolve))),
    );
  }
  const jar = readFileSync(resolve(frontend, '../backend/target/news.jar'));
  if (
    env.NEWS_DEMO_SHA256 &&
    createHash('sha256').update(jar).digest('hex') !== env.NEWS_DEMO_SHA256
  )
    throw new Error('JAR demo difere do artefato recém-verificado.');
  let signalExit = 0;
  let killTimer;
  let child;
  let vite;
  let viteLog = '';
  function killGroup(childProcess, signal) {
    if (!childProcess?.pid) return;
    try {
      // Todos os grupos abaixo foram criados por este supervisor.
      process.kill(-childProcess.pid, signal);
    } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  }
  function stop(signal) {
    signalExit = signal === 'SIGINT' ? 130 : 143;
    killGroup(child, 'SIGTERM');
    killGroup(vite, 'SIGTERM');
    killTimer ??= setTimeout(() => {
      killGroup(child, 'SIGKILL');
      killGroup(vite, 'SIGKILL');
    }, 15_000);
  }
  process.on('SIGINT', () => stop('SIGINT'));
  process.on('SIGTERM', () => stop('SIGTERM'));
  try {
    // Vite também pertence ao supervisor; o webServer do runner cria outro grupo.
    vite = spawn(
      process.execPath,
      [resolve(frontend, 'node_modules/vite/bin/vite.js'), '--port', '5174'],
      {
        cwd: frontend,
        env: { ...env, API_PROXY_TARGET: 'http://127.0.0.1:18080' },
        stdio: ['ignore', 'pipe', 'pipe'],
        detached: true,
      },
    );
    for (const stream of [vite.stdout, vite.stderr])
      stream.on('data', (chunk) => {
        viteLog = (viteLog + chunk.toString()).slice(-262144);
      });
    let spawnError;
    vite.on('error', (error) => {
      spawnError = error;
    });
    const deadline = Date.now() + 30_000;
    while (true) {
      if (spawnError) throw spawnError;
      if (signalExit)
        throw new Error('E2E interrompido durante inicialização.');
      if (
        vite.exitCode !== null ||
        vite.signalCode !== null ||
        Date.now() > deadline
      )
        throw new Error('Vite não iniciou; consulte vite.log.');
      try {
        if (
          (
            await fetch('http://127.0.0.1:5174', {
              signal: AbortSignal.timeout(1000),
            })
          ).ok
        )
          break;
      } catch {
        /* Aguardar somente o processo próprio recém-iniciado. */
      }
      await delay(100);
    }
    child = spawn(
      process.execPath,
      [
        resolve(frontend, 'node_modules/@playwright/test/cli.js'),
        'test',
        ...process.argv.slice(2),
      ],
      { cwd: frontend, env, stdio: 'inherit', detached: true },
    );
    const [code] = await once(child, 'exit');
    process.exitCode = signalExit || code || (child.signalCode ? 1 : 0);
  } catch (error) {
    process.exitCode = signalExit || 1;
    console.error(error.message);
  } finally {
    clearTimeout(killTimer);
    // Encerra também descendentes que sobreviveram a um worker cancelado.
    killGroup(child, 'SIGKILL');
    try {
      await stopProcess(vite);
    } catch {
      process.exitCode = process.exitCode || 1;
    } finally {
      killGroup(vite, 'SIGKILL');
    }
    mkdirSync(resolve(frontend, 'test-results'), { recursive: true });
    writeFileSync(resolve(frontend, 'test-results/vite.log'), viteLog);
    const exited = (childProcess) =>
      !childProcess?.pid ||
      childProcess.exitCode !== null ||
      childProcess.signalCode !== null;
    writeFileSync(
      resolve(frontend, 'test-results/processes.json'),
      JSON.stringify({
        runId,
        viteExited: exited(vite),
        playwrightExited: exited(child),
      }) + '\n',
    );
    if (!exited(vite) || !exited(child))
      process.exitCode = process.exitCode || 1;
    try {
      cleanup();
    } catch {
      process.exitCode = process.exitCode || 1;
      console.error(
        'Não foi possível confirmar a limpeza dos recursos E2E desta execução.',
      );
    }
  }
}
