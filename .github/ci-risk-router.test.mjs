import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { route } from './ci-risk-router.mjs';

const GITHUB_DIR = dirname(fileURLToPath(import.meta.url));
const WORKFLOW_DIR = join(GITHUB_DIR, 'workflows');

/**
 * Every file that can carry a `uses:` line, not just the workflows: a composite action's pins are
 * exactly as much of a supply-chain surface as a workflow's, and scanning only `workflows/` left
 * `actions/setup-nusmv/action.yml` unguarded through a version bump.
 */
function actionManifests() {
  const files = readdirSync(WORKFLOW_DIR)
    .filter((f) => f.endsWith('.yml'))
    .map((f) => ({ label: `workflows/${f}`, path: join(WORKFLOW_DIR, f) }));
  for (const entry of readdirSync(join(GITHUB_DIR, 'actions'), { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    files.push({
      label: `actions/${entry.name}/action.yml`,
      path: join(GITHUB_DIR, 'actions', entry.name, 'action.yml'),
    });
  }
  return files;
}

/**
 * Routing decides whether a change is allowed to reach main without full validation, so a wrong
 * answer here is not a slow pipeline — it is an unverified release. These tests pin the two
 * directions that matter: cheap changes must stay cheap, and risky changes must escalate no matter
 * how small they are.
 */

test('documentation-only changes run nothing', () => {
  for (const paths of [
    ['README.md'],
    ['docs/architecture/shared-value-semantics.md'],
    ['CHANGELOG.md', 'docs/README.md', 'backend/README.md'],
    ['docs/examples/screenshot.png'],
    ['.gitignore'],
  ]) {
    const decision = route(paths, { ref: 'refs/heads/feature/x' });
    assert.equal(decision.inertOnly, true, `expected inert: ${paths}`);
    assert.equal(decision.fast, false);
    assert.equal(decision.full, false);
  }
});

test('an ordinary source change runs fast CI only', () => {
  const decision = route(
    ['frontend/src/components/DeviceDialog.vue', 'frontend/src/assets/i18n.ts'],
    { ref: 'refs/heads/feature/x' },
  );
  assert.equal(decision.fast, true);
  assert.equal(decision.full, false, 'a UI component tweak should not need the full E2E suite');
  assert.equal(decision.frontend, true);
  assert.equal(decision.backend, false, 'no backend tree touched');
});

test('a single high-risk line escalates to full CI regardless of diff size', () => {
  const cases = [
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/component/nusmv/generator/module/SmvMainModuleBuilder.java', 'NuSMV model generation'],
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/component/fuzz/FuzzModel.java', 'bounded exploration engine'],
    ['backend/device-template-schema.json', 'template schema is the authoring contract'],
    ['backend/src/main/resources/deviceTemplate/Light.json', 'bundled template semantics'],
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/security/JwtAuthFilter.java', 'security filters'],
    ['backend/src/main/resources/db/migration/V9__add_column.sql', 'database migrations'],
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/po/UserPo.java', 'persistence mapping'],
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/dto/verification/VerificationRequestDto.java', 'REST/DTO contract'],
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/controller/VerificationController.java', 'REST surface'],
    ['frontend/src/stores/board.ts', 'cross-cutting frontend state/routing'],
    ['frontend/src/api/verify.ts', 'frontend API client contract'],
    ['frontend/src/utils/modelRequest.ts', 'shared model contract'],
    ['.github/workflows/fast-ci.yml', 'CI infrastructure'],
    ['frontend/playwright.config.ts', 'test infrastructure'],
    ['frontend/e2e/board-full-flow.spec.ts', 'E2E harness'],
    ['backend/pom.xml', 'backend dependency graph'],
    ['frontend/package-lock.json', 'frontend dependency graph'],
  ];

  for (const [path, why] of cases) {
    const decision = route([path], { ref: 'refs/heads/feature/x' });
    assert.equal(decision.full, true, `expected full CI for ${path}`);
    assert.ok(decision.reasons.includes(why), `expected reason "${why}" for ${path}, got ${decision.reasons}`);
    assert.equal(decision.fast, false, 'Full CI already includes the smoke journeys');
    assert.equal(decision.backend, false, 'Full CI owns backend validation');
    assert.equal(decision.frontend, false, 'Full CI owns frontend validation');
  }
});

test('a high-risk path mixed with docs still escalates', () => {
  const decision = route(
    ['README.md', 'backend/src/main/java/cn/edu/nju/Iot_Verify/po/UserPo.java'],
    { ref: 'refs/heads/feature/x' },
  );
  assert.equal(decision.inertOnly, false);
  assert.equal(decision.full, true);
});

test('main, schedule, and manual dispatch select Full CI without duplicating Fast tiers', () => {
  for (const context of [
    { ref: 'refs/heads/main', eventName: 'push' },
    { ref: 'refs/heads/feature/x', eventName: 'schedule' },
    { ref: 'refs/heads/feature/x', eventName: 'workflow_dispatch' },
    { ref: 'refs/heads/feature/x', eventName: 'push', forceFull: true },
  ]) {
    const decision = route(['README.md'], context);
    assert.equal(decision.full, true, `expected full CI for ${JSON.stringify(context)}`);
    assert.equal(decision.fast, false);
    assert.equal(decision.backend, false);
    assert.equal(decision.frontend, false);
    assert.equal(decision.inertOnly, false, 'main must not be short-circuited by a docs-only diff');
  }
});

test('an unrecognised path escalates rather than being silently skipped', () => {
  // A new top-level directory has unknown blast radius. Failing safe costs a slow pipeline; failing
  // open ships something nothing validated.
  const decision = route(['services/new-thing/main.go'], { ref: 'refs/heads/feature/x' });
  assert.equal(decision.full, true);
  assert.equal(decision.fast, false);
  assert.equal(decision.backend, false);
  assert.equal(decision.frontend, false);
  assert.ok(decision.reasons.some((r) => r.startsWith('unclassified path')), decision.reasons.join('; '));
});

test('the template admission gate is high risk, like the contract it enforces', () => {
  // `device-template-schema.json` was already high-risk as "the authoring contract" while the code
  // enforcing it routed as an ordinary source change — so a fix to `DeviceTemplateNuSmvValidator`
  // shipped on Fast CI only. A wrong edit there admits a manifest whose generated model NuSMV refuses,
  // which is the defect class the schema entry exists to guard.
  const decision = route(
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/component/template/DeviceTemplateNuSmvValidator.java'],
    { ref: 'refs/heads/feature/x' },
  );
  assert.equal(decision.full, true);
  assert.ok(
    decision.reasons.some((r) => r === 'template admission gate'),
    decision.reasons.join('; '),
  );
});

test('the two admission gates are high risk, and ordinary service classes are not', () => {
  // Same reasoning as the template gate: these decide what may be persisted or verified, so a wrong
  // edit admits a board whose generated model the engine refuses — or refuses a board that was always
  // legal. Both happened on this branch, and the 127-line change that caused the second one routed as
  // "low-risk source change" and skipped Full CI entirely.
  for (const [path, why] of [
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/BoardStorageServiceImpl.java',
      'board admission gate'],
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/NusmvRequestValidator.java',
      'verification request admission gate'],
  ]) {
    const decision = route([path], { ref: 'refs/heads/feature/x' });
    assert.equal(decision.full, true, path);
    assert.ok(decision.reasons.some((r) => r === why), decision.reasons.join('; '));
  }

  // The narrowing half: `service/impl/` is a large package and must not escalate wholesale, or the
  // escalation stops meaning anything.
  const ordinary = route(
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/UserServiceImpl.java'],
    { ref: 'refs/heads/feature/x' },
  );
  assert.equal(ordinary.full, false, ordinary.reasons.join('; '));
});

