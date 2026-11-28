// @vitest-environment node
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
})
