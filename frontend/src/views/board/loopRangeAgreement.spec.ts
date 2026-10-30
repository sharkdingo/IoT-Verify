import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The cycle the replay bar names must be the cycle the backend marked.
 *
 * NuSMV closes a lasso counterexample by re-printing its loop-entry state, and it can print
 * `-- Loop starts here` **more than once** in a single counterexample. The backend resolves that
 * deliberately and documents the choice: `SmvTraceParser` overwrites `loopStartState` on every marker, so
 * the state it pairs with `loopBack` is the one the **last** marker points at —
 * `SmvTraceParserTest.parseCounterexample_usesTheLastMarkerWhenNuSmvPrintsSeveral` asserts a five-state
 * trace where states 3 and 4 both carry `loopStart` and only state 5 carries `loopBack`.
 *
 * The frontend then has to agree, because it renders a *sentence about formal evidence*:
 * "State {end} loops back to state {start}". Reading the first marker instead of the last names a cycle
 * the counterexample does not contain — a wrong claim about a proof, in the one place a reader goes to
 * understand why the final step shows no movement.
 *
 * There are **two** such computeds and both implement the rule independently:
 * `activePlaybackLoopRange` is 1-based and feeds that sentence, while `counterexampleLoopRange` is
 * 0-based and feeds `counterexampleViolationSteps` — canvas emphasis and the rail's cycle rings. The
 * differing index base is deliberate (display numbers vs array positions), the resolution rule is not
 * free to differ, and only the first one was guarded here: a regression to `findIndex` in the other
 * would have emphasised a device on a state outside the cycle with nothing going red.
 *
 * Inspect the owning computeds without mounting all of `Board.vue`.
 */
describe('loop range agreement between parser and playback', () => {
  const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')

  /** A computed's body by name, comment lines dropped so prose cannot satisfy a code assertion. */
  const bodyOf = (name: string) => {
    const at = board.indexOf(`const ${name}`)
    expect(at, `${name} should exist`).toBeGreaterThan(-1)
    return board
      .slice(at, board.indexOf('\n})', at))
      .split('\n')
      .filter(line => !line.trim().startsWith('*') && !line.trim().startsWith('//'))
      .join('\n')
  }

  /** The `activePlaybackLoopRange` body, which owns the sentence the panel renders. */
  const rangeBody = bodyOf('activePlaybackLoopRange')

  it.each([
    ['activePlaybackLoopRange', 'states'],
    ['counterexampleLoopRange', 'trace\\.states']
  ])('resolves the loop entry from the last marker in %s, as the parser does', (name, collection) => {
    // `findIndex` takes the first match and is the defect; `findLastIndex` (or an equivalent reverse
    // scan) is what agrees with the parser. Asserting the absence of the first-match form is what makes
    // this catch a regression rather than restate the current code.
    const code = bodyOf(name)
    expect(code, 'the loop entry must be the last marked state, not the first')
      .not.toMatch(/findIndex\(\s*\w+\s*=>\s*\w+\??\.loopStart/)
    // A reverse scan, because `findLastIndex` needs `lib: ES2023` and this app targets ES2020.
    expect(code, 'resolved by scanning from the end')
      .toMatch(new RegExp(`for \\(let (\\w+) = ${collection}\\.length - 1; \\1 >= 0; \\1--\\)`))
    expect(code, 'and it must stop at the first hit from the end').toContain('break')
    // The closing state is set on at most one state, so first-match is correct for it — pinned so a
    // well-meant symmetry edit cannot make both lookups scan backwards and mis-name a two-marker trace.
    expect(code, 'the closing state is located forwards').toMatch(/findIndex\(/)
  })

  it('reports no cycle rather than a partial one when either flag is missing', () => {
    // A finite safety counterexample carries neither flag, and the parser refuses to invent them
    // (`parseCounterexample_leavesLoopFlagsAbsentForAFinitePath`). Both lookups must therefore be able
    // to fail independently, or a trace with one flag would render half a sentence.
    expect(rangeBody, 'a missing loop entry yields no range').toMatch(/loopStartIndex === -1/)
    expect(rangeBody, 'a missing closing state yields no range').toMatch(/loopBackIndex === -1/)
  })
})
