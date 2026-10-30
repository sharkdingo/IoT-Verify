// @vitest-environment node
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Model-authored explanations and adjusted values must stay visible in every recommendation panel.
const BOARD = readFileSync(join(__dirname, '../../views/Board.vue'), 'utf8')

describe('recommendation panel evidence', () => {
  it('routes every model-authored explanation through the locale fallback', () => {
    /*
     * The rule card printed `rec.reason` raw while the other three wrapped theirs.
     *
     * A model-authored `reason`/`rationale` is not contractually in the UI locale, so `localizedRecommendationText`
     * swaps in a translated line when it does not match. The rule card skipped that call, and once its provenance
     * line became a `v-else` shown only when `reason` was absent, a Chinese-locale user reading an English reason
     * had no localized text left in the block at all. Four hand-maintained copies, three of them right.
     */
    const explanations = [
      { panel: 'rule-recommendation-panel', field: 'rec.reason' },
      { panel: 'device-recommendation-panel', field: 'rec.reason' },
      { panel: 'spec-recommendation-panel', field: 'rec.rationale' }
    ] as const

    for (const { panel, field } of explanations) {
      const at = BOARD.indexOf(`data-testid="${panel}"`)
      expect(at, `the ${panel} panel should exist`).toBeGreaterThan(-1)
      const window = BOARD.slice(at, BOARD.indexOf('data-testid="', at + 1) + 24_000)
      const rendered = window.split(/\r?\n/).filter(row => row.includes('{{') && row.includes(field))
      expect(rendered.length, `${panel}: expected a line rendering ${field}`).toBeGreaterThan(0)
      for (const row of rendered) {
        expect(row, `${panel}: ${field} must go through localizedRecommendationText, not be printed raw`)
          .toContain('localizedRecommendationText')
      }
    }
  })

  it('lets every panel surface the values the server completed for the user', () => {
    /*
     * All four panels must show their adjusted items, and the specification panel did not.
     *
     * It never read `adjustedItems`, so a server-completed value arrived, passed both validators, and vanished.
     * That is the panel where it matters most: `BoardStorageController:535` passes `requireAdjustments=false` for
     * specifications alone — rule and device pass `true` — so this is precisely the case where the recommender may
     * adjust a candidate silently. The user would then apply a value the system filled in, without the "review
     * before applying" notice the other three panels show.
     */
    for (const panel of ['rule', 'device', 'spec']) {
      const at = BOARD.indexOf(`data-testid="${panel}-adjusted-items"`)
      expect(at, `the ${panel} panel should surface its adjusted items`).toBeGreaterThan(-1)
    }
  })
})
