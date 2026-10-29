#!/usr/bin/env node
/**
 * Decides which CI tiers a change needs, from the paths it touches.
 *
 * This is a script rather than inline YAML because the decision is real logic with real consequences
 * — routing a formal-model change away from full validation would let an unsound verdict reach main —
 * and logic that matters deserves tests. `ci-risk-router.test.mjs` exercises it directly.
 *
 * The rule is deliberately *not* "small diff means small pipeline". A one-line change to a security
 * filter, a migration, a shared contract, or the NuSMV generator is exactly the kind of edit whose
 * blast radius has nothing to do with its size. So every path maps to an area, and areas — not
 * counts — select the tier.
 *
 * Fail-safe direction: anything unrecognised escalates to full validation. A new top-level directory
 * should cost a slow pipeline, not silently bypass one.
 */

/** Paths that cannot affect any build, test, or runtime behaviour. */
const INERT = [
  /^\.gitignore$/,
  /^\.gitattributes$/,
  /^LICENSE$/,
  /^CHANGELOG\.md$/,
  /^(?:[^/]+\/)*[^/]*\.md$/, // any markdown, at any depth
  /^docs\/.*\.(png|jpg|jpeg|svg|gif|webp|mp4)$/,
];

/**
 * High-risk areas. A single line here earns the full pipeline.
 *
 * Each entry says *why*, because a reader deciding whether to add one needs the standard, not just
 * the list: the question is "can a wrong edit here produce a wrong verdict, leak data, corrupt
 * persisted state, or break a contract another layer already trusts?"
 */