test('scene files and their generator are model inputs, not documentation', () => {
  // These reach real-NuSMV regressions, and the generator is their only source. They previously
  // escalated through the unclassified fail-safe: right tier, wrong reason, and silently dependent on
  // that fallback staying put. Named explicitly, they must no longer appear as unclassified — a
  // recognised path crowding that list would bury a genuinely new directory in it.
  for (const path of [
    'docs/examples/default-away-mode-unlock-scene.json',
    'scripts/generate-default-template-scenes.mjs',
  ]) {
    const decision = route([path], { ref: 'refs/heads/feature/x' });
    assert.equal(decision.full, true, path);
    assert.ok(
      !decision.reasons.some((r) => r.startsWith('unclassified path')),
      `${path} should be recognised, got: ${decision.reasons.join('; ')}`,
    );
  }
});

test('an empty change list still runs fast CI', () => {
  const decision = route([], { ref: 'refs/heads/feature/x' });
  assert.equal(decision.fast, true);
  assert.equal(decision.full, false);
  assert.equal(decision.inertOnly, false);
});

test('backend-only and frontend-only changes scope their suites', () => {
  const backendOnly = route(
    ['backend/src/main/java/cn/edu/nju/Iot_Verify/service/impl/ChatServiceImpl.java'],
    { ref: 'refs/heads/feature/x' },
  );
  assert.equal(backendOnly.backend, true);
  assert.equal(backendOnly.frontend, false);
  assert.equal(backendOnly.full, false);

  const frontendOnly = route(['frontend/src/views/Landing.vue'], { ref: 'refs/heads/feature/x' });
  assert.equal(frontendOnly.frontend, true);
  assert.equal(frontendOnly.backend, false);
  assert.equal(frontendOnly.full, false);
});

