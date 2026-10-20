// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { DOMWrapper, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n, setLocale } from '@/assets/i18n'
import { useTheme } from '@/composables/useTheme'
import LanguageToggle from '../common/LanguageToggle.vue'
import ThemeToggle from '../common/ThemeToggle.vue'

const tooltip = { template: '<slot />', inheritAttrs: false }
const wrappers: ReturnType<typeof mount>[] = []
let stylesheet: HTMLStyleElement

beforeEach(() => {
  window.matchMedia = vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn() })
  setLocale('zh-CN')
  useTheme().setTheme('light')
  useTheme().setPalette('copper')
  stylesheet = document.createElement('style')
  const base = readFileSync(join(__dirname, '../../styles/base.css'), 'utf8')
  const target = /--iot-target-min:\s*([^;]+);/.exec(base)
  expect(target).not.toBeNull()
  stylesheet.textContent = `:root { --iot-target-min: ${target![1]}; }\n`
    + readFileSync(join(__dirname, '../../styles/header-controls.css'), 'utf8')
  document.head.append(stylesheet)
})

afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  stylesheet.remove()
  setLocale('zh-CN')
  useTheme().setTheme('light')
  useTheme().setPalette('copper')
})

const mountControl = (component: typeof LanguageToggle | typeof ThemeToggle) => {
  const wrapper = mount(component, {
    attachTo: document.body,
    props: { compact: true },
    global: { plugins: [i18n], stubs: { HintTooltip: tooltip } }
  })
  wrappers.push(wrapper)
  return wrapper
}

const appearancePanel = (wrapper: ReturnType<typeof mountControl>) => {
  const id = wrapper.get('.theme-toggle').attributes('aria-controls')
  expect(id).toBeTruthy()
  const element = document.getElementById(id!)
  expect(element, 'the appearance region must match its trigger').not.toBeNull()
  return new DOMWrapper(element!)
}

// jsdom retains var() in computed lengths; resolve the inherited token before measuring the floor.
const pixels = (length: string) => {
  const token = /^var\((--[\w-]+)\)$/.exec(length)
  const value = token ? getComputedStyle(document.documentElement).getPropertyValue(token[1]).trim() : length
  const parsed = /^([\d.]+)(px|rem)$/.exec(value)
  expect(parsed, `unresolved control size: ${length}`).not.toBeNull()
  return Number(parsed![1]) * (parsed![2] === 'rem' ? 16 : 1)
}

describe('shared header preferences', () => {
  it.each(['light', 'dark'] as const)('keeps the full language pair wider than the icon target in %s theme', theme => {
    useTheme().setTheme(theme)
    const language = mountControl(LanguageToggle)
    const appearance = mountControl(ThemeToggle)
    const languageStyle = getComputedStyle(language.get('button').element)
    const appearanceStyle = getComputedStyle(appearance.get('button').element)

    expect(language.get('.language-toggle__label').text()).toBe('中·EN')
    expect(pixels(languageStyle.minWidth)).toBeGreaterThanOrEqual(88)
    expect(pixels(appearanceStyle.width)).toBe(44)
    for (const style of [languageStyle, appearanceStyle]) {
      expect(pixels(style.minHeight)).toBeGreaterThanOrEqual(44)
      expect(style.boxShadow).toBe('none')
      expect(style.borderRadius).toBe(languageStyle.borderRadius)
    }
  })

  it('changes locale, document language and the selected side together', async () => {
    const language = mountControl(LanguageToggle)
    const button = language.get('button')
    expect(button.attributes('lang')).toBe('en')
    await button.trigger('click')

    expect(document.documentElement.lang).toBe('en')
    expect(localStorage.getItem('locale')).toBe('en')
    expect(button.attributes('lang')).toBe('zh-CN')
    expect(language.get('.language-toggle__side--active').text()).toBe('EN')
    expect(language.get('.language-toggle__label').text()).toBe('中·EN')
  })

  it('offers every mode and palette in one picker without widening the compact trigger', async () => {
    const appearance = mountControl(ThemeToggle)
    await appearance.get('.theme-toggle').trigger('click')
    await flushPromises()
    expect(appearance.get('.theme-toggle').attributes('aria-expanded')).toBe('true')
    await appearancePanel(appearance).get('[data-theme-mode="dark"]').trigger('click')
    expect(useTheme().theme.value).toBe('dark')
    await appearancePanel(appearance).get('[data-color-palette="monochrome"]').trigger('click')
    expect(useTheme().palette.value).toBe('monochrome')
    expect(document.documentElement.dataset.palette).toBe('monochrome')
    expect(useTheme().theme.value).toBe('dark')
    expect(appearancePanel(appearance).get('[data-color-palette="monochrome"]').attributes('aria-pressed')).toBe('true')
    await appearancePanel(appearance).get('[data-theme-mode="system"]').trigger('click')
    expect(useTheme().followsSystem.value).toBe(true)
    expect(useTheme().palette.value).toBe('monochrome')
    await appearancePanel(appearance).get('[data-theme-mode="light"]').trigger('click')
    expect(useTheme().theme.value).toBe('light')
    expect(useTheme().followsSystem.value).toBe(false)
    expect(appearance.get('.theme-toggle').attributes('aria-label')).toContain('黑白')
  })

  it('focuses the selected mode and restores the trigger on Escape', async () => {
    const appearance = mountControl(ThemeToggle)
    const trigger = appearance.get<HTMLButtonElement>('.theme-toggle')
    trigger.element.focus()
    await trigger.trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(appearancePanel(appearance).get('[data-theme-mode="light"]').element)
    await appearancePanel(appearance).trigger('keydown', { key: 'Escape' })
    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger.element)
  })

  it('keeps two header instances synchronized and closes when keyboard focus leaves', async () => {
    const first = mountControl(ThemeToggle)
    const second = mountControl(ThemeToggle)
    await first.get('.theme-toggle').trigger('click')
    await flushPromises()
    await appearancePanel(first).get('[data-color-palette="monochrome"]').trigger('click')
    expect(second.get('.theme-toggle').attributes('aria-label')).toContain('黑白')
    const outside = document.createElement('button')
    document.body.append(outside)
    outside.focus()
    await flushPromises()
    expect(first.get('.theme-toggle').attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(outside)
    outside.remove()
  })

  it('closes on outside pointer input and releases positioning listeners on unmount', async () => {
    const removeDocument = vi.spyOn(document, 'removeEventListener')
    const removeWindow = vi.spyOn(window, 'removeEventListener')
    const appearance = mountControl(ThemeToggle)
    await appearance.get('.theme-toggle').trigger('click')
    await flushPromises()
    document.body.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(appearance.get('.theme-toggle').attributes('aria-expanded')).toBe('false')
    appearance.unmount()
    wrappers.splice(wrappers.indexOf(appearance), 1)
    expect(removeDocument).toHaveBeenCalledWith('pointerdown', expect.any(Function), true)
    expect(removeWindow).toHaveBeenCalledWith('resize', expect.any(Function))
    expect(removeWindow).toHaveBeenCalledWith('scroll', expect.any(Function), true)
    removeDocument.mockRestore()
    removeWindow.mockRestore()
  })

  it('localizes the complete appearance picker in English', async () => {
    setLocale('en')
    const appearance = mountControl(ThemeToggle)
    await appearance.get('.theme-toggle').trigger('click')
    await flushPromises()
    const panel = appearancePanel(appearance)
    expect(panel.text()).toContain('Monochrome')
    expect(panel.text()).toContain('Copper')
    expect(panel.text()).toContain('System')
    expect(panel.text()).not.toMatch(/外观|明暗|陶土|黑白/)
  })
})
