// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it } from 'vitest'
import RecommendationProgressStatus from '../RecommendationProgressStatus.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: {
    en: {
      app: {
        recommendationProgressTitle: 'AI recommendation in progress',
        recommendationProgressElapsed: '{seconds}s elapsed',
        recommendationProgressContext: '{templates}/{devices}/{rules}/{specs}',
        recommendationProgressQueued: 'Waiting for server capacity',
        recommendationProgressSubmitting: 'Submitting the request',
        recommendationProgressAnalyzing: 'Waiting for the model service; private reasoning is not observable.',
        recommendationProgressValidating: 'Validating the returned candidates',
        recommendationProgressCancelling: 'Stopping the server request',
        recommendationProgressStillWorking: 'Still processing; you can stop at any time.'
      }
    }
  }
})

const mountStatus = (stage: 'QUEUED' | 'REQUESTING_MODEL' | 'VALIDATING_RESULT', elapsedSeconds: number) => mount(RecommendationProgressStatus, {
  props: {
    elapsedSeconds,
    stage,
    templateCount: 45,
    deviceCount: 2,
    ruleCount: 1,
    specCount: 1
  },
  global: { plugins: [i18n] }
})

describe('RecommendationProgressStatus', () => {
  it('does not infer unobservable model phases from elapsed time', () => {
    expect(mountStatus('QUEUED', 31).text()).toContain('Waiting for server capacity')
    expect(mountStatus('REQUESTING_MODEL', 1).text()).toContain('Waiting for the model service')
  })

  it('shows server-observed response validation', () => {
    expect(mountStatus('VALIDATING_RESULT', 31).text()).toContain('Validating the returned candidates')
  })
})
