import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { route } from './ci-risk-router.mjs';

const workflow = name => readFileSync(new URL(`./workflows/${name}-ci.yml`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const fast = workflow('fast');
const full = workflow('full');
const job = (text, name) => text.split(new RegExp(`^  ${name}:\\s*$`, 'm'))[1]?.split(/^  [\w-]+:\s*$/m)[0];

function enabled(body, needs, ref) {
  assert.ok(body, 'job must exist');
  const lines = body.split('\n');
  const index = lines.findIndex(line => line.startsWith('    if:'));
  assert.ok(index >= 0, 'conditional tier must declare its selection');
  let expression = lines[index].slice('    if:'.length).trim();
  if (expression === '|') {
    expression = lines.slice(index + 1).slice(0, lines.slice(index + 1).findIndex(line => !line.startsWith('      '))).join(' ').trim();
  }
  // These are the workflow's actual string comparisons, boolean operators and always() calls.
  return Function('needs', 'github', 'always', `return (${expression})`)(needs, { ref }, () => true);
}

test('routing executes one owner and gives every smoke run both artifact producers', () => {
  for (const [paths, ref, expected] of [
    [['README.md'], 'refs/heads/main', ['escalate']],
    [['backend/pom.xml'], 'refs/heads/topic', ['escalate']],
    [['frontend/src/views/Landing.vue'], 'refs/heads/topic', ['frontend', 'backend', 'smoke']],
    [['backend/src/main/java/example/Example.java'], 'refs/heads/topic', ['frontend', 'backend', 'smoke']],
    [['README.md'], 'refs/heads/topic', []],
  ]) {
    const decision = route(paths, { ref });
    const outputs = Object.fromEntries(Object.entries(decision).map(([key, value]) => [key === 'fast' ? 'smoke' : key, String(value)]));
    const needs = { route: { result: 'success', outputs } };
    const selected = [];
    for (const name of ['frontend', 'backend', 'smoke', 'escalate']) {
      const run = enabled(job(fast, name), needs, ref);
      needs[name] = { result: run ? 'success' : 'skipped' };
      if (run) selected.push(name);
    }
    assert.deepEqual(selected, expected, `${ref}: ${paths}`);
  }
});

test('Full CI has one push owner and the aggregate waits for its result', () => {
  assert.doesNotMatch(full, /^  push:/m, 'Fast CI must be the only push entry point');
  for (const trigger of ['schedule', 'workflow_dispatch', 'workflow_call']) {
    assert.match(full, new RegExp(`^  ${trigger}:`, 'm'));
  }
  assert.match(job(fast, 'escalate'), /^    needs: route$/m);
  assert.match(job(fast, 'fast-ci'), /^    needs: \[route, frontend, backend, smoke, escalate\]$/m);
  assert.match(job(fast, 'fast-ci'), /CI_NEEDS_JSON: \$\{\{ toJSON\(needs\) \}\}/);
  assert.match(job(fast, 'fast-ci'), /node \.github\/check-ci-results\.mjs/);
});

test('browser jobs consume the current workflow bundles instead of rebuilding', () => {
  for (const [text, frontend, backend, browser, prefix] of [
    [fast, 'frontend', 'backend', 'smoke', ''],
    [full, 'frontend', 'build', 'e2e', 'full-'],
  ]) {
    for (const [producer, artifact] of [[frontend, `${prefix}frontend-dist`], [backend, `${prefix}backend-jar`]]) {
      const name = `name: ${artifact}-` + '${{ github.sha }}';
      assert.ok(job(text, producer).includes(name), `${artifact} must have a producer`);
      assert.ok(job(text, browser).includes(name), `${artifact} must be downloaded`);
    }
    assert.match(job(text, browser), /E2E_PREBUILT_DIST: '1'/);
  }
  assert.match(job(full, 'e2e'), /^    needs: \[build, frontend\]$/m);
});
