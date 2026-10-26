import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Generate and Stop must have consistent roles across the three recommendation sections.
const BOARD = readFileSync(join(__dirname, '../../views/Board.vue'), 'utf8')

// Split quoted class strings; CSS utilities themselves contain colons.
const ternaryBranches = (line: string): { running: string, idle: string } => {
  const quoted = line.match(/'[^']*'/g) ?? []
  expect(quoted.length, `expected two class strings, found ${quoted.length}: ${line.trim()}`).toBe(2)
  return { running: quoted[0] ?? '', idle: quoted[1] ?? '' }
}

const PANELS = [
  'generate-rule-recommendations',
  'generate-device-recommendations',
  'generate-spec-recommendations'
] as const

/** The class expression on a panel's Generate button, found from its test id. */
const generateButtonClasses = (panel: string): string => {
  const at = BOARD.indexOf(`data-testid="${panel}"`)
  expect(at, `the ${panel} panel should have a generate button`).toBeGreaterThan(-1)
  // Disabled and label expressions also use the running flag; inspect only the class attribute.
  const button = BOARD.slice(at, BOARD.indexOf('</button>', at))
  const classes = button.match(/:class="([^"]*)"/)?.[1]
  expect(classes, `the ${panel} button should have conditional classes`).toBeTruthy()
  const line = classes!.split(/\r?\n/).find(row => row.includes('isRecommending'))
  expect(line, `the ${panel} button should switch on its running flag`).toBeTruthy()
  return line!
}

describe('recommendation panels agree on their run affordance', () => {
  it('gives every disabled Generate button the shared disarmed treatment', () => {
    for (const panel of PANELS) {
      const at = BOARD.indexOf(`data-testid="${panel}"`)
      expect(at, panel).toBeGreaterThan(-1)
      const button = BOARD.slice(at, BOARD.indexOf('</button>', at))
      expect(button, panel).toContain('board-action-disarmed')
      expect(button, panel).not.toContain('disabled:opacity-')
    }
  })

  it('gives every Generate button a visible change between idle and running', () => {
    for (const panel of PANELS) {
      const line = generateButtonClasses(panel)
      const { running, idle } = ternaryBranches(line)
      const runningTokens = running.match(/var\(--[a-z-]+\)/g) ?? []
      const idleTokens = idle.match(/var\(--[a-z-]+\)/g) ?? []

      expect(runningTokens.length, `${panel}: running state should set a colour`).toBeGreaterThan(0)
      expect(idleTokens.length, `${panel}: idle state should set a colour`).toBeGreaterThan(0)
      expect(runningTokens[0], `${panel}: pressing Generate must change the button's colour`)
        .not.toBe(idleTokens[0])
    }
  })

  it('uses the same colour pair across all three panels', () => {
    // Three schemes had accumulated. One vocabulary, so "running" reads the same wherever the user meets it.
    const schemes = PANELS.map(panel => {
      const line = generateButtonClasses(panel)
      const { running, idle } = ternaryBranches(line)
      return `${(running.match(/var\(--[a-z-]+\)/) ?? [''])[0]} -> ${(idle.match(/var\(--[a-z-]+\)/) ?? [''])[0]}`
    })

    expect(new Set(schemes).size, `panels disagree: ${schemes.join(' | ')}`).toBe(1)
  })

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
