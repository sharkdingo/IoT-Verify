import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * Moving or resizing a node must not invalidate a displayed verification verdict.
 *
 * Canvas coordinates never reach the generated model, so a verdict computed before a drag is
 * still a verdict about the same model afterwards. The backend nevertheless reports
 * `operation: "updated"` for a layout write, because a layout row genuinely changed — so the
 * frontend cannot derive "semantic change" from that field. It did, and dragging a device to
 * point at it mid-presentation raised a "re-verify" banner over a perfectly valid result.
 *
 * `semanticCommit.spec.ts` covers the commit helper's own contract (`semanticChanged: false`
 * suppresses the staleness mark). What that cannot see is which value the layout call site
 * passes, which is where the defect lived. `Board.vue` has no unit-test seam, so this asserts
 * on its source text.
 *
 * There is deliberately no E2E case, and the reason is worth recording so nobody spends the
 * afternoon rediscovering it. Observing this behaviour in a browser needs an open verdict *and* a
 * pointer-reachable canvas, and those are mutually exclusive: the result dialog is a true
 * `aria-modal` surface whose overlay is `position: fixed; inset: 0` over the whole viewport
 * (`styles/dialog.css`). A raw `page.mouse` drag performs no hit-target check, so it lands on the
 * scrim and no layout request is ever sent — an attempted E2E case failed identically with the fix
 * present and reverted, i.e. it could not tell correct code from broken. Closing the dialog first
 * does not help either: `dismissResultDialog` clears `verificationResultStale`, so a reopened
 * verdict is a fresh read that cannot witness the commit.
 */
describe('node layout mutations and verification staleness', () => {
  const boardSource = readFileSync(
    resolve(__dirname, '..', 'Board.vue'),
    'utf8'
  )

  it('commits a layout mutation as a semantic no-op instead of trusting operation === updated', () => {
    // Assert the anchor before slicing. `indexOf` returns -1 when the call site is renamed, and
    // `slice(-1)` then yields the file's last character — which passes a `length > 0` guard and
    // fails the assertions below with a message about a one-character string, hiding the cause.
    const anchor = boardSource.indexOf('await boardApi.updateNodeLayout(')
    expect(anchor, 'the layout call site was not found in Board.vue').toBeGreaterThan(-1)
    const call = boardSource.slice(anchor)

    const commit = call.slice(0, call.indexOf('})') + 2)
    expect(commit).toContain('commitSemanticScene(')
    expect(commit).toContain('semanticChanged: false')
    expect(commit).not.toContain("operation === 'updated'")
  })

})
