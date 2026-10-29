import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { checkCIResults } from './check-ci-results.mjs';
import { route } from './ci-risk-router.mjs';

const cases = [
  ['main', ['README.md'], 'refs/heads/main', ['escalate']],
  ['high risk', ['backend/pom.xml'], 'refs/heads/topic', ['escalate']],
  ['frontend', ['frontend/src/views/Landing.vue'], 'refs/heads/topic', ['frontend', 'backend', 'smoke']],
  ['backend', ['backend/src/main/java/example/Example.java'], 'refs/heads/topic', ['frontend', 'backend', 'smoke']],
  ['docs', ['README.md'], 'refs/heads/topic', []],
];

for (const [label, paths, ref, required] of cases) {
  test(`${label}: the aggregate requires exactly the selected tiers`, () => {
    const decision = route(paths, { ref });
    const needs = {
      route: { result: 'success', outputs: Object.fromEntries(Object.entries(decision).map(([key, value]) => [key === 'fast' ? 'smoke' : key, String(value)])) },
      ...Object.fromEntries(['frontend', 'backend', 'smoke', 'escalate'].map(job =>
        [job, { result: required.includes(job) ? 'success' : 'skipped' }])),
    };
    assert.doesNotThrow(() => checkCIResults(needs));
    for (const job of ['frontend', 'backend', 'smoke', 'escalate']) {
      for (const result of ['success', 'skipped', 'failure', 'cancelled']) {
        if (result === needs[job].result) continue;
        assert.throws(() => checkCIResults({ ...needs, [job]: { result } }),
          undefined, `${label}: ${job} must reject ${result}`);
      }
    }
    for (const result of ['failure', 'cancelled', 'skipped']) {
      assert.throws(() => checkCIResults({ ...needs, route: { ...needs.route, result } }),
        undefined, `${label}: routing must reject ${result}`);
    }
  });
}

test('missing or malformed routing outputs cannot turn every tier into a skip', () => {
  const needs = {
    route: { result: 'success', outputs: { full: 'false', smoke: 'false', frontend: 'false', backend: 'false' } },
    ...Object.fromEntries(['frontend', 'backend', 'smoke', 'escalate'].map(job => [job, { result: 'skipped' }])),
  };
  for (const key of ['full', 'smoke', 'frontend', 'backend']) {
    for (const value of [undefined, '', 'unknown', true]) {
      assert.throws(() => checkCIResults({ ...needs,
        route: { ...needs.route, outputs: { ...needs.route.outputs, [key]: value } },
      }), undefined, `${key} must reject ${value}`);
    }
  }
});

test('the workflow CLI returns failure when selected Full CI did not pass', () => {
  const needs = {
    route: { result: 'success', outputs: { full: 'true', smoke: 'false', frontend: 'false', backend: 'false' } },
    ...Object.fromEntries(['frontend', 'backend', 'smoke'].map(job => [job, { result: 'skipped' }])),
  };
  for (const result of ['success', 'cancelled']) {
    const command = spawnSync(process.execPath, [fileURLToPath(new URL('./check-ci-results.mjs', import.meta.url))], {
      encoding: 'utf8', env: { ...process.env,
        CI_NEEDS_JSON: JSON.stringify({ ...needs, escalate: { result } }),
      },
    });
    assert.equal(command.status, result === 'success' ? 0 : 1, command.stderr);
  }
});
