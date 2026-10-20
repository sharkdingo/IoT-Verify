import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

/** Scene contents may be grouped, but complete deletion and irrecoverable result/history loss stay explicit. */

const i18n = () => readFileSync(join(__dirname, '../../assets/i18n.ts'), 'utf8')

const warningFor = (locale: 'en' | 'zh') => {
  const source = i18n()
  const matches = [...source.matchAll(/deleteAccountDataWarning:\s*'([^']+)'/g)].map(m => m[1])
  expect(matches.length, 'both locales should declare the warning').toBe(2)
  // The zh block is declared first in this file.
  return locale === 'zh' ? matches[0] : matches[1]
}

describe('account deletion scope', () => {
  it('states all account data is removed and names the principal artifact groups in English', () => {
    const text = warningFor('en').toLowerCase()
    expect(text).toContain('your account and all its data')
    for (const category of [
      'scene', 'template', 'run and edit history', 'counterexample', 'ai conversation'
    ]) {
      expect(text, `the warning should name "${category}"`).toContain(category)
    }
  })

  it('states all account data is removed and names the principal artifact groups in Chinese', () => {
    const text = warningFor('zh')
    expect(text).toContain('账号及其全部数据')
    for (const category of ['场景', '模板', '运行和编辑历史', '反例', 'AI 会话']) {
      expect(text, `警告文案应包含「${category}」`).toContain(category)
    }
  })

  it('says the loss cannot be recovered, not only that it is permanent', () => {
    expect(warningFor('en').toLowerCase()).toContain('cannot be recovered')
    expect(warningFor('zh')).toContain('无法恢复')
  })

  it('keeps the destructive control disarmed and looking disarmed', () => {
    const dialog = readFileSync(join(__dirname, '../AccountDeleteDialog.vue'), 'utf8')

    // Disabled until both the identifier and the password are supplied.
    expect(dialog).toContain(':disabled="!canConfirm"')

    // And visibly so. `opacity: 0.58` alone left the button saturated red with its glow intact, and all three
    // reviews read it as enabled while measurement showed `disabled: true` — the control was right and its
    // appearance was not, which on an irreversible action is the worse failure.
    const disabledRule = dialog.slice(dialog.indexOf('.account-delete-confirm:disabled'))
    expect(disabledRule.slice(0, disabledRule.indexOf('}'))).toContain('box-shadow: none')

    // Desaturated to a neutral, not tinted toward the danger hue and not faded. Both of the treatments this
    // replaced were measured illegible: `opacity: 0.58` over a saturated red still read as armed, and mixing
    // danger 34% into the white surface produced a pink wash under white ink.
    const sheet = readFileSync(join(__dirname, '../../styles/dialog.css'), 'utf8')
    const shared = sheet.slice(sheet.indexOf('.iot-dialog-btn--danger:disabled'))
    expect(shared.slice(0, shared.indexOf('}'))).toContain('var(--accent-fill-disabled)')
  })

  it('keeps the flow addressable, since it is the one irreversible action', () => {
    const dialog = readFileSync(join(__dirname, '../AccountDeleteDialog.vue'), 'utf8')
    const account = readFileSync(join(__dirname, '../../views/Account.vue'), 'utf8')
    const logout = readFileSync(join(__dirname, '../LogoutConfirmDialog.vue'), 'utf8')

    // Deletion has its own page entry; the sign-out confirmation only answers the sign-out question.
    expect(account).toContain('data-testid="open-account-delete"')
    expect(logout).not.toContain('data-testid="open-account-delete"')
    for (const id of [
      'account-delete-dialog',
      'account-delete-confirmation',
      'account-delete-password',
      'account-delete-confirm',
      'account-delete-cancel'
    ]) {
      expect(dialog, `${id} should be addressable`).toContain(`data-testid="${id}"`)
    }
  })
})
