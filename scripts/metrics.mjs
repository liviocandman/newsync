import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { tseslint, ESLint } from '../tools/quality/runtime.mjs';

const tooling = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const root = process.argv[3] ? resolve(process.argv[3]) : tooling;
const output = resolve(process.argv[2]);
mkdirSync(output, { recursive: true });
const lint = new ESLint({
  cwd: root,
  overrideConfigFile: true,
  overrideConfig: [
    { ignores: ['**/node_modules/**', '**/target/**'] },
    {
      languageOptions: { parser: tseslint.parser },
      files: ['**/*.{js,mjs,mts,ts,tsx}'],
      rules: {
        complexity: ['warn', 0],
        'max-lines-per-function': [
          'warn',
          { max: 0, skipBlankLines: true, skipComments: true },
        ],
      },
    },
  ],
});
const scope = [
  'frontend/src',
  'frontend/scripts',
  'scripts',
  ...(existsSync(resolve(root, 'eslint.config.mjs'))
    ? ['eslint.config.mjs']
    : []),
];
const results = await lint.lintFiles(scope);
if (
  !results.length ||
  results.some((result) => result.fatalErrorCount || result.errorCount)
)
  throw new Error('Complexity discovery failed or lint errors were found');
const metrics = results.map((result) => ({
  file: result.filePath.slice(root.length + 1),
  lines: readFileSync(result.filePath, 'utf8').split('\n').length - 1,
  units: result.messages.filter((m) =>
    ['complexity', 'max-lines-per-function'].includes(m.ruleId),
  ),
}));
if (!metrics.some((result) => result.units.length))
  throw new Error('No JS/TS complexity units discovered');
writeFileSync(
  resolve(output, 'complexity.json'),
  JSON.stringify(
    {
      eslint: '10.14.0',
      scope,
      parameters: { complexityThreshold: 0, functionLengthThreshold: 0 },
      files: metrics,
    },
    null,
    2,
  ) + '\n',
);
const cpd = spawnSync(
  process.execPath,
  [
    resolve(tooling, 'tools/quality/node_modules/.bin/jscpd'),
    '--format',
    'java,typescript,tsx,javascript',
    '--min-lines',
    '5',
    '--min-tokens',
    '50',
    '--reporters',
    'json,html',
    '--output',
    output,
    '--ignore',
    '**/node_modules/**,**/target/**,**/*.test.*,**/test-setup.ts',
    'backend/src/main/java',
    'frontend/src',
    'frontend/scripts',
    'scripts',
  ],
  { cwd: root, encoding: 'utf8', timeout: 180000 },
);
process.stdout.write(cpd.stdout ?? '');
process.stderr.write(cpd.stderr ?? '');
if (cpd.status !== 0) throw new Error(`CPD failed: ${cpd.status}`);
const report = JSON.parse(
  readFileSync(resolve(output, 'jscpd-report.json'), 'utf8'),
);
if (!report.statistics?.total?.lines || !report.statistics?.total?.sources)
  throw new Error('Empty CPD inventory');
writeFileSync(
  resolve(output, 'summary.json'),
  JSON.stringify(
    {
      status: 'diagnostic',
      complexity: 'complexity.json',
      duplication: 'jscpd-report.json',
      jscpd: '5.4.2',
      cpd: report.statistics.total,
      exclusions: [
        'generated outputs and dependency directories',
        'test fixtures and tests are measured by their test runners; this baseline covers production units and maintenance scripts',
      ],
      limitation:
        'Token duplication and complexity are review diagnostics, without global quality thresholds.',
    },
    null,
    2,
  ) + '\n',
);
