<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LanguageToggle from './LanguageToggle.vue'
import ThemeToggle from './ThemeToggle.vue'
import { useTheme } from '@/composables/useTheme'

type HeaderVariant = 'transparent' | 'auth' | 'light'
type ControlTone = 'light' | 'dark' | 'glass'

const props = withDefaults(defineProps<{
  variant?: HeaderVariant
  showNav?: boolean
  showTheme?: boolean
  primaryLabel?: string
  primaryTo?: string
  secondaryLabel?: string
  secondaryTo?: string
}>(), {
  variant: 'transparent',
  showNav: false,
  showTheme: true,
  primaryLabel: '',
  primaryTo: '',
  secondaryLabel: '',
  secondaryTo: ''
})

const router = useRouter()
const { t } = useI18n()
const { theme } = useTheme()

const controlTone = computed<ControlTone>(() => {
  if (props.variant === 'transparent') return 'glass'
  return theme.value === 'dark' ? 'dark' : 'light'
})
const navItems = computed(() => [
  t('app.devices'),
  t('app.automation'),
  t('app.templates'),
  t('app.rules')
])

const goHome = () => {
  router.push('/')
}

const navigateTo = (target?: string) => {
  if (!target) return
  router.push(target)
}
</script>

<template>
  <nav
    class="public-header"
    :class="[
      `public-header--${variant}`,
      { 'public-header--dark': theme === 'dark' }
    ]"
    :aria-label="t('app.title')"
    data-toast-anchor
  >
    <button type="button" class="public-header__brand" @click="goHome">
      <span class="public-header__brand-mark">IoT-Verify</span>
      <sup class="public-header__brand-sup">®</sup>
    </button>

    <div v-if="showNav" class="public-header__nav" aria-hidden="true">
      <span
        v-for="item in navItems"
        :key="item"
        class="public-header__nav-item"
      >
        {{ item }}
      </span>
    </div>

    <div class="public-header__actions">
      <ThemeToggle v-if="showTheme" :tone="controlTone" compact />
      <LanguageToggle :tone="controlTone" compact />

      <button
        v-if="secondaryLabel"
        type="button"
        class="public-header__button public-header__button--secondary"
        @click="navigateTo(secondaryTo)"
      >
        {{ secondaryLabel }}
      </button>

      <button
        v-if="primaryLabel"
        type="button"
        class="public-header__button public-header__button--primary"
        @click="navigateTo(primaryTo)"
      >
        {{ primaryLabel }}
      </button>
    </div>
  </nav>
</template>

<style scoped>
.public-header {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 30;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  grid-template-areas: "brand nav actions";
  align-items: center;
  gap: 24px;
  min-height: 72px;
  padding: 18px clamp(20px, 4vw, 64px);
}

.public-header--auth,
.public-header--light {
  background: rgba(255, 255, 255, 0.84);
  border-bottom: 1px solid rgba(226, 232, 240, 0.78);
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.public-header--auth.public-header--dark,
.public-header--light.public-header--dark {
  background: rgba(15, 23, 42, 0.94);
  border-bottom-color: rgba(51, 65, 85, 0.78);
  box-shadow: 0 12px 30px rgba(2, 6, 23, 0.28);
}

.public-header__brand {
  grid-area: brand;
  justify-self: start;
  display: inline-flex;
  align-items: baseline;
  border: none;
  background: transparent;
  color: #ffffff;
  cursor: pointer;
  padding: 0;
  text-align: left;
}

.public-header--auth .public-header__brand,
.public-header--light .public-header__brand {
  color: var(--text);
}

.public-header--auth.public-header--dark .public-header__brand,
.public-header--light.public-header--dark .public-header__brand {
  color: #f8fafc;
}

.public-header__brand-mark {
  font-family: var(--iot-font-display);
  font-size: clamp(1.35rem, 2vw, 1.875rem);
  line-height: 1;
}

.public-header__brand-sup {
  font-size: 0.72rem;
  line-height: 1;
  margin-left: 2px;
}

.public-header__nav {
  grid-area: nav;
  justify-self: center;
  display: flex;
  align-items: center;
  gap: clamp(18px, 3vw, 32px);
  color: rgba(255, 255, 255, 0.76);
  font-size: 0.875rem;
  font-weight: 500;
  white-space: nowrap;
}

.public-header--auth .public-header__nav,
.public-header--light .public-header__nav {
  color: var(--text-muted);
}

.public-header--auth.public-header--dark .public-header__nav,
.public-header--light.public-header--dark .public-header__nav {
  color: var(--text-muted);
}

.public-header__nav-item:first-child {
  color: #ffffff;
}

.public-header--auth .public-header__nav-item:first-child,
.public-header--light .public-header__nav-item:first-child {
  color: var(--text);
}

.public-header--auth.public-header--dark .public-header__nav-item:first-child,
.public-header--light.public-header--dark .public-header__nav-item:first-child {
  color: #f8fafc;
}

.public-header__actions {
  grid-area: actions;
  justify-self: end;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.public-header__button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  max-width: 180px;
  padding: 0 16px;
  border-radius: var(--iot-radius-pill);
  border: 1px solid transparent;
  font-size: 0.875rem;
  font-weight: 700;
  line-height: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    box-shadow 0.18s ease,
    background-color 0.18s ease,
    border-color 0.18s ease;
}

.public-header__button:hover {
  transform: translateY(-1px);
}

.public-header__button:active {
  transform: translateY(0);
}

.public-header__button--primary {
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.24);
  color: #ffffff;
  box-shadow: inset 0 1px 1px rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.public-header--auth .public-header__button--primary,
.public-header--light .public-header__button--primary {
  background: var(--accent-fill);
  border-color: var(--accent);
  color: #ffffff;
  box-shadow: 0 12px 24px color-mix(in srgb, var(--accent-fill) 22%, transparent);
}

.public-header__button--secondary {
  background: rgba(255, 255, 255, 0.88);
  border-color: rgba(148, 163, 184, 0.38);
  color: #1e40af;
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.08);
}

.public-header__button--secondary:hover {
  background: var(--surface-elevated);
}

.public-header--dark .public-header__button--secondary {
  background: rgba(15, 23, 42, 0.78);
  border-color: rgba(148, 163, 184, 0.28);
  color: #dbeafe;
  box-shadow: 0 8px 20px rgba(2, 6, 23, 0.2);
}

.public-header--dark .public-header__button--secondary:hover {
  background: rgba(30, 41, 59, 0.9);
}

@media (max-width: 860px) {
  .public-header {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas: "brand actions";
    min-height: 64px;
    gap: 14px;
    padding: 14px 18px;
  }

  .public-header__nav {
    display: none;
  }

  .public-header__button {
    max-width: 128px;
    padding: 0 12px;
  }
}

@media (max-width: 520px) {
  .public-header {
    padding: 12px 14px;
  }

  .public-header__brand-mark {
    font-size: 1.25rem;
  }

  .public-header__actions {
    gap: 8px;
  }

  .public-header__button {
    display: none;
  }
}
</style>
