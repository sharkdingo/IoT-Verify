import { computed, ref } from 'vue'

export type ThemeMode = 'light' | 'dark'
export type ColorPalette = 'copper' | 'monochrome'

const THEME_STORAGE_KEY = 'iot_verify_theme'
const PALETTE_STORAGE_KEY = 'iot_verify_palette'
const theme = ref<ThemeMode>('light')
const palette = ref<ColorPalette>('copper')
const followsSystem = ref(false)
let initialized = false

const getSystemTheme = (): ThemeMode => {
  if (typeof window === 'undefined') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

const readStoredTheme = (): ThemeMode | null => {
  if (typeof localStorage === 'undefined') return null
  const stored = localStorage.getItem(THEME_STORAGE_KEY)
  return stored === 'dark' || stored === 'light' ? stored : null
}

const applyTheme = (mode: ThemeMode) => {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.theme = mode
  root.dataset.palette = palette.value
  root.classList.toggle('dark', mode === 'dark')
  root.classList.toggle('light', mode === 'light')
  root.style.colorScheme = mode
}

const handleSystemThemeChange = () => {
  if (!followsSystem.value) return
  theme.value = getSystemTheme()
  applyTheme(theme.value)
}

export const initializeTheme = () => {
  if (initialized) return
  initialized = true

  // No stored preference means "follow the OS", which is also the first-run default.
  // A stored value is an explicit user choice and wins until it is reset.
  const storedTheme = readStoredTheme()
  palette.value = typeof localStorage !== 'undefined' && localStorage.getItem(PALETTE_STORAGE_KEY) === 'monochrome'
    ? 'monochrome' : 'copper'
  followsSystem.value = storedTheme === null
  theme.value = storedTheme ?? getSystemTheme()
  applyTheme(theme.value)

  if (typeof window !== 'undefined') {
    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
    mediaQuery.addEventListener?.('change', handleSystemThemeChange)
  }
}

export const useTheme = () => {
  initializeTheme()

  const setTheme = (mode: ThemeMode) => {
    followsSystem.value = false
    theme.value = mode
    localStorage.setItem(THEME_STORAGE_KEY, mode)
    applyTheme(mode)
  }

  const resetThemeToSystem = () => {
    followsSystem.value = true
    localStorage.removeItem(THEME_STORAGE_KEY)
    theme.value = getSystemTheme()
    applyTheme(theme.value)
  }

  const setPalette = (value: ColorPalette) => {
    palette.value = value
    localStorage.setItem(PALETTE_STORAGE_KEY, value)
    applyTheme(theme.value)
  }

  return {
    theme,
    palette,
    followsSystem,
    isDark: computed(() => theme.value === 'dark'),
    setTheme,
    setPalette,
    resetThemeToSystem
  }
}
