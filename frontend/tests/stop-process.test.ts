// @vitest-environment node
import { spawn, type ChildProcess } from 'node:child_process';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { afterEach, expect, it, vi } from 'vitest';
import { stopProcess } from '../e2e/support/stop-process.ts';

const children = new Set<ChildProcess>();

async function startChild(ignoreTermination = false): Promise<ChildProcess> {
  const child = spawn(
    process.execPath,
    [
      '-e',
      `
    process.on('SIGTERM', () => {
      if (!${ignoreTermination}) process.exit(0);
    });
    setInterval(() => {}, 1000);
    process.send('ready');
  `,
    ],
    { stdio: ['ignore', 'ignore', 'inherit', 'ipc'] },
  );
  children.add(child);
  // O filho instala seu handler antes de confirmar prontidão; sem sleep de boot.
  await once(child, 'message');
  return child;
}

afterEach(async () => {
  try {
    await Promise.all(
      [...children].map(async (child) => {
        if (child.exitCode !== null || child.signalCode !== null) return;
        const exited = once(child, 'exit');
        child.kill('SIGKILL');
        await exited;
      }),
    );
  } finally {
    children.clear();
    vi.restoreAllMocks();
  }
});

it('cancela o prazo de A e mantém B vivo depois do prazo antigo', async () => {
  const graceMs = 2000;
  const first = await startChild();
  const signals = vi.spyOn(first, 'kill');

  await stopProcess(first, graceMs);
  expect(first.exitCode).toBe(0);

  const replacement = await startChild();
  // Aguardar este prazo é o comportamento sob teste, não coordenação de concorrência.
  await delay(graceMs + 100);
  expect(replacement.exitCode).toBeNull();
  expect(replacement.signalCode).toBeNull();
  expect(signals.mock.calls.map(([signal]) => signal)).toEqual(['SIGTERM']);
}, 10_000);

it('força somente A quando ele ignora SIGTERM e preserva B', async () => {
  const first = await startChild(true);
  const replacement = await startChild();
  const firstSignals = vi.spyOn(first, 'kill');
  const replacementSignals = vi.spyOn(replacement, 'kill');
  const exited = once(first, 'exit');

  await expect(stopProcess(first, 250)).rejects.toThrow(
    'Backend não encerrou.',
  );
  const [code, signal] = await exited;
  expect(code).toBeNull();
  expect(signal).toBe('SIGKILL');
  expect(firstSignals.mock.calls.map(([sent]) => sent)).toEqual([
    'SIGTERM',
    'SIGKILL',
  ]);
  expect(replacementSignals).not.toHaveBeenCalled();
  expect(replacement.exitCode).toBeNull();
  expect(replacement.signalCode).toBeNull();
}, 10_000);
