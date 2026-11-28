import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  definedAnchors, headingSlug, isExternal, linksIn, shouldSkipDirectory
} from './docs-link-check.mjs';

test('a heading anchor drops backticks and punctuation the way GitHub does', () => {
  // The repo links to `#environmentvalueprovenancedto`, whose heading is written in backticks.
  // Getting this wrong would report every correct link as broken, which is worse than no check:
  // a noisy required gate gets bypassed.
  assert.equal(headingSlug('### `EnvironmentValueProvenanceDto`'), 'environmentvalueprovenancedto');
  assert.equal(headingSlug('## Type and domain'), 'type-and-domain');
  assert.equal(headingSlug('## 4. Capabilities: read and affect'), '4-capabilities-read-and-affect');
});

test('punctuation dropped between two spaces leaves a double hyphen, as on GitHub', () => {
  // Collapsing the spaces accepted `#strategy-2-condition-adjustment-...`, which GitHub does not
  // define, so a link that lands nowhere passed the check.
  assert.equal(headingSlug('## Strategy 2 — condition adjustment (`ConditionAdjustStrategy`)'),
    'strategy-2--condition-adjustment-conditionadjuststrategy');
});

test('anchors come from headings only, not from bold text that looks like one', () => {
  const anchors = definedAnchors([
    '# Title',
    '',
    '**Not a heading.** Prose that happens to be emphasised.',
    '',
    '## Real section'
  ].join('\n'));
  assert.deepEqual([...anchors].sort(), ['real-section', 'title']);
});

test('a link is split into its path and fragment', () => {
  assert.deepEqual(linksIn('See [the contract](api/verification.md#field-table).'), [
    { target: 'api/verification.md', fragment: 'field-table' }
  ]);
  assert.deepEqual(linksIn('See [the page](api/verification.md).'), [
    { target: 'api/verification.md', fragment: null }
  ]);
});

test('a same-document fragment link has an empty target', () => {
  assert.deepEqual(linksIn('Jump to [section 7](#7-composition).'), [
    { target: '', fragment: '7-composition' }
  ]);
});

test('links inside fenced code are ignored', () => {
  // A sample request body or shell transcript can contain something shaped like a link. Failing on
  // an example would push people to reword documentation to satisfy the checker.
  const markdown = [
    'Real: [config](getting-started/configuration.md)',
    '',
    '```bash',
    'curl [not-a-link](http://example.invalid/nope)',
    '```',
    '',
    '```json',
    '{"see": "[docs](does/not/exist.md)"}',
    '```'
  ].join('\n');
  assert.deepEqual(linksIn(markdown), [
    { target: 'getting-started/configuration.md', fragment: null }
  ]);
});

test('external references are not resolved locally', () => {
  // Fetching them would let an unrelated network failure fail the commit.
  assert.equal(isExternal('https://example.com/x'), true);
  assert.equal(isExternal('http://example.com/x'), true);
  assert.equal(isExternal('mailto:a@b.c'), true);
  assert.equal(isExternal('//cdn.example.com/x'), true);
  assert.equal(isExternal('api/verification.md'), false);
  assert.equal(isExternal('../architecture/overview.md'), false);
});

test('generated and vendored trees are skipped', () => {
  // Scanning node_modules would drown real findings and make the check slow enough to be disabled.
  for (const directory of ['node_modules', 'target', 'dist', '.git']) {
    assert.equal(shouldSkipDirectory(directory), true, directory);
  }
  for (const directory of ['docs', 'api', 'architecture', 'backend']) {
    assert.equal(shouldSkipDirectory(directory), false, directory);
  }
});

test('the CLI accepts directory links and reports missing targets and headings', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'iot-verify-doc-links-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'examples'));
  const page = join(root, 'guide.md');
  const valid = '# Guide\n[examples](examples/) and [guide](#guide)';
  const cli = fileURLToPath(new URL('./docs-link-check-cli.mjs', import.meta.url));
  const check = () => spawnSync(process.execPath, [cli, root], { encoding: 'utf8' });

  writeFileSync(page, valid);
  const accepted = check();
  assert.equal(accepted.status, 0, accepted.stderr);

  writeFileSync(page, `${valid}\n[missing](missing.md) and [section](#missing-section)`);
  const rejected = check();
  assert.equal(rejected.status, 1);
  assert.match(rejected.stderr, /2 unresolvable documentation reference/);
  assert.match(rejected.stderr, /link: {2}missing\.md\s+cause: target does not exist/);
  assert.match(rejected.stderr, /link: {2}#missing-section\s+cause: no heading in guide\.md/);
});
