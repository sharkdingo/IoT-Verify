<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { setLocale } from '@/assets/i18n'
import HintTooltip from '@/components/common/HintTooltip.vue'

withDefaults(defineProps<{
  compact?: boolean
}>(), {
  compact: false
})

const { t, locale } = useI18n()

// Show both choices and mark the current locale without changing the button width.
const isChinese = computed(() => locale.value === 'zh-CN')

// The accessible name states the destination in its own language.
const switchTargetDescription = computed(() =>
  locale.value === 'zh-CN' ? t('app.switchToEnglish') : t('app.switchToChinese'))

// setLocale owns persistence and the document language.
const toggleLocale = () => {
  setLocale(locale.value === 'zh-CN' ? 'en' : 'zh-CN')
}
</script>

<template>
  <HintTooltip :content="switchTargetDescription">
    <button
      type="button"
      class="header-preference language-toggle"
      :class="{ 'language-toggle--compact': compact }"
      :aria-label="switchTargetDescription"
      :lang="isChinese ? 'en' : 'zh-CN'"
      @click="toggleLocale"
    >
      <span class="material-symbols-outlined language-toggle__icon" aria-hidden="true">language</span>
      <!-- aria-hidden: `aria-label` already states the destination in a full sentence, so reading the
           pair out again would only add noise. -->
      <span class="language-toggle__label" aria-hidden="true">
        <span :class="['language-toggle__side', { 'language-toggle__side--active': isChinese }]">中</span>
        <span class="language-toggle__divider">·</span>
        <span :class="['language-toggle__side', { 'language-toggle__side--active': !isChinese }]">EN</span>
      </span>
    </button>
  </HintTooltip>
</template>
