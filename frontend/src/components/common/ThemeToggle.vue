<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import HintTooltip from '@/components/common/HintTooltip.vue'
import { useTheme } from '@/composables/useTheme'

withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })

const { t } = useI18n()
const { theme, palette, followsSystem, setTheme, setPalette, resetThemeToSystem } = useTheme()
const open = ref(false)
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const panelId = `appearance-${useId()}`
const mode = computed(() => followsSystem.value ? 'system' : theme.value)
const currentModeLabel = computed(() => t(`app.${mode.value === 'system' ? 'systemTheme' : mode.value === 'dark' ? 'darkTheme' : 'lightTheme'}`))
const currentPaletteLabel = computed(() => t(palette.value === 'monochrome' ? 'app.paletteMonochrome' : 'app.paletteCopper'))
const appearanceLabel = computed(() => `${t('app.appearanceSettings')}: ${currentModeLabel.value} · ${currentPaletteLabel.value}`)
const iconName = computed(() => mode.value === 'system' ? 'brightness_auto' : mode.value === 'dark' ? 'dark_mode' : 'light_mode')
const modeOptions = [
  { value: 'light', label: 'app.lightTheme', icon: 'light_mode' },
  { value: 'dark', label: 'app.darkTheme', icon: 'dark_mode' },
  { value: 'system', label: 'app.systemTheme', icon: 'brightness_auto' }
] as const
const palettes = [
  { value: 'copper', label: 'app.paletteCopper', icon: 'palette' },
  { value: 'monochrome', label: 'app.paletteMonochrome', icon: 'contrast' }
] as const

const placement = ref<Record<string, string>>({})
const placePanel = () => {
  if (!open.value || !trigger.value || !panel.value) return
  const bounds = trigger.value.getBoundingClientRect()
  placement.value = {
    '--appearance-anchor-right': bounds.right + 'px',
    '--appearance-anchor-bottom': bounds.bottom + 'px',
    '--appearance-height': panel.value.offsetHeight + 'px'
  }
}

const togglePanel = async () => {
  open.value = !open.value
  if (!open.value) return
  await nextTick()
  if (!open.value) return
  placePanel()
  await nextTick()
  if (open.value) panel.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus({ preventScroll: true })
}

const closeOnOutsidePointer = (event: PointerEvent) => {
  const target = event.target
  if (target instanceof Node && !panel.value?.contains(target) && !trigger.value?.contains(target)) open.value = false
}

const closeOnEscape = (event: KeyboardEvent) => {
  if (event.key !== 'Escape' || !open.value) return
  event.preventDefault()
  event.stopPropagation()
  open.value = false
  trigger.value?.focus()
}

const closeOnFocusLeave = (event: FocusEvent) => {
  const destination = event.relatedTarget
  if (destination instanceof Node && !panel.value?.contains(destination) && destination !== trigger.value) {
    open.value = false
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', closeOnOutsidePointer, true)
  window.addEventListener('resize', placePanel)
  window.addEventListener('scroll', placePanel, true)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', closeOnOutsidePointer, true)
  window.removeEventListener('resize', placePanel)
  window.removeEventListener('scroll', placePanel, true)
})
</script>

<template>
  <HintTooltip :content="compact && !open ? appearanceLabel : ''">
    <button
      ref="trigger"
      type="button"
      class="header-preference theme-toggle"
      :class="{ 'theme-toggle--compact': compact }"
      :aria-label="appearanceLabel"
      :aria-expanded="open"
      :aria-controls="panelId"
      @click="togglePanel"
      @keydown="closeOnEscape"
      @focusout="closeOnFocusLeave"
    >
      <span class="material-symbols-outlined theme-toggle__icon" aria-hidden="true">{{ iconName }}</span>
      <span class="theme-toggle__label">{{ currentModeLabel }} · {{ currentPaletteLabel }}</span>
    </button>
  </HintTooltip>
  <Teleport to="body">
    <section v-if="open" ref="panel" :id="panelId" class="appearance-panel appearance-panel--popover iot-scroll-region" role="region" :style="placement" :aria-label="t('app.appearanceSettings')" @keydown="closeOnEscape" @focusout="closeOnFocusLeave">
      <fieldset class="appearance-panel__group">
        <legend class="board-form-label">{{ t('app.appearanceMode') }}</legend>
        <div class="appearance-panel__modes">
          <button v-for="option in modeOptions" :key="option.value" type="button" :data-theme-mode="option.value" :aria-pressed="mode === option.value" @click="option.value === 'system' ? resetThemeToSystem() : setTheme(option.value)">
            <span class="material-symbols-outlined" aria-hidden="true">{{ option.icon }}</span>
            <span>{{ t(option.label) }}</span>
          </button>
        </div>
      </fieldset>
      <fieldset class="appearance-panel__group">
        <legend class="board-form-label">{{ t('app.colorPalette') }}</legend>
        <div class="appearance-panel__palettes">
          <button v-for="option in palettes" :key="option.value" type="button" :data-color-palette="option.value" :aria-pressed="palette === option.value" @click="setPalette(option.value)">
            <span class="material-symbols-outlined" :class="{ 'appearance-panel__copper': option.value === 'copper' }" aria-hidden="true">{{ option.icon }}</span>
            <span>{{ t(option.label) }}</span>
            <span class="appearance-panel__check material-symbols-outlined" :class="{ 'appearance-panel__check--hidden': palette !== option.value }" aria-hidden="true">check</span>
          </button>
        </div>
      </fieldset>
    </section>
  </Teleport>
</template>