const HIGH_RISK = [
  // A wrong model is worse than a broken build: it produces confident false verdicts.
  { pattern: /^backend\/src\/main\/java\/.*\/component\/nusmv\//, why: 'NuSMV model generation' },
  { pattern: /^backend\/src\/main\/java\/.*\/component\/fuzz\//, why: 'bounded exploration engine' },
  { pattern: /^backend\/device-template-schema\.json$/, why: 'template schema is the authoring contract' },
  { pattern: /^backend\/src\/main\/resources\/deviceTemplate\//, why: 'bundled template semantics' },
  // The code that *enforces* that contract belongs with the contract itself. Without this entry a
  // change to `DeviceTemplateNuSmvValidator` routed as "low-risk source change" — and a wrong edit
  // there admits a manifest whose generated model NuSMV refuses, which is exactly the class of defect
  // the schema entry above exists to guard.
  { pattern: /^backend\/src\/main\/java\/.*\/component\/template\//, why: 'template admission gate' },
  // The board admission gate, for the same reason as the template one above: it decides what may be
  // persisted, and a wrong edit there admits a board whose generated model NuSMV refuses — or refuses a
  // board that was always legal. Both happened on this branch. `BoardStorageServiceImpl` alone carries
  // the discrete-writer conflict check, the fix-generator prefix guard and the label-length check, and a
  // 127-line change to it routed as "low-risk source change" and skipped Full CI.
  { pattern: /^backend\/src\/main\/java\/.*\/service\/impl\/BoardStorageServiceImpl\.java$/,
    why: 'board admission gate' },
  { pattern: /^backend\/src\/main\/java\/.*\/service\/impl\/NusmvRequestValidator\.java$/,
    why: 'verification request admission gate' },
  // Model inputs, not documentation: these scenes are read by real-NuSMV regressions and the generator
  // is their only source. They previously reached full validation through the unclassified fail-safe,
  // which gave the right tier for the wrong reason and would have gone quiet if the fallback changed.
  { pattern: /^docs\/examples\/.*\.json$/, why: 'scene files are verification inputs' },
  { pattern: /^scripts\/generate-default-template-scenes\.mjs$/, why: 'generator for scene inputs' },

  // Authentication, authorization, and rate limiting.
  { pattern: /^backend\/src\/main\/java\/.*\/(security|filter|interceptor)\//, why: 'security filters' },
  { pattern: /^backend\/src\/main\/java\/.*(Auth|Jwt|Token|Permission|RateLimit)[A-Za-z]*\.java$/, why: 'auth/permission path' },

  // Persistence: schema and mapping errors surface as data loss, not test failures.
  { pattern: /^backend\/src\/main\/resources\/db\//, why: 'database migrations' },
  { pattern: /^backend\/src\/main\/java\/.*\/(po|repository)\//, why: 'persistence mapping' },

  // Cross-layer contracts. Both sides trust these shapes.
  { pattern: /^backend\/src\/main\/java\/.*\/dto\//, why: 'REST/DTO contract' },
  { pattern: /^backend\/src\/main\/java\/.*\/controller\//, why: 'REST surface' },

  // Cross-cutting frontend state: a regression here breaks pages no unit test covers together.
  { pattern: /^frontend\/src\/(stores|router)\//, why: 'cross-cutting frontend state/routing' },
  { pattern: /^frontend\/src\/api\//, why: 'frontend API client contract' },
  { pattern: /^frontend\/src\/utils\/(modelRequest|device|modelSemantics)\.ts$/, why: 'shared model contract' },

  // The pipeline and its harness. If routing itself is wrong, nothing downstream can be trusted.
  { pattern: /^\.github\//, why: 'CI infrastructure' },
  { pattern: /^frontend\/(playwright\.config|vitest\.config|vite\.config)\.[cm]?[jt]s$/, why: 'test infrastructure' },
  { pattern: /^frontend\/e2e\//, why: 'E2E harness' },
  { pattern: /^backend\/pom\.xml$/, why: 'backend dependency graph' },
  { pattern: /^frontend\/package(-lock)?\.json$/, why: 'frontend dependency graph' },
  { pattern: /^docker-compose\.ya?ml$/, why: 'local/CI service topology' },
];

/** Which build/test surfaces a path belongs to. */
const AREAS = [
  { pattern: /^backend\//, area: 'backend' },
  { pattern: /^frontend\//, area: 'frontend' },
];

const matchAll = (paths, rules) =>
  rules.filter((rule) => paths.some((path) => (rule.pattern ?? rule).test(path)));

/**
 * @param {string[]} changedPaths repo-relative, forward-slash separated
 * @param {{ref?: string, eventName?: string, forceFull?: boolean}} context
 */
export function route(changedPaths, context = {}) {
  const paths = (changedPaths ?? []).map((p) => p.trim()).filter(Boolean);
  const { ref = '', eventName = 'push', forceFull = false } = context;

  const onDefaultBranch = ref === 'refs/heads/main';
  const scheduledOrManual = eventName === 'schedule' || eventName === 'workflow_dispatch';

  // main and scheduled/manual runs always get the complete gate: main is what gets released, and a
  // nightly run is the only thing that catches decay in code nobody touched.
  if (onDefaultBranch || scheduledOrManual || forceFull) {
    return {
      fast: false,
      full: true,
      backend: false,
      frontend: false,
      reasons: [onDefaultBranch ? 'default branch' : scheduledOrManual ? eventName : 'forced'],
      inertOnly: false,
    };
  }

  if (paths.length === 0) {
    // No detectable change (empty push, tag move). Cheapest honest answer is the fast tier.
    return { fast: true, full: false, backend: true, frontend: true, reasons: ['no changed paths detected'], inertOnly: false };
  }

  const isInert = (path) => INERT.some((rule) => rule.test(path));
  if (paths.every(isInert)) {
    return { fast: false, full: false, backend: false, frontend: false, reasons: ['documentation/inert paths only'], inertOnly: true };
  }

  const risks = matchAll(paths, HIGH_RISK);
  const areas = new Set(matchAll(paths, AREAS).map((rule) => rule.area));

  // Anything outside backend/, frontend/, the inert list, and the high-risk table is unclassified.
  // Escalate rather than guess: a path we do not recognise is precisely the one whose impact we cannot
  // bound.
  //
  // The HIGH_RISK check matters for a path that lives outside both area trees — `docs/examples/*.json`
  // and the scene generator. Those are named explicitly, so reporting them as "unclassified" would be
  // false, and worse, it would bury the signal: if a recognised path always shows up in that list, a
  // genuinely new top-level directory stops standing out in it.
  const unclassified = paths.filter(
    (path) => !isInert(path)
      && !AREAS.some((rule) => rule.pattern.test(path))
      && !HIGH_RISK.some((rule) => rule.pattern.test(path)),
  );

  const reasons = risks.map((rule) => rule.why);
  if (unclassified.length > 0) {
    reasons.push(`unclassified path(s): ${unclassified.slice(0, 3).join(', ')}`);
  }

  const full = risks.length > 0 || unclassified.length > 0;
  return {
    fast: !full,
    full,
    // Full CI owns both suites and includes smoke; Fast must not repeat them.
    backend: !full && areas.has('backend'),
    frontend: !full && areas.has('frontend'),
    reasons: reasons.length > 0 ? [...new Set(reasons)] : ['low-risk source change'],
    inertOnly: false,
  };
}

/** True when this module was executed directly rather than imported by the test file. */
const runAsScript = () => {
  const entry = process.argv[1];
  if (!entry) return false;
  return import.meta.url.endsWith(entry.replace(/\\/g, '/').replace(/^[A-Za-z]:/, ''));
};

// CLI: emits `key=value` lines suitable for appending to $GITHUB_OUTPUT.
if (runAsScript()) {
  // Paths arrive via CHANGED_PATHS (newline separated) so the workflow passes a computed diff without
  // shell quoting hazards. stdin stays supported for ad-hoc local use.
  const fromEnv = process.env.CHANGED_PATHS;
  const raw =
    fromEnv !== undefined
      ? fromEnv
      : await new Promise((resolve) => {
          let data = '';
          process.stdin.setEncoding('utf8');
          process.stdin.on('data', (chunk) => (data += chunk));
          process.stdin.on('end', () => resolve(data));
        });

  const decision = route(raw.split('\n'), {
    // A reusable workflow reports the caller's ref, so an explicit *_INPUT override wins when a
    // workflow passes one deliberately.
    ref: process.env.GITHUB_REF_INPUT || process.env.GITHUB_REF || '',
    eventName: process.env.GITHUB_EVENT_INPUT || process.env.GITHUB_EVENT_NAME || 'push',
    forceFull: process.env.FORCE_FULL === 'true',
  });

  const lines = Object.entries(decision)
    .map(([key, value]) => `${key}=${Array.isArray(value) ? value.join('; ') : value}`)
    .join('\n');
  process.stdout.write(`${lines}\n`);
  process.stderr.write(`CI routing: ${JSON.stringify(decision, null, 2)}\n`);
}

