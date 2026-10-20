// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const THEME_KEY = 'iot_verify_theme'

let prefersDark = false
const listeners = new Set<() => void>()

const loadTheme = async () => {
  vi.resetModules()
  return import('../useTheme')
}

beforeEach(() => {
  prefersDark = false
  listeners.clear()
  localStorage.clear()
  document.documentElement.className = ''
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('prefers-color-scheme: dark') ? prefersDark : false,
    media: query,
    addEventListener: (_: string, handler: () => void) => { listeners.add(handler) },
    removeEventListener: (_: string, handler: () => void) => { listeners.delete(handler) }
  }))
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useTheme', () => {
  it('follows the system when the user has never chosen a theme', async () => {
    prefersDark = true
    const { useTheme } = await loadTheme()
    const { theme, followsSystem } = useTheme()

    expect(followsSystem.value).toBe(true)
    expect(theme.value).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
  })

  it('reacts to system changes only while following the system', async () => {
    const { useTheme } = await loadTheme()
    const { theme, setTheme } = useTheme()

    prefersDark = true
    listeners.forEach(handler => handler())
    expect(theme.value).toBe('dark')

    setTheme('light')
    prefersDark = false
    listeners.forEach(handler => handler())
    prefersDark = true
    listeners.forEach(handler => handler())
    expect(theme.value).toBe('light')
  })

  it('treats a stored theme as an explicit choice that survives reload', async () => {
    localStorage.setItem(THEME_KEY, 'dark')
    prefersDark = false
    const { useTheme } = await loadTheme()
    const { theme, followsSystem } = useTheme()

    expect(followsSystem.value).toBe(false)
    expect(theme.value).toBe('dark')
  })

  it('makes explicit light, dark and system choices independent of palette', async () => {
    prefersDark = true
    const { useTheme } = await loadTheme()
    const { theme, palette, followsSystem, setTheme, setPalette, resetThemeToSystem } = useTheme()
    setPalette('monochrome')
    expect(followsSystem.value).toBe(true)
    setTheme('light')
    expect(theme.value).toBe('light')
    expect(localStorage.getItem(THEME_KEY)).toBe('light')
    setTheme('dark')
    expect(theme.value).toBe('dark')
    resetThemeToSystem()
    expect(followsSystem.value).toBe(true)
    expect(theme.value).toBe('dark')
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
    expect(palette.value).toBe('monochrome')
    expect(document.documentElement.dataset.palette).toBe('monochrome')
    expect(localStorage.getItem('iot_verify_palette')).toBe('monochrome')
  })

  it('restores a palette before applying the first theme and retains it across system changes', async () => {
    localStorage.setItem('iot_verify_palette', 'monochrome')
    const { useTheme } = await loadTheme()
    const { palette, followsSystem } = useTheme()
    expect(palette.value).toBe('monochrome')
    expect(document.documentElement.dataset.palette).toBe('monochrome')
    expect(followsSystem.value).toBe(true)
    prefersDark = true
    listeners.forEach(handler => handler())
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(document.documentElement.dataset.palette).toBe('monochrome')
  })

  it('shares palette selection between consumers and restores copper explicitly', async () => {
    const { useTheme } = await loadTheme()
    const first = useTheme()
    const second = useTheme()
    first.setPalette('monochrome')
    expect(second.palette.value).toBe('monochrome')
    second.setPalette('copper')
    expect(first.palette.value).toBe('copper')
    expect(document.documentElement.dataset.palette).toBe('copper')
    expect(localStorage.getItem('iot_verify_palette')).toBe('copper')
  })
})
