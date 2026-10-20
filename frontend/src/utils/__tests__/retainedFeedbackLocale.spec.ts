import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { ElNotification } from 'element-plus'
import i18n from '@/assets/i18n'
import { notifyAvailableResult } from '@/utils/feedback'

describe('retained feedback with Element Plus', () => {
  it('keeps one localized heading and action owner after a language change', async () => {
    const locale = i18n.global.locale.value
    i18n.global.locale.value = 'en'
    const onAction = vi.fn().mockReturnValue(true)
    const onClose = vi.fn()
    const close = notifyAvailableResult({
      title: () => i18n.global.t('app.failedToLoadSimulationRun'),
      message: () => i18n.global.t('app.completedTaskResultUnavailable'),
      actionLabel: () => i18n.global.t('app.viewDetails'), discardLabel: () => i18n.global.t('app.close'),
      onAction, onClose
    })
    try {
      for (const value of ['en', 'zh-CN'] as const) {
        i18n.global.locale.value = value
        await nextTick()
        const notices = document.querySelectorAll('.iot-result-notification')
        expect(notices).toHaveLength(1)
        const notice = notices[0]!
        expect([...notice.querySelectorAll('h2')].filter(item => item.textContent?.trim()).map(item => item.textContent))
          .toEqual([i18n.global.t('app.failedToLoadSimulationRun')])
        expect(notice.textContent).toContain(i18n.global.t('app.completedTaskResultUnavailable'))
        expect(notice.querySelector('[data-testid="available-result-open"]')?.textContent).toBe(i18n.global.t('app.viewDetails'))
      }
      document.querySelector<HTMLButtonElement>('.iot-result-notification [data-testid="available-result-open"]')!.click()
      await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce())
      expect(onAction).toHaveBeenCalledOnce()
    } finally {
      close()
      ElNotification.closeAll()
      i18n.global.locale.value = locale
    }
  })
})
