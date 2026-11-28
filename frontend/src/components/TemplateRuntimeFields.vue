<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import FieldSelect from '@/components/common/FieldSelect.vue'
import type { DeviceTemplate, InternalVariable, WorkingState } from '@/types/device'
import {
  PRIVACY_OPTIONS,
  TRUST_OPTIONS,
  findTemplateStatePrivacy,
  findTemplateStateTrust,
  formatDeviceRuntimeVariablePlaceholder,
  getTemplateVariableDefaultValue,
  templateVariableHasEnumValues,
  templateVariableIsStateDerived,
  templateVariableUsesNumericBounds,
  type DeviceRuntimeDraft
} from '@/utils/deviceRuntime'

const props = defineProps<{
  variant: 'single-device' | 'template-instance'
  template: DeviceTemplate | null
  // Both callers retain ownership of their reactive draft throughout a save or recovery.
  draft: DeviceRuntimeDraft
  workingStates: readonly WorkingState[]
  variables: readonly InternalVariable[]
  formatToken: (template: DeviceTemplate | null, value: unknown) => string
  valueFailures?: Readonly<Record<string, string>>
}>()
const emit = defineEmits<{ 'value-input': [event: Event, name: string] }>()
const { t } = useI18n()
const manager = computed(() => props.variant === 'single-device')
const displayToken = (value: unknown) => props.formatToken(props.template, value)
const stateOptions = computed(() => props.workingStates.map(state => ({
  name: state.Name,
  label: displayToken(state.Name)
})))
const variableRows = computed(() => props.variables.map(variable => ({
  variable,
  name: variable.Name,
  label: displayToken(variable.Name),
  followsState: templateVariableIsStateDerived(props.template, variable.Name),
  choices: templateVariableHasEnumValues(variable) ? {
    defaultLabel: displayToken(getTemplateVariableDefaultValue(variable, props.template, props.draft.state)),
    options: variable.Values!.map(value => ({ raw: String(value), label: displayToken(value) }))
  } : null
})))

const valueFailureId = (name: string) => `${props.variant}-value-error-${encodeURIComponent(name)}`
const valueFailureAttributes = (name: string) => props.valueFailures?.[name]
  ? { 'aria-invalid': 'true' as const, 'aria-describedby': valueFailureId(name) } : {}
</script>

<template>
  <div v-if="stateOptions.length" :class="manager ? 'board-field-grid mt-3' : 'mt-3 grid grid-cols-1 gap-2 sm:grid-cols-3'">
    <label class="min-w-0">
      <span class="board-form-label mb-1 block">{{ t('app.initialState') }}</span>
      <FieldSelect v-model="draft.state" :data-testid="`${variant}-state`">
        <option v-for="state in stateOptions" :key="state.name" :value="state.name">{{ state.label }}</option>
      </FieldSelect>
    </label>

    <label class="min-w-0">
      <span class="board-form-label mb-1 block">{{ t('app.stateTrust') }}</span>
      <FieldSelect v-model="draft.currentStateTrust" :data-testid="`${variant}-state-trust`">
        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStateTrust(template, draft.state) || 'trusted'}`) }) }}</option>
        <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
      </FieldSelect>
    </label>

    <label class="min-w-0">
      <span class="board-form-label mb-1 block">{{ t('app.statePrivacy') }}</span>
      <FieldSelect v-model="draft.currentStatePrivacy" :data-testid="`${variant}-state-privacy`">
        <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${findTemplateStatePrivacy(template, draft.state) || 'public'}`) }) }}</option>
        <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
      </FieldSelect>
    </label>
  </div>

  <div v-if="variableRows.length" class="mt-3 space-y-2">
    <div v-for="row in variableRows" :key="row.name" class="rounded-lg border board-border-subtle p-2"
      :class="manager ? 'board-bg-muted' : 'board-bg-elevated'">
      <div class="mb-2 flex flex-wrap items-start justify-between gap-x-3 gap-y-1">
        <span class="max-w-full break-words font-semibold board-text-strong" :class="manager ? 'text-xs' : 'text-sm'" :title="row.label">{{ row.label }}</span>
        <span v-if="templateVariableUsesNumericBounds(row.variable)" class="max-w-full break-words font-semibold board-text-muted"
          :class="manager ? 'text-xs' : 'text-[length:var(--iot-font-min)]'">
          {{ formatDeviceRuntimeVariablePlaceholder(row.variable, t) }}
        </span>
      </div>

      <div :class="manager ? 'board-field-grid' : 'grid grid-cols-[minmax(0,1fr)_5.8rem_5.8rem] gap-2 max-[520px]:grid-cols-1'">
        <label class="min-w-0">
          <span class="board-form-label mb-1 block">{{ t('app.variableValue') }}</span>
          <!-- A variable every state constrains is a consequence of the state. -->
          <div v-if="row.followsState" :data-testid="`${variant}-variable-derived-${row.name}`"
            class="flex min-w-0 items-center gap-2 rounded-lg border border-dashed board-border-subtle board-bg-muted px-2 py-1.5">
            <span class="min-w-0 break-words text-xs font-medium board-text-strong">{{ displayToken(draft.variables[row.name]) }}</span>
            <span class="shrink-0 board-text-muted" :class="manager ? 'text-xs' : 'text-[length:var(--iot-font-min)]'">{{ t('app.variableFollowsState') }}</span>
          </div>
          <FieldSelect v-else-if="row.choices" v-model="draft.variables[row.name]" :data-testid="`${variant}-variable-${row.name}`"
            v-bind="valueFailureAttributes(row.name)">
            <option value="">{{ t('app.useTemplateDefaultWithValue', { value: row.choices.defaultLabel }) }}</option>
            <option v-for="option in row.choices.options" :key="option.raw" :value="option.raw">{{ option.label }}</option>
          </FieldSelect>
          <input v-else v-model="draft.variables[row.name]" class="board-field" type="text" :data-testid="`${variant}-variable-${row.name}`"
            :placeholder="formatDeviceRuntimeVariablePlaceholder(row.variable, t)" v-bind="valueFailureAttributes(row.name)"
            @input="emit('value-input', $event, row.name)" />
          <span v-if="valueFailures?.[row.name]" :id="valueFailureId(row.name)" :data-testid="`${variant}-value-error-${row.name}`"
            class="mt-1 block text-sm board-text-danger" role="alert">
            {{ valueFailures[row.name] }}
          </span>
        </label>

        <label class="min-w-0">
          <span class="board-form-label mb-1 block">{{ t('app.variableTrust') }}</span>
          <FieldSelect v-model="draft.variableTrusts[row.name]" :data-testid="`${variant}-variable-trust-${row.name}`">
            <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${row.variable.Trust || 'trusted'}`) }) }}</option>
            <option v-for="trust in TRUST_OPTIONS" :key="trust" :value="trust">{{ t(`app.${trust}`) }}</option>
          </FieldSelect>
        </label>

        <label class="min-w-0">
          <span class="board-form-label mb-1 block">{{ t('app.privacy') }}</span>
          <FieldSelect v-model="draft.privacies[row.name]" :data-testid="`${variant}-privacy-${row.name}`">
            <option value="">{{ t('app.useTemplateDefaultWithValue', { value: t(`app.${row.variable.Privacy || 'public'}`) }) }}</option>
            <option v-for="privacy in PRIVACY_OPTIONS" :key="privacy" :value="privacy">{{ t(`app.${privacy}`) }}</option>
          </FieldSelect>
        </label>
      </div>
    </div>
  </div>
</template>
