import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import postcss from 'postcss'

const currentDirectory = dirname(fileURLToPath(import.meta.url))
const boardSource = readFileSync(resolve(currentDirectory, 'Board.vue'), 'utf8')
const boardCss = readFileSync(resolve(currentDirectory, '../styles/board.css'), 'utf8')

describe('Board surface accessibility contracts', () => {
  it('keeps the automatic-fix request owner mounted while its dialog is hidden', () => {
    const fixDialogStart = boardSource.indexOf('<FixResultDialog')
    const fixDialog = boardSource.slice(fixDialogStart, boardSource.indexOf('/>', fixDialogStart) + 2)

    expect(fixDialogStart).toBeGreaterThan(-1)
    expect(fixDialog).toContain('ref="fixResultDialogRef"')
    expect(fixDialog).toContain(':visible="showFixDialog"')
    expect(fixDialog).not.toContain('v-if=')

    const openFixStart = boardSource.indexOf('const openFixDialog =')
    const openFixDialog = boardSource.slice(
      openFixStart,
      boardSource.indexOf('const canFixVerificationResultTrace', openFixStart)
    )
    expect(openFixDialog).toContain('canOpenTrace?.(traceId) === false')
    expect(openFixDialog).toContain('showFixDialog.value = true')
    expect(openFixDialog.indexOf('canOpenTrace?.(traceId) === false'))
      .toBeLessThan(openFixDialog.indexOf('fixTraceId.value = traceId'))
  })

  it('keeps the template instance dialog focus-managed and the delete footer reachable', () => {
    const templateDialog = boardSource.slice(
      boardSource.indexOf('v-if="templateInstanceDialogVisible"'),
      boardSource.indexOf('<!-- Left Sidebar - Control Center -->')
    )
    expect(templateDialog).toContain('@keydown="handleTemplateInstanceDialogKeydown"')
    expect(templateDialog).toContain(':ref="setTemplateInstanceDialogRef"')
    expect(templateDialog).toContain('tabindex="-1"')
    // Height bounding is now `.iot-dialog`'s (`max-height: calc(100dvh - 2rem)` on a flex column whose
    // `__footer` is `flex: none`), so what this asserts is that the dialog is composed from that layer rather
    // than re-deriving the cap. Same guarantee, one owner.
    expect(templateDialog).toContain('iot-dialog--md')
    expect(templateDialog).toContain('iot-dialog__footer')

    const deleteDialog = boardSource.slice(
      boardSource.indexOf('<!-- Custom Delete Confirmation Dialog -->'),
      boardSource.indexOf('<FuzzingResultDialog')
    )
    expect(deleteDialog).toContain('iot-dialog--md')
    // The shared primitive owns overflow, overscroll containment, the scrollbar skin, and the
    // scroll-padding that keeps a programmatically revealed control clear of the boundary. Asserting
    // `overflow-y-auto overscroll-contain` pinned two of those four and let the other two drift —
    // which is how 31 regions ended up with a scrollbar that did not match the product and no
    // scroll-padding for keyboard users.
    expect(deleteDialog).toContain('iot-scroll-region')
    // `min-h-0 flex-1` on the body and `shrink-0` on the footer are now `.iot-dialog__body` / `__footer`
    // in the shared sheet: the same two properties, declared once for every dialog instead of per surface.
    expect(deleteDialog).toContain('iot-dialog__body')
    expect(deleteDialog).toContain('iot-dialog__footer')
  })

  it('names the concrete template value behind blank initial-value choices', () => {
    const placeholder = boardSource.slice(
      boardSource.indexOf('const templateVariableInputPlaceholder ='),
      boardSource.indexOf('const buildTemplateInstanceRuntimeConfig')
    )
    expect(placeholder).toContain('getTemplateVariableDefaultValue(variable)')
    expect(placeholder).toContain("t('app.useTemplateDefaultWithValue', { value: defaultValue })")

    const templateDialog = boardSource.slice(
      boardSource.indexOf('v-if="templateInstanceDialogVisible"'),
      boardSource.indexOf('<!-- Left Sidebar - Control Center -->')
    )
    // The hint now names the value the CHOSEN STATE declares, not the first enum literal: for a variable
    // every state constrains those differ, and showing `Values[0]` advertised a value the writers reject.
    expect(templateDialog).toContain(
      'getTemplateVariableDefaultValue(variable, templateInstanceDialogData.template, templateInstanceRuntime.state)'
    )
  })

  it('isolates narrow-screen background controls and respects reduced motion', () => {
    const narrowBackground = boardSource.slice(
      boardSource.indexOf('data-testid="board-narrow-background"') - 100,
      boardSource.indexOf('data-testid="board-narrow-background"') + 300
    )
    expect(narrowBackground).toContain(':inert="showNarrowPanelScrim ? true : undefined"')
    expect(narrowBackground).toContain(':aria-hidden="showNarrowPanelScrim ? \'true\' : undefined"')

    expect(boardCss).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*\.board-side-panel[\s\S]*\.animate-ping/)

    // The verdict decision table maps each outcome to a role, never to a hue ramp. The window starts at the
    // tone helper because the role class names are now built there from one tone name per outcome, rather than
    // spelled out six times per branch.
    const verdictTable = boardSource.slice(
      boardSource.indexOf('const verificationVerdictTone ='),
      boardSource.indexOf('const verificationModelSemanticsConsistent = computed')
    )
    expect(verdictTable).toMatch(/board-surface-\$\{tone\}/)
    expect(verdictTable).toMatch(/verificationVerdictTone\('(danger|warning|success)'\)/)
    expect(verdictTable).not.toMatch(/bg-(red|amber|green|emerald)-\d{2,3}/)

    const verificationResultStatus = boardSource.slice(
      boardSource.indexOf('const verificationResultStatus = computed'),
      boardSource.indexOf('const verificationModelSemanticsConsistent = computed')
    )
    expect(verificationResultStatus).not.toContain('bg-gradient-to-r')

    const reducedMotion = new Map<string, Record<string, string>>()
    postcss.parse(boardCss).walkAtRules('media', media => {
      if (media.params !== '(prefers-reduced-motion: reduce)') return
      media.walkRules(rule => {
        const values: Record<string, string> = {}
        rule.walkDecls(decl => { values[decl.prop] = decl.value })
        for (const selector of rule.selectors) reducedMotion.set(selector, values)
      })
    })
    for (const selector of ['.iot-dialog', '.iot-dialog *']) {
      expect(reducedMotion.get(selector), selector).toMatchObject({
        'scroll-behavior': 'auto',
        'transition-delay': '0ms',
        'transition-duration': '0.01ms'
      })
    }
  })

  it('keeps changes inside the same terminal in every viewport', () => {
    expect(boardSource).not.toContain('showPlaybackChangePopover')
    expect(boardCss).not.toContain('board-playback-change-popover')
    const terminal = readFileSync(resolve(currentDirectory, '../components/PlaybackTerminal.vue'), 'utf8')
    expect(terminal).toContain('role="tablist"')
    expect(terminal).toContain('<slot name="changes" />')
    expect(boardCss).toContain('.playback-terminal__body')
  })

  it('pairs disabled formal-run controls with visible reasons', () => {
    for (const kind of ['verification', 'simulation']) {
      const button = boardSource.slice(
        boardSource.indexOf(`data-testid="run-${kind}"`) - 150,
        boardSource.indexOf(`data-testid="${kind}-run-blocked-reason"`) + 350
      )
      expect(button).toContain(`:aria-describedby="${kind}RunBlockedDescriptionId"`)
      expect(button).toContain(`id="${kind}-run-blocked-reason"`)
      expect(button).toContain(`{{ ${kind}RunBlockedReason }}`)
    }
  })
})
