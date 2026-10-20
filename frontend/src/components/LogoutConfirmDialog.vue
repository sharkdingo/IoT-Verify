<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useModalAccessibility } from '@/composables/useModalAccessibility'

const { t } = useI18n()

const props = withDefaults(defineProps<{
  visible: boolean
  loading?: boolean
}>(), {
  loading: false
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'confirm': []
  'cancel': []
}>()

const handleConfirm = () => {
  if (props.loading) return
  emit('confirm')
}

const handleCancel = () => {
  if (props.loading) return
  emit('cancel')
  emit('update:visible', false)
}

const isDialogOpen = computed(() => props.visible)
const { setDialogRef, handleModalKeydown } = useModalAccessibility(isDialogOpen, handleCancel)
</script>

<template>
  <Teleport to="body">
    <Transition name="iot-dialog">
      <div
        v-if="visible"
        class="iot-dialog-overlay iot-dialog-overlay--session"
        @click.self="handleCancel"
        @keydown="handleModalKeydown"
      >
        <div
          :ref="setDialogRef"
          class="iot-dialog iot-dialog--sm"
          data-testid="logout-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="logout-dialog-title"
          tabindex="-1"
        >
          <div class="iot-dialog__header">
            <div class="iot-dialog__icon">
              <span class="material-symbols-outlined" aria-hidden="true">logout</span>
            </div>
            <div class="iot-dialog__heading">
              <h2 id="logout-dialog-title" class="iot-dialog__title">{{ t('app.logoutTitle') }}</h2>
            </div>
          </div>

          <div class="iot-dialog__body">{{ t('account.sessionDescription') }}</div>

          <div class="iot-dialog__footer">
            <button type="button" class="iot-dialog-btn iot-dialog-btn--ghost" data-testid="logout-cancel" autofocus @click="handleCancel" :disabled="loading">
              {{ t('app.cancel') }}
            </button>
            <button type="button" class="iot-dialog-btn iot-dialog-btn--primary" data-testid="logout-confirm" @click="handleConfirm" :disabled="loading" :aria-busy="loading ? 'true' : undefined">
              <span v-if="loading" class="iot-dialog-btn__spinner" aria-hidden="true"></span>
              <span>{{ loading ? t('account.signingOut') : t('app.logout') }}</span>
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
