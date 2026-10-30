import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const [mode, target] = process.argv.slice(2);
if (!['normal', 'demo'].includes(mode) || !target)
  throw new Error('Build exige modo normal/demo e diretório Maven explícitos.');
const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = resolve(target, 'generated-resources', 'web-ui');
// Alternar perfis sem clean também elimina fixture/UI antigas no classpath.
for (const directory of [
  output,
  resolve(target, 'classes/web-ui'),
  resolve(target, 'classes/demo'),
])
  rmSync(directory, { recursive: true, force: true });
const env = Object.fromEntries(
  Object.entries(process.env).filter(([key]) =>
    /^(PATH|HOME|USER|TMPDIR|CI|LANG|LC_.*|NODE_EXTRA_CA_CERTS|npm_config_cache)$/.test(
      key,
    ),
  ),
);
execFileSync(
  process.platform === 'win32' ? 'npm.cmd' : 'npm',
  ['ci', '--no-audit', '--no-fund'],
  { cwd: frontend, env, stdio: 'inherit' },
);
execFileSync(
  process.execPath,
  [
    'node_modules/vite/bin/vite.js',
    'build',
    '--mode',
    mode,
    '--outDir',
    output,
    '--emptyOutDir',
  ],
  { cwd: frontend, env, stdio: 'inherit' },
);
const html = readFileSync(join(output, 'index.html'), 'utf8');
const assets = readdirSync(join(output, 'assets')).sort();
if (
  !html.includes(`name="news-ui-mode" content="${mode}"`) ||
  /nonce=|@vite|<style|<script(?![^>]*\bsrc=)/i.test(html) ||
  !assets.some((name) => name.endsWith('.js')) ||
  !assets.some((name) => name.endsWith('.css')) ||
  assets.some(
    (name) => !/^[a-zA-Z0-9_-]+-[a-zA-Z0-9_-]{8,}\.(js|css)$/.test(name),
  )
)
  throw new Error('UI ausente ou incompatível com o documento produtivo.');
writeFileSync(
  join(output, 'bundle.properties'),
  `mode=${mode}\nassets=${assets.join(',')}\n`,
);
