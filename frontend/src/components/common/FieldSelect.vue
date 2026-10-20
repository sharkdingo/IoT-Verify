<script setup lang="ts" generic="T extends string | string[]">
import { computed, onUpdated, ref } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: T
  value?: T
  multiple?: boolean
}>(), { multiple: false })

const emit = defineEmits<{ 'update:modelValue': [value: T] }>()
const field = ref<HTMLSelectElement>()
const selectedValue = computed({
  get: () => props.modelValue ?? props.value,
  set: value => emit('update:modelValue', value as T)
})

onUpdated(() => {
  const element = field.value
  if (element && !props.multiple) {
    // Native selectedcontent refreshes on selectedIndex assignment; option text edits alone leave it stale.
    element.selectedIndex = element.selectedIndex
  }
})
</script>

<template>
  <select ref="field" v-model="selectedValue" :multiple="multiple" class="board-field">
    <!-- Explicit native content can truncate without hiding the picker arrow.
         Dynamic native tags avoid framework nesting rules for older select markup. -->
    <component is="button" v-if="!multiple" type="button" class="board-select__button">
      <component is="selectedcontent" class="board-select__value" />
    </component>
    <slot />
  </select>
</template>
