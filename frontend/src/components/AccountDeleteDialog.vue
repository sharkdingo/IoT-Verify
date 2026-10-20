<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import { normalizeAccountIdentifier } from '@/utils/accountIdentifier'
import FailureDetails from '@/components/FailureDetails.vue'
import { technicalErrorLines, type ApiErrorDetails } from '@/utils/userMessage'

const { t } = useI18n()

const props = withDefaults(defineProps<{
  visible: boolean
  username?: string
  phone?: string
  loading?: boolean
  failure?: { message: string; diagnostics: ApiErrorDetails } | null
}>(), {
  username: '',
  phone: '',
  loading: false
})

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'confirm': [payload: { password: string; confirmation: string }]
  'cancel': []
  'clear-failure': []
}>()

const form = reactive({
  confirmation: '',
  password: ''
})
const confirmationWasEdited = ref(false)

const confirmationHint = computed(() => t('app.deleteAccountConfirmationHint'))
const confirmationMatches = computed(() => {
  const value = normalizeAccountIdentifier(form.confirmation)
  return value.length > 0 && (value === props.username || value === props.phone)
})
const canConfirm = computed(() => confirmationWasEdited.value
  && confirmationMatches.value
  && form.password.length > 0
  && !props.loading)

/**
 * Requires the confirmation text to have been entered through a real edit, so a password manager
 * filling the field cannot satisfy the typed-confirmation gate on its own.
 *
 * Keyed on `InputEvent.inputType` rather than on preceding keystrokes: every genuine edit reports
 * one — including Android soft keyboards, which report `KeyboardEvent.key` as `Unidentified`, and
 * dropped text, which fires no keydown at all. Gating on printable keys left the delete button
 * permanently disabled in both cases, with nothing on screen explaining why. Programmatic value
 * assignment fires a plain `Event` with no `inputType`, which is what this rejects.
 */
const confirmConfirmationEdited = (event: Event) => {
  emit('clear-failure')
  if (event instanceof InputEvent && event.inputType) confirmationWasEdited.value = true
}

const resetForm = () => {
  form.confirmation = ''
  form.password = ''
  confirmationWasEdited.value = false
}

const handleCancel = () => {
  if (props.loading) return
  emit('cancel')
  emit('update:visible', false)
  emit('clear-failure')
  resetForm()
}

const handleConfirm = () => {
  if (!canConfirm.value) return
  emit('confirm', {
    password: form.password,
    confirmation: normalizeAccountIdentifier(form.confirmation)
  })
}

watch(() => props.visible, () => {
  resetForm()
})

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
        <form
          :ref="setDialogRef"
          class="iot-dialog iot-dialog--md iot-dialog--danger account-delete-dialog"
          data-testid="account-delete-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="account-delete-title"
          tabindex="-1"
          @submit.prevent="handleConfirm"
        >
          <div class="iot-dialog__header">
            <div class="iot-dialog__icon">
              <span class="material-symbols-outlined" aria-hidden="true">person_remove</span>
            </div>
            <div class="iot-dialog__heading">
              <h2 id="account-delete-title" class="iot-dialog__title">{{ t('app.deleteAccountTitle') }}</h2>
            </div>
          </div>

          <div class="iot-dialog__body">
          <div class="account-delete-warning">
            <span class="material-symbols-outlined" aria-hidden="true">warning</span>
            <span>{{ t('app.deleteAccountDataWarning') }}</span>
          </div>

          <p class="account-delete-export-note" data-testid="account-delete-export-note">
            <span class="material-symbols-outlined" aria-hidden="true">download</span>
            <span>{{ t('app.deleteAccountExportHint') }}</span>
          </p>

          <label class="account-delete-field board-form-label">
            <span>{{ t('app.deleteAccountConfirmationLabel') }}</span>
            <input
              class="board-field mt-1.5"
              v-model="form.confirmation"
              type="text"
              autocomplete="off"
              name="delete-account-confirmation"
              data-testid="account-delete-confirmation"
              data-1p-ignore
              data-lpignore="true"
              :aria-invalid="Boolean(form.confirmation) && !confirmationMatches"
              aria-describedby="account-delete-confirmation-hint"
              :disabled="loading"
              @input="confirmConfirmationEdited"
            >
            <small id="account-delete-confirmation-hint" :class="{ danger: form.confirmation && !confirmationMatches }">
              {{ confirmationHint }}
            </small>
          </label>

          <label class="account-delete-field board-form-label">
            <span>{{ t('account.currentPassword') }}</span>
            <input
              class="board-field mt-1.5"
              v-model="form.password"
              type="password"
              autocomplete="current-password"
              data-testid="account-delete-password"
              :disabled="loading"
              @input="emit('clear-failure')"
            >
          </label>

          <div v-if="failure" class="account-delete-rejection" role="alert" data-testid="account-delete-rejection">
            <p>{{ failure.message }}</p>
            <FailureDetails :message="failure.message" :details="technicalErrorLines(failure.diagnostics)" suggestion="" />
          </div>

          </div>

          <!-- Test ids on the controls of the product's one irreversible action.
               They were absent, so nothing outside this component could address the dialog: a browser check
               looking for the delete flow found no route to it and reported the affordance missing. A
               destructive action is the last thing that should be unaddressable by a test. -->
          <div class="iot-dialog__footer">
            <button
              type="button"
              class="iot-dialog-btn iot-dialog-btn--ghost"
              data-testid="account-delete-cancel"
              :disabled="loading"
              @click="handleCancel"
            >
              {{ t('app.cancel') }}
            </button>
            <button
              type="submit"
              class="iot-dialog-btn iot-dialog-btn--danger account-delete-confirm"
              data-testid="account-delete-confirm"
              :disabled="!canConfirm"
              :aria-busy="loading ? 'true' : undefined"
            >
              <span v-if="loading" class="iot-dialog-btn__spinner" aria-hidden="true"></span>
              <span>{{ loading ? t('app.deleting') : t('app.deleteAccountConfirm') }}</span>
            </button>
          </div>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* Shell, header, footer and buttons come from styles/dialog.css. What stays here is specific to the
   product's one irreversible action: the warning/export pair, the typed-confirmation fields, and a
   disabled treatment that has to read as disarmed. */