test('every high-risk rule states why it is high risk', () => {
  // Without a stated standard the list becomes folklore and nobody can judge an addition.
  const decision = route(['backend/pom.xml'], { ref: 'refs/heads/feature/x' });
  for (const reason of decision.reasons) {
    assert.ok(reason.length > 3, `reason too vague: ${reason}`);
  }
});

test('every workflow action is pinned to a full commit sha with a version comment', () => {
  // A wrong pin fails at "Set up job" with an unresolvable-action error, which looks like an
  // infrastructure outage rather than a typo. It cost one red run here: a download-artifact sha was
  // copied from the wrong repository's history and resolved fine against that repo, so a naive check
  // passed. This asserts the shape locally; the comment is what makes a bad pin reviewable.
  const files = actionManifests();
  assert.ok(files.length >= 4, `expected the workflow set plus composite actions, found ${files.length}`);

  let pins = 0;
  for (const { label, path } of files) {
    const text = readFileSync(path, 'utf8');
    for (const line of text.split(/\r?\n/)) {
      const match = /^\s*uses:\s*([^\s#]+)/.exec(line);
      if (!match) continue;
      const ref = match[1];
      if (ref.startsWith('./')) continue; // local action or reusable workflow
      assert.match(ref, /@[0-9a-f]{40}$/, `${label}: action must be sha-pinned, got "${ref}"`);
      assert.match(line, /#\s*v\d/, `${label}: sha pin needs a version comment: "${line.trim()}"`);
      pins += 1;
    }
  }
  // Guards the scan itself: a refactor that stopped matching `uses:` lines would otherwise pass this
  // test by checking nothing, which is the failure mode a structural assertion is worst at showing.
  assert.ok(pins >= 6, `expected the known pins to be scanned, saw ${pins}`);
});

test('the live-AI gate cannot silently stop gating', () => {
  // The gate is skipped when no key is configured, and a skipped job makes the run report `success`.
  // That is only safe while three things hold together, none of which is visible at the edit site:
  // the suite gates on the preflight's boolean, the paid job carries its own repository check, and the
  // `status` job renames itself so an unverified run does not read as a verified one. Dropping any of
  // them turns a paid gate into a permanent silent skip that no other check notices.
  const text = readFileSync(join(WORKFLOW_DIR, 'live-ai-ci.yml'), 'utf8');

  assert.match(
    text,
    /^\s*configured:\s*\$\{\{\s*steps\.probe\.outputs\.configured\s*\}\}$/m,
    'preflight must publish the `configured` output the gate depends on',
  );
  const suiteGate = /^\s{2}live-ai:[\s\S]*?^\s{4}if:\s*(.+)$/m.exec(text);
  assert.ok(suiteGate, 'the live-ai job must declare an `if:` gate');
  assert.match(
    suiteGate[1],
    /needs\.preflight\.outputs\.configured\s*==\s*'true'/,
    'the live-ai job must gate on the preflight boolean',
  );
  assert.match(
    suiteGate[1],
    /github\.repository\s*==\s*'sharkdingo\/IoT-Verify'/,
    'the job that spends external quota must carry its own repository check, not inherit it via needs',
  );
  assert.match(
    text,
    /^\s{2}status:$/m,
    'a `status` job must exist so a skipped gate does not read as a passing one',
  );
  assert.match(
    text,
    /name:\s*\$\{\{[^}]*NOT verified/,
    "the status job's name must announce an unverified run, because the run's own conclusion cannot",
  );
});

test('local composite actions declare a supported runtime', () => {
  // GitHub now forces the Node 20 runtimes it deprecated onto Node 24 regardless of what `action.yml`
  // declares, so a `node20` declaration means the action runs on a runtime it was never tested on and a
  // deprecation warning is the only notice. Remote pins need an API call to check and are covered by the
  // bump procedure in docs/development/ci.md; a local manifest is checkable here, so it is checked here.
  for (const { label, path } of actionManifests()) {
    if (!label.startsWith('actions/')) continue;
    const runtime = /^\s*using:\s*'?([\w.]+)'?/m.exec(readFileSync(path, 'utf8'));
    assert.ok(runtime, `${label}: must declare a runtime`);
    assert.ok(
      ['composite', 'docker', 'node24'].includes(runtime[1]),
      `${label}: unsupported or deprecated runtime "${runtime[1]}"`,
    );
  }
});

test('Maven jobs require real MySQL integration coverage', () => {
  for (const [file, job] of [['fast-ci.yml', 'backend'], ['full-ci.yml', 'build']]) {
    const text = readFileSync(join(WORKFLOW_DIR, file), 'utf8');
    const body = text.split(new RegExp(`^  ${job}:\\s*$`, 'm'))[1]
      ?.split(/^  [\w-]+:\s*$/m)[0];
    assert.ok(body, `${file}: missing ${job} job`);
    assert.match(body, /services:\s+mysql:\s+image: mysql:/, `${file}: Maven needs real MySQL`);
    assert.match(body, /MYSQL_DATABASE: iot_verify_undo_it\b/, `${file}: use a dedicated test database`);
    assert.match(body, /DB_USERNAME: iot_verify\b/, `${file}: give tests the service credentials`);
    assert.match(body, /DB_PASSWORD: iot_verify_ci\b/, `${file}: give tests the service credentials`);
    assert.match(body, /mvn -B package -Diot\.verify\.mysql\.required=true\b/,
      `${file}: an unavailable MySQL must fail instead of skipping integration tests`);
  }
});

test('every workflow script is committed executable', () => {
  // A script without the git execute bit fails in CI with exit code 126 -- "Permission denied" -- long
  // after setup succeeded, which reads like a broken runner rather than a missing file mode. It cost a
  // red run on both tiers here. Windows checkouts do not carry the bit, so git's index is the only
  // place the truth lives, and this asserts it there.
  const modes = execFileSync('git', ['ls-files', '-s', '.github/scripts'], { encoding: 'utf8' });
  const lines = modes.split(/\r?\n/).filter(Boolean);
  assert.ok(lines.length > 0, 'expected at least one workflow script');
  for (const line of lines) {
    const [mode, , , path] = line.split(/\s+/);
    assert.equal(mode, '100755', `${path} must be committed executable (git mode 100755), found ${mode}`);
  }
});

test('main runs cannot be superseded by a later push', () => {
  // Cancelling a main run destroys the recorded verdict for a commit that is already released.
  // `cancel-in-progress: false` alone did not hold: a Full CI run on main was cancelled the moment
  // the next commit landed. Including the sha in the group makes each main commit its own group, so
  // supersession is impossible by construction rather than by a boolean evaluating as hoped.
  for (const file of ['fast-ci.yml', 'full-ci.yml']) {
    const text = readFileSync(join(WORKFLOW_DIR, file), 'utf8');
    const group = /^concurrency:[\s\S]*?group:\s*(.+)$/m.exec(text);
    assert.ok(group, `${file} must declare a concurrency group`);
    assert.match(
      group[1],
      /github\.sha/,
      `${file}: the group must include github.sha for main so a later push cannot cancel it`,
    );
    assert.match(group[1], /refs\/heads\/main/, `${file}: the sha must apply only to main`);
  }
});
