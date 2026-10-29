import { expect } from '@playwright/test';
import { execFileSync, spawn, type ChildProcess } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:net';
import { once } from 'node:events';
import { join } from 'node:path';
import { createWriteStream, mkdirSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { stopProcess } from './stop-process.ts';

const image =
  'postgres:18.3@sha256:7e32e9833a6fb1c92c32552794cb6ed569d51b445a54907d35fc112ef39684db';
type StartOptions = { collectionEnabled?: boolean };
export class TestBackend {
  private readonly container: string;
  private readonly password = randomUUID();
  private containerCreated = false;
  private backend: ChildProcess | undefined;
  private jdbcUrl = '';
  private backendLog: ReturnType<typeof createWriteStream> | undefined;
  private readonly mode: 'normal' | 'demo';
  constructor(mode: 'normal' | 'demo') {
    this.mode = mode;
    this.container = `news-b03-${mode}-${randomUUID()}`;
  }
  private docker(...args: string[]) {
    return execFileSync('docker', args, {
      encoding: 'utf8',
      timeout: 120_000,
      env: { ...process.env, POSTGRES_PASSWORD: this.password },
    }).trim();
  }
  sql(statement: string) {
    return this.docker(
      'exec',
      this.container,
      'psql',
      '-U',
      'b03_test',
      '-d',
      'news_b03_e2e',
      '-v',
      'ON_ERROR_STOP=1',
      '-At',
      '-c',
      statement,
    );
  }
  async create(options?: StartOptions) {
    const probe = createServer();
    probe.listen(18080, '127.0.0.1');
    await once(probe, 'listening');
    await new Promise<void>((resolve, reject) =>
      probe.close((error) => (error ? reject(error) : resolve())),
    );
    mkdirSync('test-results', { recursive: true });
    this.backendLog = createWriteStream(
      `test-results/backend-${this.mode}.log`,
      { flags: 'a' },
    );
    this.docker(
      'run',
      '--detach',
      '--rm',
      '--name',
      this.container,
      '--label',
      `news.f02.run=${process.env.NEWS_E2E_RUN_ID}`,
      '--publish',
      '127.0.0.1::5432',
      '--tmpfs',
      '/var/lib/postgresql',
      '--env',
      'POSTGRES_DB=news_b03_e2e',
      '--env',
      'POSTGRES_USER=b03_test',
      '--env',
      'POSTGRES_PASSWORD',
      '--health-cmd',
      'pg_isready -U b03_test -d news_b03_e2e',
      '--health-interval',
      '1s',
      '--health-timeout',
      '3s',
      '--health-retries',
      '30',
      image,
    );
    this.containerCreated = true;
    await expect
      .poll(
        () =>
          this.docker(
            'inspect',
            '--format',
            '{{.State.Health.Status}}',
            this.container,
          ),
        { timeout: 45_000 },
      )
      .toBe('healthy');
    const mapped = this.docker('port', this.container, '5432/tcp')
      .split(':')
      .at(-1);
    this.jdbcUrl = `jdbc:postgresql://127.0.0.1:${mapped}/news_b03_e2e`;
    await this.start(options);
  }
  async start(options?: StartOptions) {
    const jar =
      this.mode === 'normal'
        ? process.env.NEWS_NORMAL_JAR
        : process.env.NEWS_DEMO_JAR;
    if (!jar) throw new Error(`JAR ${this.mode} verificado não informado.`);
    const java = process.env.JAVA_HOME
      ? join(
          process.env.JAVA_HOME,
          'bin',
          process.platform === 'win32' ? 'java.exe' : 'java',
        )
      : 'java';
    this.backend = spawn(
      java,
      [
        '-jar',
        jar,
        ...(this.mode === 'demo' ? ['--spring.profiles.active=demo'] : []),
        ...(options?.collectionEnabled === undefined
          ? []
          : [`--app.collection.enabled=${options.collectionEnabled}`]),
        '--server.port=18080',
      ],
      {
        env: {
          ...process.env,
          JDBC_URL: this.jdbcUrl,
          JDBC_USERNAME: 'b03_test',
          JDBC_PASSWORD: this.password,
          APP_BASE_URL: 'http://127.0.0.1:5174',
        },
        stdio: ['ignore', 'pipe', 'pipe'],
      },
    );
    for (const stream of [this.backend.stdout!, this.backend.stderr!]) {
      createInterface({ input: stream }).on('line', (line) =>
        this.backendLog?.write(
          line.replaceAll(this.password, '[redacted]') + '\n',
        ),
      );
    }
    this.backend.on('error', (error) => this.backendLog?.write(String(error)));
    await expect
      .poll(
        async () => {
          if (this.backend?.exitCode !== null)
            throw new Error(
              `Backend ${this.mode} encerrou; consulte backend-${this.mode}.log.`,
            );
          try {
            return (await fetch('http://127.0.0.1:18080/health/ready')).status;
          } catch {
            return 0;
          }
        },
        { timeout: 60_000, intervals: [100, 250, 500] },
      )
      .toBe(200);
  }
  async stop() {
    await stopProcess(this.backend);
  }
  async dispose() {
    try {
      await this.stop();
    } finally {
      try {
        if (this.containerCreated) this.docker('rm', '--force', this.container);
      } finally {
        this.backendLog?.end();
      }
    }
  }
}
