import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import assert from 'node:assert/strict';
import { inspectLinks } from './check_links.mjs';

function fixture(body, extra = {}) {
  const root = mkdtempSync(join(tmpdir(), 'caderno-links-'));
  const files = {
    'README.md': body,
    'docs/Guide.md': '# Café **local**\n\n# Café **local**\n',
    'docs/image.png': 'synthetic',
    ...extra,
  };
  for (const [name, value] of Object.entries(files)) {
    const path = join(root, name);
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, value);
  }
  return {
    root,
    names: Object.keys(files),
    cleanup: () => rmSync(root, { recursive: true }),
  };
}

test('resolves relative images, references, encoded duplicate fragments, and ignores code/external URLs', () => {
  const f = fixture(
    '[guide][g]\n\n[g]: docs/Guide.md#caf%C3%A9-local-1\n\n![image](docs/image.png)\n\n`[example](missing.md)`\n\n```md\n[example](missing.md)\n```\n\n[external](https://example.invalid)\n',
  );
  try {
    assert.deepEqual(inspectLinks(f.root, f.names), []);
  } finally {
    f.cleanup();
  }
});
test('rejects missing files, wrong fragments, wrong case, and files outside delivery', () => {
  const f = fixture(
    '[missing](missing.md) [fragment](docs/Guide.md#wrong) [case](docs/guide.md) [outside](private.md)\n',
    { 'private.md': '# Private\n' },
  );
  try {
    const errors = inspectLinks(
      f.root,
      f.names.filter((n) => n !== 'private.md'),
    );
    assert.equal(errors.length, 4);
    assert.ok(errors.some((e) => e.includes('fragment missing')));
    assert.ok(errors.some((e) => e.includes('outside the delivery set')));
  } finally {
    f.cleanup();
  }
});

test('inline code cannot invent a fragment anchor', () => {
  const f = fixture('[fake](#fake) and `id="fake"`\n');
  try {
    assert.ok(
      inspectLinks(f.root, f.names).some((error) =>
        error.includes('fragment missing'),
      ),
    );
  } finally {
    f.cleanup();
  }
});

test('machine-local file URLs cannot bypass delivery membership', () => {
  const f = fixture('[local](file:///private/absent.md)\n');
  try {
    assert.ok(
      inspectLinks(f.root, f.names).some((error) =>
        error.includes('not distributable'),
      ),
    );
  } finally {
    f.cleanup();
  }
});
