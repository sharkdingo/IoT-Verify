<script setup lang="ts">
/**
 * The address a user reached does not exist.
 *
 * Rebuilt on the product's own surfaces. It was the last route still made of bare Element Plus
 * defaults — `el-main` / `el-result` / `el-button`, the only such usage left anywhere in the codebase —
 * so it could not follow the theme: `--surface`, `--text` and the accent had no effect on it, and the
 * whole page stayed light while the rest of the product went dark.
 *
 * It also stranded whoever landed here. There was no header, so the single "Back Home" button was the
 * only exit, with no way to reach the workspace, switch language, or even see the product name. A
 * wrong address is the one page a lost user reads carefully, so it should look like the product and
 * offer the two things they actually want: the way in, and the way back.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import PublicHeader from '@/components/common/PublicHeader.vue'
import { useAuth } from '@/stores/auth'

const { t } = useI18n()
const route = useRoute()
const { getToken } = useAuth()

/**
 * A signed-in user wants the workspace; a visitor wants the front door. Offering "go to the board" to
 * someone without a session would only bounce them through the auth redirect.
 */
const isSignedIn = computed(() => Boolean(getToken()))

/**
 * What the user actually typed, when the router can still tell us.
 *
 * `/:catchAll(.*)` redirects here, so by the time this renders the address bar reads `/404` and the
 * original path is lost from `route.path`. It survives in the redirect's query when present. Showing it
 * matters for the common cause — a mistyped or truncated shared link — because "this address does not
 * exist" is unfalsifiable on its own, while seeing the address makes the typo obvious.
 */
const attemptedPath = computed(() => {
  const from = route.query.from
  const value = Array.isArray(from) ? from[0] : from
  if (!value || typeof value !== 'string') return ''
  // Only ever render a same-origin path, never a full URL: this text is user-controlled.
  return value.startsWith('/') && !value.startsWith('//') ? value.slice(0, 120) : ''
})
</script>

<template>
  <div class="not-found">
    <PublicHeader />

    <main class="not-found__body">
      <section class="not-found__card" aria-labelledby="not-found-title">
        <span class="not-found__code" aria-hidden="true">404</span>

        <h1 id="not-found-title" class="not-found__title">{{ t('app.notFound.title') }}</h1>

        <!-- The address itself, when known: the fastest way for someone to spot their own typo. -->
        <p v-if="attemptedPath" class="not-found__path">
          <span class="not-found__path-label">{{ t('app.notFound.attempted') }}</span>
          <code class="not-found__path-value" data-testid="not-found-attempted">{{ attemptedPath }}</code>
        </p>

        <div class="not-found__actions">
          <RouterLink
            v-if="isSignedIn"
            to="/board"
            class="board-action-inline"
            data-testid="not-found-board"
          >
            <span class="material-symbols-outlined text-base" aria-hidden="true">dashboard</span>
            {{ t('app.notFound.workspace') }}
          </RouterLink>
          <RouterLink
            to="/"
            class="not-found__secondary board-action-secondary"
            data-testid="not-found-home"
          >
            <span class="material-symbols-outlined text-base" aria-hidden="true">home</span>
            {{ t('app.notFound.home') }}
          </RouterLink>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.not-found {
  display: flex;
  min-height: 100dvh;
  flex-direction: column;
  background: var(--iot-color-bg-canvas);
  color: var(--text);
}

.not-found__body {
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  padding: 7rem var(--iot-space-xl) var(--iot-space-4xl);
}

.not-found__card {
  display: flex;
  width: 100%;
  max-width: 32rem;
  flex-direction: column;
  align-items: center;
  gap: var(--iot-space-md);
  border: 1px solid var(--border);
  border-radius: var(--iot-radius-surface);
  background: var(--surface);
  padding: clamp(var(--iot-space-xl), 4vw, 2.5rem);
  box-shadow: var(--shadow-elevated);
  text-align: center;
}

/* Large, low-contrast, and `aria-hidden`: it orients at a glance without competing with the sentence
   that actually explains the situation, and it is not read twice to a screen reader. */
.not-found__code {
  font-family: var(--iot-font-display);
  font-size: clamp(4rem, 14vw, 7rem);
  font-weight: 500;
  line-height: 1;
  letter-spacing: -0.04em;
  color: var(--border-strong);
}

.not-found__title {
  margin: 0;
  font-size: 1.5rem;
  font-family: var(--iot-font-display);
  font-weight: 600;
}

.not-found__path {
  display: flex;
  max-width: 100%;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: center;
  gap: var(--iot-space-sm);
  margin: var(--iot-space-2xs) 0 0;
  font-size: var(--iot-font-label);
}

.not-found__path-label {
  color: var(--text-muted);
}

.not-found__path-value {
  max-width: 100%;
  overflow-wrap: anywhere;
  border-radius: var(--iot-radius-control);
  background: var(--surface-elevated);
  border: 1px solid var(--border);
  padding: 0.125rem var(--iot-space-sm);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.not-found__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--iot-space-md);
  margin-top: var(--iot-space-lg);
}

</style>
