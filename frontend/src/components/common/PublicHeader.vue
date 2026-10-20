<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import LanguageToggle from './LanguageToggle.vue'
import ThemeToggle from './ThemeToggle.vue'
import ProductMark from './ProductMark.vue'

const router = useRouter()
const { t } = useI18n()
</script>

<template>
  <nav class="public-header" :aria-label="t('app.title')" data-toast-anchor>
    <button type="button" class="public-header__brand" @click="router.push('/')">
      <ProductMark />
      <span class="public-header__brand-mark">IoT-Verify</span>
    </button>
    <div class="public-header__actions">
      <ThemeToggle compact />
      <LanguageToggle compact />
    </div>
  </nav>
</template>

<style scoped>
.public-header {
  position: absolute;
  inset: 0 0 auto;
  z-index: var(--z-board-nav);
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  grid-template-areas: "brand actions";
  align-items: center;
  gap: var(--iot-space-xl);
  min-height: var(--iot-header-height);
  border-bottom: 1px solid var(--border);
  background: var(--surface-panel);
  padding: var(--iot-space-md) var(--iot-page-gutter);
}

.public-header__brand {
  grid-area: brand;
  justify-self: start;
  display: inline-flex;
  align-items: center;
  gap: var(--iot-space-sm);
  min-height: var(--iot-target-min);
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--text);
  text-align: left;
  cursor: pointer;
  transition: opacity var(--iot-motion-fast) ease;
}

.public-header__brand:hover {
  opacity: 0.8;
}

.public-header__brand-mark {
  font-family: var(--iot-font-display);
  font-size: 1.125rem;
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.035em;
}

.public-header__actions {
  grid-area: actions;
  justify-self: end;
  display: flex;
  align-items: center;
  gap: var(--iot-space-sm);
  min-width: 0;
}

.public-header__brand:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: 3px;
}

@media (max-width: 860px) {
  .public-header {
    gap: var(--iot-space-md);
    padding-inline: var(--iot-space-lg);
  }
}

@media (max-width: 520px) {
  .public-header__brand-mark {
    font-size: 1rem;
  }
  .public-header__actions {
    gap: var(--iot-space-2xs);
  }
}

</style>
