import { spawn, execFileSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
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
  if (docker('ps', '-aq', '--filter', `label=${label}`))
    throw new Error('Cleanup E2E incompleto.');
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
      probes.map((probe) => new Promise((done) => probe.close(done))),
    );
  }
  const jarTool = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin', 'jar')
    : 'jar';
  const jarProof = {};
  for (const mode of ['normal', 'demo']) {
    const jarPath = env[`NEWS_${mode.toUpperCase()}_JAR`];
    const expectedHash = env[`NEWS_${mode.toUpperCase()}_SHA256`];
    if (!jarPath || !expectedHash)
      throw new Error(
        `Informe caminho e hash do JAR ${mode} recém-verificado pelo gate.`,
      );
    const sha256 = createHash('sha256')
      .update(readFileSync(jarPath))
      .digest('hex');
    if (sha256 !== expectedHash)
      throw new Error(`JAR ${mode} difere do artefato recém-verificado.`);
    const inventory = execFileSync(jarTool, ['tf', jarPath], {
      encoding: 'utf8',
    }).split(/\r?\n/);
    const demoEntries = inventory.filter(
      (entry) =>
        entry.startsWith('BOOT-INF/classes/demo/') && !entry.endsWith('/'),
    );
    if (
      JSON.stringify(demoEntries) !==
      JSON.stringify(mode === 'demo' ? ['BOOT-INF/classes/demo/feed.xml'] : [])
    )
      throw new Error(`Inventário demo incorreto no JAR ${mode}.`);
    jarProof[mode] = { path: jarPath, sha256, demoEntries };
  }
  if (resolve(env.NEWS_NORMAL_JAR) === resolve(env.NEWS_DEMO_JAR))
    throw new Error('Normal e demo exigem artefatos separados.');
  let signalExit = 0;
  let killTimer;
  let child;
  let vite;
  let allExited = true;
  function killGroup(processToKill, signal) {
    if (!processToKill?.pid) return;
    try {
      process.kill(-processToKill.pid, signal);
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
    mkdirSync(resolve(frontend, 'test-results'), { recursive: true });
    writeFileSync(
      resolve(frontend, 'test-results/jars.json'),
      JSON.stringify(jarProof, null, 2) + '\n',
    );
    for (const mode of ['normal', 'demo']) {
      let viteLog = '';
      try {
        const modeEnv = {
          ...env,
          NEWS_E2E_MODE: mode,
          VITE_APP_MODE: mode,
          API_PROXY_TARGET: 'http://127.0.0.1:18080',
        };
        vite = spawn(
          process.execPath,
          [
            resolve(frontend, 'node_modules/vite/bin/vite.js'),
            '--port',
            '5174',
          ],
          {
            cwd: frontend,
            env: modeEnv,
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
            throw new Error(
              `Vite ${mode} não iniciou; consulte vite-${mode}.log.`,
            );
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
            /* Esperar o processo próprio. */
          }
          await delay(100);
        }
        child = spawn(
          process.execPath,
          [
            resolve(frontend, 'node_modules/@playwright/test/cli.js'),
            'test',
            ...(mode === 'normal'
              ? ['normal.spec.ts', 'normal-pending.spec.ts']
              : ['demo.spec.ts']),
            ...process.argv.slice(2),
          ],
          { cwd: frontend, env: modeEnv, stdio: 'inherit', detached: true },
        );
        const [code] = await once(child, 'exit');
        if (signalExit || code || child.signalCode)
          throw new Error(
            `Navegador ${mode} falhou (exit ${signalExit || code || 1}).`,
          );
      } finally {
        killGroup(child, 'SIGKILL');
        try {
          await stopProcess(vite);
        } finally {
          killGroup(vite, 'SIGKILL');
          writeFileSync(
            resolve(frontend, `test-results/vite-${mode}.log`),
            viteLog,
          );
          const exited = (processToCheck) =>
            !processToCheck?.pid ||
            processToCheck.exitCode !== null ||
            processToCheck.signalCode !== null;
          allExited &&= exited(vite) && exited(child);
        }
      }
    }
  } catch (error) {
    process.exitCode = signalExit || 1;
    console.error(error.message);
  } finally {
    clearTimeout(killTimer);
    killGroup(child, 'SIGKILL');
    killGroup(vite, 'SIGKILL');
    mkdirSync(resolve(frontend, 'test-results'), { recursive: true });
    writeFileSync(
      resolve(frontend, 'test-results/processes.json'),
      JSON.stringify({
        runId,
        viteExited: allExited,
        playwrightExited: allExited,
      }) + '\n',
    );
    if (!allExited) process.exitCode = process.exitCode || 1;
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