/* A left rule rather than a full box, matching `.iot-dialog__consequence`: two bordered cards stacked on a
   third bordered surface (the dialog) gave three nested frames in 200px of height, which is what made this
   read as cramped rather than serious. The rule carries the same tone with one less frame. */
.account-delete-warning {
  display: flex;
  gap: 0.5rem;
  padding: 0.625rem 0.75rem;
  border-left: 2px solid var(--danger-border);
  border-radius: 0 var(--iot-radius-control) var(--iot-radius-control) 0;
  background: var(--danger-surface);
  color: var(--danger);
  font-size: var(--iot-font-control);
  line-height: 1.5;
}

.account-delete-warning .material-symbols-outlined {
  flex: 0 0 auto;
  font-size: 1.1rem;
}

/* The export note sits below the warning and is deliberately quieter than it: it is a way out, not another
   alarm. Two danger-coloured blocks stacked would make neither read as the more urgent. */
.account-delete-export-note {
  display: flex;
  gap: 0.5rem;
  align-items: flex-start;
  margin: 0.5rem 0 0;
  padding: 0.625rem 0.75rem;
  border-left: 2px solid var(--border-strong);
  border-radius: 0 var(--iot-radius-control) var(--iot-radius-control) 0;
  background: var(--surface-muted);
  color: var(--text-muted);
  font-size: var(--iot-font-control);
  line-height: 1.5;
}

.account-delete-export-note .material-symbols-outlined {
  flex: 0 0 auto;
  font-size: 1.05rem;
}

.account-delete-field {
  display: block;
  margin-top: var(--iot-space-lg);
}

.account-delete-field small {
  display: block;
  margin-top: 0.35rem;
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
  font-weight: 400;
  line-height: 1.5;
}

.account-delete-field small.danger {
  color: var(--danger);
}

.account-delete-rejection {
  color: var(--danger);
  font-size: var(--iot-font-label);
  line-height: 1.6;
}

/* A disarmed destructive button must look disarmed.
 *
 * `opacity: 0.58` alone left the danger button saturated red *and* still wearing its glow, so at 58% it
 * read as armed. All three reviews of this dialog said so — "'Delete Permanently' appears enabled even
 * though both confirmation fields are empty" — while measurement showed `disabled: true` in every case.
 * The button was correct and its appearance was not, which is the worse of the two failures on the
 * product's only irreversible action: a user who believes the control is live cannot tell whether their
 * click was ignored or is about to destroy their work.
 *
 * The shared `--danger:disabled` rule swaps in the neutral disabled fill, which already reads as
 * disarmed. This override keeps that decision *specific* to this dialog rather than relying on it: the
 * fill is mixed toward the surface so it stops reading as a live danger colour at all. */
/* Mixing danger 34% into a white surface produced a pink wash carrying white ink — measured illegible, and
 * it read as a decorative button rather than a disabled one. The shared neutral disabled fill says
 * "unavailable" through desaturation and keeps its label readable, which is the whole point of the rule
 * above; the only thing worth keeping local is dropping the elevation, since a disabled control should not
 * appear to sit above the footer. */
.account-delete-confirm:disabled {
  box-shadow: none;
}
</style>
