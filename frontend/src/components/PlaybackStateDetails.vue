<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TraceDevice, TraceVariable, TraceTriggeredRule } from '@/types/verify'
import {
  formatPlaybackSecurityLabel,
  normalizePlaybackDeviceId,
  playbackDeviceSecurityFacts,
  playbackDeviceStateEntries
} from '@/utils/traceView'

const props = defineProps<{
  devices: TraceDevice[]
  environmentVariables: TraceVariable[]
  compromisedAutomationLinks: TraceTriggeredRule[]
  currentDeviceIds: string[]
  currentRuleIds: string[]
  formatDeviceToken: (device: TraceDevice, value: unknown) => string
  formatEnvironmentToken: (name: string, value: unknown) => string
  environmentTitle?: (name: string, value: string) => string
}>()
const { t } = useI18n()
const currentDevices = computed(() => new Set(props.currentDeviceIds.map(normalizePlaybackDeviceId)))
const currentRules = computed(() => new Set(props.currentRuleIds.map(String)))
const securityDevices = computed(() => props.devices
  .map(device => ({ device, facts: playbackDeviceSecurityFacts(device) }))
  .filter(({ device, facts }) => device.compromised || facts.hasTrustLabels || facts.hasPrivacyLabels))
const securityLabel = (device: TraceDevice, labels: string[]) => labels
  .map(label => formatPlaybackSecurityLabel(label, value => props.formatDeviceToken(device, value))).join(', ')
</script>

<template>
  <div class="playback-terminal__state-details" data-testid="playback-state-details">
    <section v-if="devices.length" class="playback-evidence-group" data-testid="playback-state-devices">
      <h3 class="playback-evidence-group__heading">
        <span class="material-symbols-outlined" aria-hidden="true">devices</span>
        {{ t('app.playbackDeviceReadings') }}<span>{{ devices.length }}</span>
      </h3>
      <div class="playback-terminal__readings">
        <article v-for="device in devices" :key="device.deviceId" class="playback-changes__card">
          <h4 class="playback-evidence__name">
            {{ device.deviceLabel || device.deviceId }}
          </h4>
          <p v-if="!currentDevices.has(normalizePlaybackDeviceId(device.deviceId))" class="playback-evidence__scope">
            {{ t('app.traceVisualization.historicalDeviceNotOnCurrentBoard') }}
          </p>
          <dl class="playback-evidence__values">
            <template v-for="(state, index) in playbackDeviceStateEntries(device)" :key="`state-${index}`">
              <dt>{{ state.mode ? formatDeviceToken(device, state.mode) : t('app.state') }}</dt>
              <dd class="font-mono">{{ state.value ? formatDeviceToken(device, state.value) : t('app.notAvailable') }}</dd>
            </template>
            <template v-for="variable in device.variables.filter(variable => variable.observed !== false)" :key="variable.name">
              <dt>{{ formatDeviceToken(device, variable.name) }}</dt>
              <dd class="font-mono">{{ variable.value?.trim() ? formatDeviceToken(device, variable.value) : t('app.notAvailable') }}</dd>
            </template>
          </dl>
        </article>
      </div>
    </section>
    <section v-if="environmentVariables.length" class="playback-evidence-group" data-testid="playback-state-environment">
      <h3 class="playback-evidence-group__heading">
        <span class="material-symbols-outlined" aria-hidden="true">public</span>
        {{ t('app.playbackActualEnvironment') }}<span>{{ environmentVariables.length }}</span>
      </h3>
      <dl class="playback-evidence__environment">
        <div v-for="variable in environmentVariables" :key="variable.name" :title="environmentTitle?.(variable.name, variable.value)">
          <dt>{{ formatEnvironmentToken(variable.name, variable.name) }}</dt>
          <dd class="font-mono">{{ variable.value?.trim() ? formatEnvironmentToken(variable.name, variable.value) : t('app.notAvailable') }}</dd>
        </div>
      </dl>
    </section>
    <section v-if="securityDevices.length || compromisedAutomationLinks.length" class="playback-evidence-group" data-testid="playback-state-security">
      <h3 class="playback-evidence-group__heading">
        <span class="material-symbols-outlined" aria-hidden="true">shield</span>
        {{ t('app.traceVisualization.securityLabels') }}
      </h3>
      <div class="playback-terminal__readings">
        <article v-for="{ device, facts } in securityDevices" :key="device.deviceId" class="playback-changes__card">
          <h4 class="playback-evidence__name">
            {{ device.deviceLabel || device.deviceId }}
            <span v-if="device.compromised" class="playback-terminal__badge board-chip-danger board-text-danger">
              {{ t('app.traceVisualization.compromised') }}
            </span>
          </h4>
          <dl class="playback-evidence__values">
            <template v-if="facts.hasTrustLabels">
              <dt>{{ t('app.trust') }}</dt>
              <dd>{{ facts.untrustedLabels.length
                ? t('app.playbackUntrustedSources', { labels: securityLabel(device, facts.untrustedLabels) }) : t('app.trusted') }}</dd>
            </template>
            <template v-if="facts.hasPrivacyLabels">
              <dt>{{ t('app.privacy') }}</dt>
              <dd>{{ facts.privateLabels.length
                ? t('app.playbackPrivateValues', { labels: securityLabel(device, facts.privateLabels) }) : t('app.public') }}</dd>
            </template>
          </dl>
        </article>
        <article v-if="compromisedAutomationLinks.length" class="playback-changes__card board-surface-danger" data-testid="playback-state-compromised-links">
          <h4 class="playback-evidence__name board-text-danger">{{ t('app.traceVisualization.compromisedAutomationLinks') }}</h4>
          <ul class="playback-evidence__list">
            <li v-for="(rule, index) in compromisedAutomationLinks" :key="rule.ruleId || index">
              {{ rule.ruleLabel?.trim() || t('app.ruleNumber', { number: index + 1 }) }}
              <span v-if="rule.ruleId && !currentRules.has(String(rule.ruleId))" class="playback-evidence__scope">
                {{ t('app.traceVisualization.historicalRuleNotOnCurrentBoard') }}
              </span>
            </li>
          </ul>
          <p class="board-text-muted">{{ t('app.traceVisualization.compromisedAutomationLinkHint') }}</p>
        </article>
      </div>
    </section>
  </div>
</template>
