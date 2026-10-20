import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import postcss from 'postcss'

/**
 * Arriving at the violating step must change the canvas, not only the rail.
 *
 * The rail marked the step with a ring and an 11px label from the start of playback, but during playback
 * the eye is on the canvas — and reaching the violating state changed nothing there: autoplay simply
 * called `stopTraceAnimation()` on the last state. The run's whole purpose arrived in silence.
 *
 * Measured in a browser on `acceptance-demo-scene.json`: step 0 → 0 emphasised nodes, step 1 →
 * 1 (`camera_1`, the device the violated specification binds), returning to step 0 → 0 again.
 */
describe('violation emphasis reaches the canvas', () => {
  const root = process.cwd()
  const board = readFileSync(join(root, 'src/views/Board.vue'), 'utf8')
  const canvas = readFileSync(join(root, 'src/components/CanvasBoard.vue'), 'utf8')
  const css = readFileSync(join(root, 'src/styles/board.css'), 'utf8')
  const specTypes = readFileSync(join(root, 'src/types/spec.ts'), 'utf8')

  it('passes the violating step and its subject devices to the canvas', () => {
    // Derived once at the binding rather than added to each writer of `highlightedTrace`: a writer that
    // forgot the field would be a violation the canvas silently failed to mark.
    expect(board, 'the canvas must receive the wrapped trace')
      .toContain(':highlighted-trace="canvasHighlightedTrace"')
    const at = board.indexOf('const canvasHighlightedTrace')
    expect(at, 'the wrapper should exist').toBeGreaterThan(-1)
    const body = board.slice(at, at + 2400)
    expect(body, 'it must carry the violating step').toContain('violationStateIndex')
    // Named `violatedSpec` fields, not a specific accessor: this asserted `violatedSpec?.boundDeviceIds`,
    // which `Specification` does not declare, so it pinned the defect in place. Which fields carry the
    // ids is the subject of its own test below.
    expect(body, 'and the devices the violated specification binds')
      .toMatch(/violationDeviceIds[\s\S]*violatedSpec\?\./)
  })

  it('scopes the emphasis to the step, so it clears when the viewer leaves', () => {
    const at = canvas.indexOf('const isAtViolationStep')
    expect(at).toBeGreaterThan(-1)
    const body = canvas.slice(at, at + 800)
    // Equality against the selected index is what makes it step state rather than a sticky mark.
    expect(body).toContain('trace.violationStateIndex === trace.selectedStateIndex')
    // A liveness violation occupies a whole cycle, so the set wins where it exists: testing only the
    // cycle's first state would drop the emphasis while the viewer is still inside the failing loop.
    expect(body, 'the step set must be honoured before the single index')
      .toContain('steps.includes(trace.selectedStateIndex)')
  })

  it('enumerates every step of a liveness cycle for the canvas', () => {
    // Templates 2/5/6 negate to EG/GF, so NuSMV refutes them with an infinite lasso path. Trace 83
    // (`scene_spec_4`, "the front door never re-locks while nobody is home") is the measured case: 5 states,
    // loop from index 3, and its final state repeats index 3 exactly. Template 5 was missing from the
    // last-state set entirely, so that run marked no step and emphasised no device.
    const at = board.indexOf('const counterexampleViolationSteps')
    expect(at, 'the step set should exist').toBeGreaterThan(-1)
    const body = board.slice(at, at + 900)
    expect(body, 'the cycle is enumerated from the loop range').toContain('counterexampleLoopRange')

    // Bounded to the range computed itself. Slicing from `counterexampleLoopRange` all the way to
    // `counterexampleViolationSteps` let the `LIVENESS_TEMPLATES` assertion below match text in the
    // *following* computed, so it passed while claiming something about this one that is not true of it:
    // locating the loop is template-blind on purpose, and `counterexampleViolationSteps` is what gates it.
    const rangeAt = board.indexOf('const counterexampleLoopRange')
    const range = board.slice(rangeAt, board.indexOf('})', board.indexOf('return { start, end }', rangeAt)))
    // Read from NuSMV's own marker rather than guessed from the template or the index.
    expect(range, 'the loop is located by the backend flag').toContain("state?.loopStart === true")
    // The template gate lives in the consumer, because the marker alone does not mean the cycle is the
    // violation — NuSMV prints it on safety counterexamples too (see the next test).
    expect(range, 'locating a loop must not itself claim the cycle is the fault')
      .not.toContain('LIVENESS_TEMPLATES')
    expect(body, 'only a liveness template may claim its cycle').toContain('LIVENESS_TEMPLATES')
  })

  it('does not treat a loop on a safety counterexample as the violation', () => {
    // Measured on NuSMV 2.7.1: `-- Loop starts here` appears on SAFETY counterexamples too — a CTL
    // `AG(motion -> AX light)` (template 4) trace and an LTL `G(p)` trace both carry one, and the LTL trace
    // carried the line TWICE. Keying the cycle off the marker alone therefore marks a cycle for a safety
    // violation, whose fault is a single state.
    const liveness = board.slice(board.indexOf('const LIVENESS_TEMPLATES'), board.indexOf('const LIVENESS_TEMPLATES') + 200)
    expect(liveness, 'exactly the liveness templates').toMatch(/'2'.*'5'.*'6'/)

    const at = board.indexOf('const counterexampleViolationSteps')
    const body = board.slice(at, at + 1100)
    // A liveness template with no marker must claim nothing rather than falling through to the last state,
    // whose values are a loop re-print.
    expect(body, 'liveness is handled on its own branch').toContain('LIVENESS_TEMPLATES.has(String(templateId))')

    // Two markers in one trace: the backend computes loopBack against the LAST one, so the frontend must
    // agree or it would over-mark by a state.
    const range = board.slice(board.indexOf('const counterexampleLoopRange'), at)
    expect(range, 'the last marker wins, matching the backend').toContain('start = index')

    // A liveness template must never fall through to the last-state rule, which would name a single
    // step for a fault that has none. Assert the set literal itself, not a source window: the surrounding
    // prose legitimately names 2/5/6 when explaining why they are excluded.
    const declaration = board.slice(board.indexOf('const LAST_STATE_VIOLATION_TEMPLATES'))
    const literal = declaration.slice(0, declaration.indexOf(')') + 1)
    expect(literal, 'the safety templates keyed by last state').toContain("'1', '3', '4', '7'")
    expect(literal, 'liveness templates are excluded from the last-state rule').not.toMatch(/'2'|'5'|'6'/)
  })

  it('marks the violating state of an immediate-response counterexample too', () => {
    // Template 4 was excluded from the last-state set on the reasoning that its witness ends where the
    // trigger holds, with the violation in an unshown successor. That describes the NEGATED form
    // `EF(a & EX(!b))` — and verification never emits it: `SmvGenerator.buildSmvContent` passes a null
    // `ParameterizationConfig`, which is the only branch that forks to the positive `specBuilder.build`,
    // while `buildNegated` is reached solely by the fix strategies, which read it as a satisfiability bit.
    // For a violating model that negated formula is `true`, so NuSMV prints no trace for it at all.
    //
    // Measured on NuSMV 2.7.1 across 21 falsifying models of the positive `AG(if -> AX(then))`: the trigger
    // is at index n, the violating successor at n+1, and n+1 is always the last printed state. Two other
    // parts of the platform already say the same: `FuzzModel.evaluate` returns `step + 1`, and
    // `docs/architecture/fuzzing-flow.md` states "State `n+1` where `IF` held at `n`". Excluding it left the
    // most common template in the shipped scenes (13 of 42 specs) replaying with nothing marked.
    const declaration = board.slice(board.indexOf('const LAST_STATE_VIOLATION_TEMPLATES'))
    const literal = declaration.slice(0, declaration.indexOf(')') + 1)
    expect(literal, 'immediate response is a last-state violation').toContain("'4'")
    // And it must not also be treated as liveness, which would mark a cycle instead of the state.
    const liveness = board.slice(board.indexOf('const LIVENESS_TEMPLATES'))
    expect(liveness.slice(0, liveness.indexOf(')') + 1), 'template 4 is not liveness').not.toContain("'4'")

    // A template-4 violation needs a trigger AND the successor that fails to respond, so a one-state trace
    // claiming it is inconsistent evidence. Marking its only state would name the trigger as the fault —
    // exactly the error the old exclusion was guarding against. Templates 1/3/7 have no such floor: an
    // initial state can break `AG(p)` on its own.
    const step = board.slice(board.indexOf('const counterexampleViolationStep'))
    const safety = step.slice(0, step.indexOf('\n})'))
    expect(safety, 'template 4 requires two states').toMatch(/templateId\) === '4' \? 2 : 1/)
    expect(safety, 'and the length is compared against that floor').toMatch(/count >= floor/)

    // The canvas set and the rail marker must not each carry their own copy of the last-state rule: they
    // did, and this floor would have had to be added to both to keep them agreeing.
    const steps = board.slice(board.indexOf('const counterexampleViolationSteps'))
    const safetySteps = steps.slice(0, steps.indexOf('\n})'))
    expect(safetySteps, 'the set reads the single owner').toContain('counterexampleViolationStep.value')
    expect(safetySteps, 'and does not recompute the last index itself').not.toMatch(/count\s*-\s*1/)
  })

  it('does not carry a counterexample violation into a simulation replay', () => {
    // `savedTraces` has three writers and no reset, and `currentTrace` prefers it unconditionally — so a
    // counterexample opened earlier stays selected while a simulation replays through the same
    // `highlightedTrace`, and the canvas outlined that counterexample's devices on a run that violated
    // nothing. The emphasis is gated on the active playback kind instead.
    const at = board.indexOf('const canvasHighlightedTrace')
    const body = board.slice(at, at + 2400)
    expect(body, 'the gate must read the playback kind').toContain("activePlaybackKind.value === 'counterexample'")
    expect(body, 'a fuzz finding is also a violation replay').toContain("activePlaybackKind.value === 'fuzzing'")
    // All three violation fields must be gated, not just the step: the device ids alone would still paint.
    expect(body).toMatch(/violationStateIndex:\s*isViolationPlayback/)
    expect(body).toMatch(/violationStateIndexes:\s*isViolationPlayback/)
    expect(body).toMatch(/violationDeviceIds\s*=\s*isViolationPlayback/)
  })

  it('gates the playback panel badge on the same playback kind as the canvas', () => {
    // The popover is shared by all three playback kinds, and the badge previously relied on its own
    // `kind === 'fuzzing'` test — which gated it incidentally while also hiding it from every verification
    // counterexample. Making the badge kind-agnostic there moves the gate here, so the stale-`savedTraces`
    // leak above has to be re-stated for this surface or a simulation replay badges a state of a run that
    // violated nothing.
    const at = board.indexOf('const activeViolationStateNumber')
    expect(at, 'the badge source should exist').toBeGreaterThan(-1)
    const body = board.slice(at, at + 500)
    expect(body, 'the gate must read the playback kind').toContain("kind !== 'counterexample'")
    expect(body, 'a fuzz finding is also a violation replay').toContain("kind !== 'fuzzing'")
    // Reads the single owner rather than the finding, which is what makes a safety counterexample marked.
    expect(body, 'one owner for the violating step').toContain('counterexampleViolationStep.value')

    const popover = readFileSync(join(root, 'src/components/PlaybackChangesPanel.vue'), 'utf8')
    const predicate = popover.slice(popover.indexOf('const isViolationState'), popover.indexOf('const isViolationState') + 220)
    expect(predicate, 'the component must not re-gate on the kind, which excluded counterexamples')
      .not.toContain("props.kind === 'fuzzing'")
    expect(predicate, 'an absent state number must match nothing, not state undefined')
      .toContain('props.violationStateNumber !== undefined')
  })

  it('gives the rail button the same violation word its visible marker shows', () => {
    // The marker renders `traceViolationHere` while the accessible name said `fuzzFirstViolation`
    // unconditionally, so a screen-reader user heard "First violation" on a verification counterexample
    // and a sighted user read "Violation" — one state under two names, on the same button.
    // Both now come from one helper, which is what makes them impossible to diverge again: the visible
    // marker renders its return value and the accessible name appends the same string.
    const at = board.indexOf('const traceStateViolationLabel')
    expect(at, 'the label helper should exist').toBeGreaterThan(-1)
    const body = board.slice(at, at + 500)
    expect(body, 'exploration keeps its own wording').toContain("t('app.fuzzFirstViolation')")
    expect(body, 'and a counterexample gets the marker word').toContain("t('app.traceViolationHere')")

    const aria = board.slice(board.indexOf('const getTraceStateAriaLabel'), at + 2000)
    expect(aria, 'the accessible name must read the helper, not its own copy of the wording')
      .toMatch(/getTraceStateAriaLabel[\s\S]{0,600}traceStateViolationLabel\(index\)/)
    const terminal = readFileSync(join(root, 'src/components/PlaybackTerminal.vue'), 'utf8')
    expect(board).toContain(':violation-steps="counterexampleViolationSteps"')
    expect(board).toContain(':violation-label="traceStateViolationLabel"')
    expect(terminal).toContain(':aria-label="markerLabel(index)"')
    const markerLabel = terminal.slice(terminal.indexOf('const markerLabel'), terminal.indexOf('const rail ='))
    expect(markerLabel).toContain('props.stateLabel(index)')
    expect(markerLabel).toContain("t('app.playbackInitialState')")
    expect(terminal).toContain(':data-violation="violationSteps.includes(index)"')
    expect(terminal).toContain('{{ index + 1 }}')

  })

  it('keeps violation words outside the small rail markers', () => {
    const terminal = readFileSync(join(root, 'src/components/PlaybackTerminal.vue'), 'utf8')
    expect(terminal).not.toContain('board-playback-violation')
    expect(board).toContain('data-testid="trace-timeline-violation-chip"')
    expect(board).toContain('{{ traceStateViolationLabel(traceAnimationState.selectedStateIndex) }}')
  })

  it('marks a liveness cycle on the rail, not only on the canvas', () => {
    // The rail tested `counterexampleViolationStep`, which is `undefined` for templates 2/5/6 by design —
    // those are refuted by an infinite lasso path, so no single step is the fault. The canvas emphasised
    // every device in the cycle and the popover explained the loop, while the rail showed only the cursor
    // star: the same silence template 4 had, in the branch that fix did not reach.
    //
    // Measured on NuSMV 2.7.1 with the generator's own template-5 shape (`SmvSpecificationBuilder`'s
    // positive form, `AG(IF -> AF(THEN))`) over a non-responding model: a 6-state counterexample whose
    // cycle is states 5-6.
    // Two steps to mark, none marked. Five of the 42 specs in the shipped scenes are template 5.
    const at = board.indexOf('const traceStateViolationLabel')
    const body = board.slice(at, at + 500)
    // Reading the *set* is the fix: it is what already carries the cycle, and what the canvas reads.
    expect(body, 'the cycle comes from the same set the canvas emphasis reads')
      .toContain('counterexampleViolationSteps.value.includes(index)')
    expect(body, 'and gets its own word rather than repeating the single-step one')
      .toContain("t('app.traceViolationCycle')")
    // Both locales, or the cycle renders as a raw key on one of them.
    const i18n = readFileSync(join(root, 'src/assets/i18n.ts'), 'utf8')
    expect(i18n.match(/traceViolationCycle:/g)?.length, 'zh-CN and en both define it').toBe(2)
  })

  it('shows the playback header chip for a counterexample, not only an exploration finding', () => {
    // The chip hand-rolled its own predicate against `activeFuzzingFinding.firstViolationStep`, so it
    // appeared for a fuzz finding and never for a verification counterexample — while the rail directly
    // beneath it marked that very step. Two surfaces in the same header, one step apart, disagreeing about
    // whether the step the user is standing on is the violation.
    //
    // The helper is a strict superset of the old test: it returns the fuzz wording when a finding is
    // active, the safety word otherwise, and the cycle word inside a liveness loop.
    expect(board, 'the chip reads the one owner of "does this step carry a violation word"')
      .toMatch(/v-if="traceStateViolationLabel\(traceAnimationState\.selectedStateIndex\)"/)
    expect(board, 'and prints what that helper returns rather than a hardcoded fuzz string')
      .toMatch(/\{\{ traceStateViolationLabel\(traceAnimationState\.selectedStateIndex\) \}\}/)
    // The finding-only predicate must be gone, not merely supplemented: leaving it would keep two owners.
    expect(board, 'no second, finding-only violation predicate remains in the header')
      .not.toContain('traceAnimationState.selectedStateIndex === activeFuzzingFinding.firstViolationStep')
    // The old testid named exploration only; the chip is now kind-agnostic, so the id must be too. A
    // stale selector elsewhere would address a control that no longer exists.
    expect(board, 'the testid no longer claims the chip is exploration-only')
      .not.toContain('data-testid="fuzzing-timeline-first-violation"')
    expect(board).toContain('data-testid="trace-timeline-violation-chip"')
  })

  it('says why the loop-closing step shows no change, rather than reporting an empty diff', () => {
    // On a one-state cycle NuSMV re-prints the loop entry with no variable lines, so the delta merge makes
    // the final state identical to its predecessor: "No observable changes" is then true but reads as a
    // broken animation. (A longer cycle prints real deltas, so the block must be ordered ahead of the
    // change list's own empty state rather than replacing it — see PlaybackChangesPanel.spec.ts.)
    const popover = readFileSync(join(root, 'src/components/PlaybackChangesPanel.vue'), 'utf8')
    const loopAt = popover.indexOf('data-testid="playback-change-loop-back"')
    const emptyAt = popover.indexOf('data-testid="playback-change-empty"')
    expect(loopAt, 'the loop explanation must exist').toBeGreaterThan(-1)
    expect(loopAt, 'and be tested before the generic empty state').toBeLessThan(emptyAt)
    expect(board, 'the board must tell the popover which step closes the loop')
      .toContain(':is-loop-back-state="activePlaybackIsLoopBackState"')
  })

  it('scopes the emphasis to the specification subject, with a non-silent fallback', () => {
    const at = canvas.indexOf('const isNodeAtViolation')
    expect(at).toBeGreaterThan(-1)
    const body = canvas.slice(at, at + 300)
    expect(body, 'bound devices win when the specification names any').toContain('scoped.has(node.id)')
    expect(body, 'and a specification binding none must still mark something')
      .toContain('isNodeInTrace(node)')
    expect(canvas, 'the class must be applied to the node').toContain("'device-at-violation': isNodeAtViolation(node)")
  })

  it('reads the subject devices off fields the specification type actually has', () => {
    // The scoping above is only real if the id list is non-empty for a specification that names devices.
    // It used to read `violatedSpec.boundDeviceIds` — a field `Specification` does not declare — so the
    // list was permanently `[]`, the fallback ran every time, and the canvas emphasised every device in
    // the state. The shape assertion in the gating test above passed throughout, because the defect was
    // the field name rather than the expression.
    expect(specTypes, 'the fabricated field must not appear on the type')
      .not.toMatch(/boundDeviceIds/)

    const at = board.indexOf('const canvasHighlightedTrace')
    const body = board.slice(at, at + 2400)
    // The bare identifier, not an access shape: `(spec as any)?.boundDeviceIds` reads the same absent
    // field while matching no accessor pattern, and a cast is exactly how this defect returns.
    const code = body.split('\n').filter(line => !line.trim().startsWith('//')).join('\n')
    expect(code, 'and must not be read by the derivation, cast or not')
      .not.toContain('boundDeviceIds')
    // `devices` is the accumulated reference list; the conditions are the authority behind it. Asserted
    // separately so the derivation can be reordered without a false failure.
    expect(body, 'the accumulated device references').toMatch(/violatedSpec\?\.devices/)
    for (const side of ['aConditions', 'ifConditions', 'thenConditions']) {
      expect(body, `${side} carry deviceId and must be read`).toContain(`violatedSpec?.${side}`)
    }
    expect(body, 'and the ids must be de-duplicated across the two sources').toMatch(/new Set\(/)
  })

  it('keeps the ring under reduced motion while dropping the arrival flash', () => {
    // Reduced motion may remove animation, never information: the ring states "this is the violating
    // step", so only the flash is suppressed.
    expect(css).toContain('.iot-board .device-node.device-at-violation')
    expect(css, 'the persistent statement is an outline, not an animation')
      .toMatch(/\.device-node\.device-at-violation\s*\{[^}]*outline:\s*3px solid var\(--danger-fill\)/)
    let flashSuppressed = false
    postcss.parse(css).walkAtRules('media', media => {
      if (!media.params.includes('prefers-reduced-motion: reduce')) return
      media.walkRules(rule => {
        const selectors = rule.selectors.map(selector => selector.replace(/\/\*[\s\S]*?\*\//g, '').trim())
        if (!selectors.includes('.iot-board .device-node.device-at-violation')) return
        rule.walkDecls('animation', declaration => {
          if (declaration.value === 'none' && declaration.important) flashSuppressed = true
        })
      })
    })
    expect(flashSuppressed, 'the flash must be suppressed throughout the reduced-motion block').toBe(true)
  })

  it('uses the non-text indicator token rather than a literal red', () => {
    // `--danger-fill` is measured at 4.41:1 light / 3.70:1 dark, against the 3:1 WCAG 1.4.11 asks of a
    // non-text indicator; a literal would not be theme-aware.
    const at = css.indexOf('.iot-board .device-node.device-at-violation')
    const block = css.slice(at, at + 1200)
    expect(block).toContain('var(--danger-fill)')
    expect(block, 'no literal hex red').not.toMatch(/#(EF4444|ef4444|F87171|f87171)/)
  })
})
