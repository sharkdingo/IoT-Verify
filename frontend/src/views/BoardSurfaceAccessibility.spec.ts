import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const currentDirectory = dirname(fileURLToPath(import.meta.url))
const boardSource = readFileSync(resolve(currentDirectory, 'Board.vue'), 'utf8')

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

  it('isolates narrow-screen background controls', () => {
    const narrowBackground = boardSource.slice(
      boardSource.indexOf('data-testid="board-narrow-background"') - 100,
      boardSource.indexOf('data-testid="board-narrow-background"') + 300
    )
    expect(narrowBackground).toContain(':inert="showNarrowPanelScrim ? true : undefined"')
    expect(narrowBackground).toContain(':aria-hidden="showNarrowPanelScrim ? \'true\' : undefined"')

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
