import type { ChildProcess } from 'node:child_process';
import { once } from 'node:events';

export async function stopProcess(
  child: ChildProcess | undefined,
  timeoutMs = 15_000,
): Promise<void> {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;

  const stopped = once(child, 'exit');
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    child.kill('SIGTERM');
    await Promise.race([
      stopped,
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => {
          // O prazo pertence a este processo, mesmo depois de um restart.
          child.kill('SIGKILL');
          reject(new Error('Backend não encerrou.'));
        }, timeoutMs);
        timeout.unref();
      }),
    ]);
  } finally {
    clearTimeout(timeout);
  }
}
