import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * The solver's raw output must reach a user somewhere.
 *
 * `nusmvOutput` is captured by `NusmvExecutor`, persisted on the run, mapped through every verification
 * DTO, and *required* by the client contract validator (`runResponse.ts` calls `requireString` for it) —
 * carried the entire way and, for a while, rendered nowhere. A dialog-consolidation pass deleted the
 * disclosure that showed it and a later dead-key sweep deleted its now-unreferenced label, so the two
 * changes together removed the only channel by which a NuSMV message can reach a user, and each looked
 * locally correct.
 *
 * Why that channel matters, measured rather than assumed: NuSMV 2.7.1 on a hand-built model whose
 * fair-states set is empty prints
 *
 *     ********   WARNING   ********
 *     Fair states set of the finite state machine is empty.
 *     This might make results of model checking not trustable.
 *
 * and then answers *both* `AG (step != 2)` and `F (step = 2)` as `true` — mutually contradictory. Nothing
 * in this repo parses that warning (grep for "not trustable" / "Fair states" returns nothing), and the
 * parser models only `-- specification ... is true/false`. Today's generator emits total transition
 * relations — every `case` carries a `TRUE:` default, and there are no `TRANS`/`FAIRNESS` sections — so
 * the state is not reachable through the product right now. This disclosure is what makes it visible if
 * that ever changes, and it is the only channel for any other solver diagnostic.
 */
describe('solver output visibility', () => {
  const board = readFileSync(join(process.cwd(), 'src/views/Board.vue'), 'utf8')
  const i18n = readFileSync(join(process.cwd(), 'src/assets/i18n.ts'), 'utf8')
  const runResponse = readFileSync(join(process.cwd(), 'src/utils/runResponse.ts'), 'utf8')

  it('renders the verification run\'s raw NuSMV output', () => {
    expect(board, 'the disclosure must exist')
      .toContain('data-testid="verification-nusmv-output"')
    const at = board.indexOf('data-testid="verification-nusmv-output"')
    const block = board.slice(at, board.indexOf('</details>', at))
    expect(block, 'and actually interpolate the field, not just label a section')
      .toContain('verificationResult.nusmvOutput')
    expect(i18n, 'its label must exist in the bundle')
      .toContain('showNusmvDiagnosticOutput')
  })

  it('keeps the field a required part of the client contract', () => {
    // If the validator ever stops requiring it, a backend that quietly stopped sending it would pass
    // validation and the disclosure would render an empty console — which reads as "the solver said
    // nothing" rather than "the field is missing".
    expect(runResponse, 'the response contract requires the solver output')
      .toMatch(/requireString\([^)]*'nusmvOutput'/)
  })

})
