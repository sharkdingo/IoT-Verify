import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { i18n } from '@/assets/i18n'
import SceneImportRepairs from '../SceneImportRepairs.vue'

describe('scene import repair content', () => {
  it.each(['en', 'zh-CN'])('renders %s in an external confirmation root without an injected i18n plugin', locale => {
    const previous = i18n.global.locale.value
    i18n.global.locale.value = locale as typeof previous
    try {
      const wrapper = mount(SceneImportRepairs, { props: {
        t: i18n.global.t,
        plan: {
          scene: { schema: 'iot-verify.board-scene', version: 6,
            templates: [], devices: [], rules: [], specs: [], environmentVariables: [] },
          templateRenames: [{ from: 'Sensor', to: 'Sensor (imported)' }],
          variableCorrections: [{ device: 'My car', variable: 'location', state: 'away', from: 'garage', to: 'away' }],
          addedTemplates: 1, libraryLimitExceeded: false
        }
      } })
      expect(wrapper.get('h3').text()).toBe(i18n.global.t('app.sceneImportRepairsTitle'))
      expect(wrapper.get('ul').text()).toContain('Sensor → Sensor (imported)')
      expect(wrapper.findAll('li')[1]!.text()).toBe(i18n.global.t('app.sceneImportVariableCorrection', {
        device: 'My car', variable: 'location', state: 'away', from: 'garage', to: 'away'
      }))
      wrapper.unmount()
    } finally {
      i18n.global.locale.value = previous
    }
  })
})
