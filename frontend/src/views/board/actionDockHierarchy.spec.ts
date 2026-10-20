import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { parse } from '@vue/compiler-sfc'
import type { TemplateChildNode } from '@vue/compiler-dom'

// Toolbar groups preserve workflow meaning while their controls share one visual treatment.
describe('board action dock hierarchy', () => {
  const root = join(process.cwd(), 'src')
  const board = readFileSync(join(root, 'views/Board.vue'), 'utf8')
  const css = readFileSync(join(root, 'styles/board.css'), 'utf8')

  const SUGGESTION_TOOLS = [
    'open-scenario-recommendations',
    'open-rule-recommendations',
    'open-device-recommendations',
    'open-spec-recommendations'
  ]

  const RUN_TOOLS = [
    'open-simulation-panel',
    'open-verification-panel',
    'open-fuzzing-panel',
    'open-history-panel'
  ]

  it('retains functional hints while layout toggles have only their accessible names', () => {
    const ast = parse(board).descriptor.template?.ast
    expect(ast).toBeDefined()
    const tooltipParents = new Map<string, boolean>()
    const visit = (node: TemplateChildNode, inTooltip: boolean) => {
      if (node.type !== 1) return
      const wrapped = inTooltip || node.tag === 'HintTooltip'
      if (node.tag === 'button') {
        const testId = node.props.find(prop => prop.type === 6 && prop.name === 'data-testid')
        if (testId?.type === 6 && testId.value) tooltipParents.set(testId.value.content, wrapped)
      }
      node.children.forEach(child => visit(child, wrapped))
    }
    ast?.children.forEach(node => visit(node, false))
    for (const id of [...RUN_TOOLS, ...SUGGESTION_TOOLS]) expect(tooltipParents.get(id), id).toBe(true)
    for (const id of ['toggle-action-dock', 'restore-action-dock']) {
      expect(tooltipParents.get(id), id).toBe(false)
      expect(buttonMarkup(id)).toContain(':aria-label=')
      expect(buttonMarkup(id)).not.toContain('title=')
    }
  })

  /**
   * The complete opening tag of one dock button.
   *
   * Scans forward for the tag-closing `>` while ignoring any `>` inside an attribute value, since
   * these bindings contain expressions like `count > 0`. Attribute order varies between buttons, so
   * nothing here assumes the test id comes before the class.
   */
  const buttonMarkup = (testId: string): string => {
    // Search inside the template only. The script block references these same test ids in
    // focus-restoration selectors, and matching one of those would read the wrong region entirely.
    const templateAt = board.indexOf('<template>')
    expect(templateAt, 'Board.vue should have a template block').toBeGreaterThan(-1)
    const at = board.indexOf(`data-testid="${testId}"`, templateAt)
    expect(at, `${testId} should exist in the dock markup`).toBeGreaterThan(-1)
    const start = board.lastIndexOf('<button', at)
    expect(start, `${testId} should sit inside a <button>`).toBeGreaterThan(-1)
    let quote: string | null = null
    for (let i = start; i < board.length; i++) {
      const ch = board[i]
      if (quote) {
        if (ch === quote) quote = null
      } else if (ch === '"' || ch === "'") {
        quote = ch
      } else if (ch === '>') {
        return board.slice(start, i + 1)
      }
    }
    throw new Error(`unterminated <button> for ${testId}`)
  }

  it('gives all execution, history and suggestion entries the same neutral control', () => {
    for (const id of [...RUN_TOOLS, ...SUGGESTION_TOOLS]) {
      const markup = buttonMarkup(id)
      expect(markup, id).toContain('class="board-tool-button transition-colors"')
      expect(markup, id).not.toMatch(/board-tool-button--|\bbg-|\bshadow-/)
    }
  })

  it('marks open panels with a shared marker rather than an inset shadow', () => {
    const rule = (selector: string) => {
      const start = css.indexOf(selector + ' {')
      expect(start, selector).toBeGreaterThan(-1)
      return css.slice(start, css.indexOf('}', start))
    }
    expect(rule('.iot-board .board-tool-button')).toContain('box-shadow: none;')
    const selected = rule(".iot-board .board-tool-button[aria-pressed='true']")
    expect(selected).toContain('background-color: var(--accent-surface);')
    expect(selected).not.toContain('box-shadow')
    expect(rule(".iot-board .board-tool-button[aria-pressed='true']::before")).toContain('opacity: 1;')
  })

  it('keeps every suggestion tool a real focusable button with a name', () => {
    // This is a demotion in emphasis, not disclosure: the tools stay keyboard reachable and named.
    for (const id of SUGGESTION_TOOLS) {
      const markup = buttonMarkup(id)
      expect(markup, `${id} should be a <button>`).toContain('<button')
      expect(markup, `${id} needs an accessible name`).toContain('aria-label')
      expect(markup, `${id} should report its panel state`).toContain('aria-pressed')
    }
  })

  it('groups Run History apart from the actions that start a run', () => {
    // Two independent visual reviews raised the same point unprompted: Run History reads a stored
    // result rather than producing one, so grouping it under "Run" mislabels what it does.
    const template = board.slice(board.indexOf('<template>'))
    const runGroupAt = template.indexOf('data-testid="run-tool-group"')
    const reviewGroupAt = template.indexOf('data-testid="review-tool-group"')
    const historyAt = template.indexOf('data-testid="open-history-panel"')
    expect(reviewGroupAt, 'Run History needs its own group').toBeGreaterThan(-1)
    expect(historyAt, 'the history button should live after the review group opens')
      .toBeGreaterThan(reviewGroupAt)
    expect(reviewGroupAt, 'the review group should come after the run group')
      .toBeGreaterThan(runGroupAt)
  })

  it('uses a rail label short enough not to truncate', () => {
    // "Counterexample Search" rendered as "Counterex..." in a rail that allows about 70px. A
    // truncated *action name* is the one label a user cannot recover from surrounding context, so
    // the rail uses a short form while the full name stays on the panel and the accessible name.
    expect(buttonMarkup('open-fuzzing-panel'), 'the rail should not print the long name')
      .not.toContain("t('app.fuzzSearch')")
    expect(board, 'the rail needs a dedicated short label').toContain("t('app.fuzzSearchShort')")
  })

  it('gives recorded execution one home in Step changes for each replay kind', () => {
    expect(board).not.toContain('data-testid="trace-timeline-triggered-rules"')
    expect(board.match(/<template #changes>\s*<PlaybackChangesPanel/g)).toHaveLength(2)
    const changes = readFileSync(join(root, 'components/PlaybackChangesPanel.vue'), 'utf8')
    expect(changes.match(/data-testid="playback-change-automation"/g)).toHaveLength(1)
    expect(changes).not.toMatch(/<details(?:\s|>)/)
  })

  it('derives every dock mode decision from one list of available modes', () => {
    // Which modes a viewport allows was written four times in four shapes: two hardcoded cycle arrays
    // selected by `>= 1280`, a clamp restating the same threshold, a restore handler re-deriving the
    // same answer, and a `>= 1280` ternary inline in the launcher's `aria-label`. They agreed only by
    // coincidence, so a new width rule had to be remembered in three other places.
    expect(board).toContain('const availableActionDockModes = computed<ActionDockMode[]>')

    // The threshold appears once, in that list. The 720px fallback is a separate documented rule and
    // lives in `actionDockMode`, which is why it is excluded rather than counted here.
    const railWidthThresholds = board.match(/actionDockViewportWidth\.value\s*[<>]=?\s*1280/g) ?? []
    expect(railWidthThresholds, 'the 1280px rail threshold should have one owner').toHaveLength(1)

    // The launcher names the mode it restores instead of re-deriving it from a width.
    expect(board).toContain(':aria-label="actionDockRestoreLabel"')
    expect(board).not.toMatch(/:aria-label="actionDockViewportWidth >= 1280/)
  })

  it('names the set of workflow panels once', () => {
    // `showCanvasEmptyState` spelled out the five members and also re-listed the four recommendation flags
    // that `isAnyRecommendationPanelVisible()` owns, so a sixth panel meant remembering two lists. Only the
    // E2E suite covers the empty state, so a unit-level mutation of that predicate was silent; this is not.
    expect(board).toContain('const isWorkflowPanelOpen = computed(')
    const listings = board.match(/showVerificationPanel\.value \|\|\s*\n\s*showSimulationPanel\.value/g) ?? []
    expect(listings, 'the workflow-panel set should be enumerated once').toHaveLength(1)

    const at = board.indexOf('const showCanvasEmptyState = computed(')
    expect(at, 'showCanvasEmptyState should exist').toBeGreaterThan(-1)
    const body = board.slice(at, at + board.slice(at).indexOf('\n)'))
    expect(body, 'it should read the shared predicate').toContain('isWorkflowPanelOpen.value')
    expect(body, 'it should not re-list the recommendation panels').not.toContain('showDeviceRecommendationPanel')
  })

  it('keeps the canvas map and its viewport controls visible together', () => {
    /*
     * The map card had a `v-show` hiding it whenever any of eleven surfaces was open, which also removed the
     * zoom field, the zoom buttons and fit-to-content — the board's only pointer viewport controls.
     *
     * Narrowing that to hide only the map rectangle (and renaming the card's heading while it was hidden) was
     * treating a symptom. Measured, the collision it guarded against cannot occur: the floating panels carry a
     * `right` inset clearing the inspector and the action rail, so at 1440x900 a panel spans x=660..948
     * against an inspector at 1120..1440, and at 1100x800 it is 336..692 against 780..1100. The map lives
     * inside that inspector. At narrow widths the inspector is a 56px rail and the map is not rendered.
     */
    // Read directives from the element itself, independent of its tag or comparisons in bindings.
    const visibilityDirectives = new Map<string, string[]>()
    const targets = ['canvas-map', 'canvas-map-viewport']
    const ast = parse(board).descriptor.template?.ast
    expect(ast).toBeDefined()
    const visit = (node: TemplateChildNode) => {
      if (node.type !== 1) return
      const testId = node.props.find(prop => prop.type === 6 && prop.name === 'data-testid')
      if (testId?.type === 6 && testId.value && targets.includes(testId.value.content)) {
        visibilityDirectives.set(testId.value.content, node.props.flatMap(prop =>
          prop.type === 7 && (prop.name === 'show' || prop.name === 'if') ? [prop.name] : []))
      }
      node.children.forEach(visit)
    }
    ast?.children.forEach(visit)
    for (const testId of targets) {
      expect(visibilityDirectives.has(testId), `${testId} should exist`).toBe(true)
      expect(visibilityDirectives.get(testId), `${testId} should not be conditionally hidden`).toEqual([])
    }

    // One heading, and it must name the map unconditionally. Asserting the *absence* of the removed
    // `app.canvasView` key was vacuous — that key no longer exists in `assets/`, so nothing could reintroduce
    // the literal. What can regress is the heading becoming state-dependent again, so assert the shape instead.
    const headingAt = board.indexOf('canvas-map__title')
    expect(headingAt, 'the map heading should exist').toBeGreaterThan(-1)
    const heading = board.slice(headingAt, board.indexOf('</span>', headingAt))
    expect(heading, 'the map heading should not branch on state').not.toMatch(/\?[^:]*:/)
    expect(heading, 'the compact heading should name the map').toContain("t('app.canvasMapShort')")
  })

  it('keeps the dock heading a tier above the group labels it introduces', () => {
    // The heading was `0.72rem`/800 against `--iot-font-min` (11px)/700 group labels *beneath* it —
    // 0.52px of difference at a heavier weight, i.e. two heading levels rendering as one. It cleared
    // `typographyFloor` (11.52px over an 11px floor), which is why nothing flagged it; a floor is a
    // minimum for body text, not a target for a heading. Uppercase also cost apparent x-height and
    // word shape, which is most of why four characters read as smaller than they measured.
    const title = css.slice(css.indexOf('.iot-board .board-action-dock__title {'))
      .slice(0, css.slice(css.indexOf('.iot-board .board-action-dock__title {')).indexOf('}'))
    const titlePx = Number(/font-size:\s*([\d.]+)rem/.exec(title)?.[1]) * 16
    expect(titlePx, 'the dock heading should use the panel-title tier').toBeGreaterThanOrEqual(14)
    expect(title, 'the heading should not be uppercased').not.toMatch(/text-transform:\s*uppercase/)

    const groupLabel = css.slice(css.indexOf('.iot-board .board-tool-group-label {'))
    const groupPx = /font-size:\s*var\(--iot-font-min\)/.test(groupLabel.slice(0, groupLabel.indexOf('}')))
      ? 11
      : Number.NaN
    expect(groupPx, 'group labels should stay on the minimum tier').toBe(11)
    expect(titlePx - groupPx, 'the two tiers should be distinguishable').toBeGreaterThanOrEqual(2)
  })
})
