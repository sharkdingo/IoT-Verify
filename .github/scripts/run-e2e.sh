#!/usr/bin/env bash
# Boot the packaged backend, wait for it to actually serve, and run one E2E tier against it.
#
# Both tiers share this so the readiness probe and the process lifecycle exist once. They had been
# duplicated inline in YAML, which is where a startup bug hides: a subtly different probe in one job
# reports "never became ready" for a backend that was fine.
#
# Usage: run-e2e.sh smoke|full|live-ai
set -euo pipefail

tier="${1:?usage: run-e2e.sh smoke|full|live-ai}"
log="${RUNNER_TEMP:-/tmp}/backend-e2e.log"

# Validate before starting anything: a typo in a workflow should fail in a second, not after booting
# a backend and waiting on a readiness probe.
case "$tier" in
  smoke|full|live-ai) ;;
  *) echo "::error::unknown tier '$tier' (expected smoke, full, or live-ai)"; exit 1 ;;
esac

jar="$(find backend/target -maxdepth 1 -name '*.jar' -type f ! -name '*sources*' ! -name '*javadoc*' | head -n 1)"
test -n "$jar" || { echo "::error::no backend jar found in backend/target"; exit 1; }
echo "Starting $jar"

java -jar "$jar" >"$log" 2>&1 &
backend_pid=$!
trap 'kill "$backend_pid" 2>/dev/null || true' EXIT

# A 401 proves the whole request path works: HTTP listener, dispatcher, security filter, and the
# rate guard. A 200 would need a seeded account; a health endpoint would prove less.
status=000
for _ in $(seq 1 60); do
  kill -0 "$backend_pid" 2>/dev/null || { echo "::error::backend exited during startup"; cat "$log"; exit 1; }
  status="$(curl -s -o "${RUNNER_TEMP:-/tmp}/readiness.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' \
    -d '{"identifier":"ci-readiness-user","password":"Pass1234!!"}' \
    http://127.0.0.1:8080/api/auth/login || true)"
  [ "$status" = 401 ] && break
  sleep 2
done
[ "$status" = 401 ] || { echo "::error::backend never became ready (last status $status)"; cat "$log"; exit 1; }
echo "Backend ready."

cd frontend

# Shared flags. --forbid-only keeps a stray test.only from silently shrinking a run to one test,
# which is the failure mode that makes a green tick meaningless.
common=(
  --project=chromium
  --forbid-only
  --trace=retain-on-failure-and-retries
  --reporter=line
)

case "$tier" in
  smoke)
    # Chosen for coverage per second, from measured per-spec cost on this repo:
    #   error-contract (0.9s)             - REST error envelope the whole UI depends on
    #   canvas-runtime-environment (8.0s) - board renders and shared values reach the canvas
    #   board-recovery (4.7s)             - the stack recovers rather than wedging
    #   ui-contracts (15.6s)              - 14 tests of cross-page contracts, cheapest broad net
    # Together ~30s of test time for a real barrier: the app boots, authenticates, renders a board,
    # and reports errors in the agreed shape. The two heaviest specs (authority-model-audit and
    # board-full-flow, 59% of the suite) are deliberately left to Full CI.
    #
    # --workers=2 is safe here because these specs create their own accounts and boards; the
    # sequencing constraint that forces --workers=1 in the full suite is its shared NuSMV budget.
    npx playwright test \
      e2e/error-contract.spec.ts \
      e2e/canvas-runtime-environment.spec.ts \
      e2e/board-recovery.spec.ts \
      e2e/ui-contracts.spec.ts \
      "${common[@]}" --workers=2 --retries=1 --global-timeout=420000
    ;;
  full)
    # Everything except the live-AI suite, which needs a real external endpoint and has its own gate.
    # --workers=1 and --fail-on-flaky-tests are the standing contract for the complete run: a flake
    # here is a defect report, not noise to be retried away.
    # The expanded suite must finish before the global deadline; per-test deadlines stay unchanged.
    npx playwright test \
      --grep-invert='live AI' \
      "${common[@]}" --workers=1 --retries=1 \
      --fail-on-flaky-tests --global-timeout=1800000 --reporter=line,html
    ;;
  live-ai)
    # Only the external-endpoint tests. No --fail-on-flaky-tests: a provider transport failure is not
    # a defect in this repo, and treating it as one would make the gate untrustworthy. Retries absorb
    # a transient outage; a reproducible failure still fails.
    npx playwright test \
      --grep='live AI' \
      "${common[@]}" --workers=1 --retries=2 --global-timeout=900000
    ;;
  *)
    echo "::error::unknown tier '$tier' (expected smoke, full, or live-ai)"; exit 1 ;;
esac
