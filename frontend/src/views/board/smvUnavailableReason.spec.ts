import { describe, expect, it } from 'vitest'

import { i18n } from '@/assets/i18n'
import { smvUnavailableReasonKey } from './smvUnavailableReason'

/**
 * An unavailable SMV model must not blame the wrong cause.
 *
 * The single message this notice used to render told every reader to "check whether the run is still in
 * your history". That is sound only for a run that was saved. Three of the four `RunPersistenceStatus`
 * values reach the same notice with no history record to check and none forthcoming — the user did not
 * ask to save, the save failed, or its outcome is unconfirmed — so the advice named a cause that was not
 * theirs and an action that could not succeed.
 */
describe('SMV unavailable reason', () => {
  it('sends a saved run to its history record', () => {
    expect(smvUnavailableReasonKey({ status: 'SAVED' })).toBe('app.smvModelNotAvailable')
  })

  it('does not send an unsaved run to a history record it will never have', () => {
    expect(smvUnavailableReasonKey({ status: 'NOT_REQUESTED' })).toBe('app.smvModelNotPersisted')
    expect(smvUnavailableReasonKey({ status: 'FAILED' })).toBe('app.smvModelNotPersisted')
  })

  it('keeps an unconfirmed write distinct from a confirmed absence', () => {
    // The whole point of `OUTCOME_UNKNOWN`: the client does not know whether the row exists. Reporting
    // it as "not saved" would resolve an unknown into a fact the client cannot support.
    expect(smvUnavailableReasonKey({ status: 'OUTCOME_UNKNOWN' }))
      .toBe('app.smvModelPersistenceUnknown')
  })

  it('treats an unrecognized or absent status as making no claim about why', () => {
    // Not knowing what a status means is not evidence that nothing was saved, so this falls through to
    // the wording that does not assert a cause.
    expect(smvUnavailableReasonKey({ status: 'SOMETHING_NEW' } as never))
      .toBe('app.smvModelNotAvailable')
    expect(smvUnavailableReasonKey(undefined)).toBe('app.smvModelNotAvailable')
  })

  it('every key it can return is defined in both locales', () => {
    const keys = [
      'app.smvModelNotAvailable',
      'app.smvModelNotPersisted',
      'app.smvModelPersistenceUnknown'
    ]
    for (const key of keys) {
      expect(i18n.global.te(key, 'en')).toBe(true)
      expect(i18n.global.te(key, 'zh-CN')).toBe(true)
    }
    expect(i18n.global.t(smvUnavailableReasonKey({ status: 'SAVED' }), {}, { locale: 'zh-CN' }))
      .toMatch(/删除.*未留下模型.*运行历史/)
  })
})
