<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { useI18n } from 'vue-i18n'
import TopologyShowcase from '@/components/common/TopologyShowcase.vue'
import PublicHeader from '@/components/common/PublicHeader.vue'
import { authApi } from '@/api/auth'
import { useAuth } from '@/stores/auth'
import { errorDetails, technicalErrorLines, type ApiErrorDetails } from '@/utils/userMessage'
import FailureDetails from '@/components/FailureDetails.vue'
import { isValidNormalizedUsername, normalizeAccountIdentifier } from '@/utils/accountIdentifier'
import { CREDENTIAL_LIMITS } from '@/constants/requestLimits'
import HintTooltip from '@/components/common/HintTooltip.vue'

type AuthMode = 'login' | 'register'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const auth = useAuth()
const { login } = auth

const loading = ref(false)
const requestError = shallowRef<{ key: string; error?: unknown } | null>(null)
const requestDiagnostics = shallowRef<ApiErrorDetails | null>(null)
const workspaceNavigation = shallowRef<{ token: string; target: RouteLocationRaw } | null>(null)
let disposed = false
let authRequestEpoch = 0
const requestErrorRef = ref<HTMLElement | null>(null)
const loginIdentifierRef = ref<HTMLInputElement | null>(null)
const loginPasswordRef = ref<HTMLInputElement | null>(null)
const registerPhoneRef = ref<HTMLInputElement | null>(null)
const registerUsernameRef = ref<HTMLInputElement | null>(null)
const registerPasswordRef = ref<HTMLInputElement | null>(null)
const confirmPasswordRef = ref<HTMLInputElement | null>(null)
const authMode = ref<AuthMode>(route.query.mode === 'register' ? 'register' : 'login')
const showAuthPanel = ref(Boolean(route.query.mode || route.query.redirect))

const workflowSteps = [
  { number: '01', title: 'app.landingModelTitle', description: 'app.landingModelDescription' },
  { number: '02', title: 'app.landingCheckTitle', description: 'app.landingCheckDescription' },
  { number: '03', title: 'app.landingTraceTitle', description: 'app.landingTraceDescription' }
]

const loginForm = reactive({
  identifier: '',
  password: ''
})

const registerForm = reactive({
  phone: '',
  username: '',
  password: '',
  confirmPassword: ''
})

const formErrors = reactive<Record<string, string>>({})

const isRegisterMode = computed(() => authMode.value === 'register')
const panelTitle = computed(() => isRegisterMode.value ? t('auth.register') : t('auth.login'))
const accountDeletionUnknown = computed(() => route.query.reason === 'account-deletion-unknown' && !isRegisterMode.value)
// loginRedirectTarget owns the expiry reason; this surface only reports it.
const sessionExpired = computed(() => route.query.reason === 'session-expired' && !isRegisterMode.value)
const passwordChangeNotice = computed(() => {
  if (isRegisterMode.value) return ''
  return route.query.reason === 'password-changed'
    ? t('account.passwordChangedNotice')
    : route.query.reason === 'password-change-unknown'
      ? t('account.passwordChangeUnknownNotice')
      : ''
})
const loadingLabel = computed(() => isRegisterMode.value ? t('auth.creatingAccount') : t('auth.signingIn'))
// Only same-origin absolute paths are honoured, and the login-surface parameters are
// stripped so a successful sign-in always lands on a clean workspace URL instead of
// carrying `?mode=`/`?redirect=` into the board.
const redirectTarget = computed(() => {
  const redirect = route.query.redirect
  if (typeof redirect !== 'string' || !redirect.startsWith('/') || redirect.startsWith('//')) {
    return '/board'
  }
  const resolved = router.resolve(redirect)
  if (resolved.name === '404' || resolved.path === '/') return '/board'
  const { mode: _mode, redirect: _redirect, ...query } = resolved.query
  return { path: resolved.path, query, hash: resolved.hash }
})

watch(
  () => [route.query.mode, route.query.redirect],
  ([mode, redirect]) => {
    // Browser navigation abandons the form intent even when Landing stays mounted.
    authRequestEpoch += 1
    loading.value = false
    authMode.value = mode === 'register' ? 'register' : 'login'
    showAuthPanel.value = Boolean(mode || redirect)
    clearErrors()
  }
)

const clearErrors = () => {
  for (const key of Object.keys(formErrors)) {
    delete formErrors[key]
  }
  requestError.value = null
  requestDiagnostics.value = null
  workspaceNavigation.value = null
}

const clearFieldError = (key: string) => {
  delete formErrors[key]
  requestError.value = null
  requestDiagnostics.value = null
}

const focusRequestError = async () => {
  await nextTick()
  requestErrorRef.value?.focus()
}

const openAuthenticatedWorkspace = async (token: string, target: RouteLocationRaw) => {
  const navigation = { token, target }
  workspaceNavigation.value = navigation
  try {
    await router.push(target)
  } catch (error) {
    if (disposed || workspaceNavigation.value !== navigation || !auth.isCurrentSessionToken(token)) return
    requestError.value = { key: 'auth.workspaceOpenFailed' }
    requestDiagnostics.value = { details: errorDetails(error).details, fields: {} }
    await focusRequestError()
  }
}

const retryWorkspace = () => {
  const navigation = workspaceNavigation.value
  if (disposed || loading.value || !navigation || !auth.isCurrentSessionToken(navigation.token)) return
  loading.value = true
  // A failed native module import remains cached for this document.
  window.history.replaceState(null, '', router.resolve(navigation.target).href)
  window.location.reload()
}

watch(() => auth.state.token, () => {
  loading.value = false
  requestError.value = null
  requestDiagnostics.value = null
  if (workspaceNavigation.value && !auth.isCurrentSessionToken(workspaceNavigation.value.token)) {
    workspaceNavigation.value = null
  }
}, { flush: 'sync' })
onBeforeUnmount(() => { disposed = true })

const closeAuthPanel = () => {
  if (loading.value || workspaceNavigation.value) return
  showAuthPanel.value = false
  clearErrors()
  void router.replace({ path: '/' })
}

const setAuthMode = (mode: AuthMode) => {
  if (loading.value || workspaceNavigation.value) return
  showAuthPanel.value = true
  if (authMode.value === mode && route.query.mode === mode) return
  const query = { ...route.query, mode }
  authMode.value = mode
  clearErrors()
  router.replace({ path: '/', query })
}

const setAuthModeFromKeyboard = async (mode: AuthMode) => {
  setAuthMode(mode)
  await nextTick()
  document.getElementById(`auth-tab-${mode}`)?.focus()
}

const openAuthPanel = () => {
  setAuthMode('login')
}

// credentialLimitsMirror.spec.ts checks this shared limit against RequestLimits.PHONE_PATTERN.
const validatePhone = (phone: string, key: string) => {
  if (!phone.trim()) {
    formErrors[key] = 'auth.phoneRequired'
    return false
  }
  if (!CREDENTIAL_LIMITS.phonePattern.test(phone.trim())) {
    formErrors[key] = 'auth.phoneInvalid'
    return false
  }
  return true
}

const validateLogin = () => {
  clearErrors()
  if (!normalizeAccountIdentifier(loginForm.identifier)) {
    formErrors.loginIdentifier = 'auth.accountRequired'
  }
  if (!loginForm.password) {
    formErrors.loginPassword = 'auth.passwordRequired'
  }
  const valid = !formErrors.loginIdentifier && !formErrors.loginPassword
  if (!valid) {
    void nextTick(() => {
      if (formErrors.loginIdentifier) loginIdentifierRef.value?.focus()
      else loginPasswordRef.value?.focus()
    })
  }
  return valid
}

const validateRegister = () => {
  clearErrors()
  const validPhone = validatePhone(registerForm.phone, 'registerPhone')
  const username = normalizeAccountIdentifier(registerForm.username)
  const usernameLength = Array.from(username).length

  if (!username) {
    formErrors.registerUsername = 'auth.usernameRequired'
  } else if (usernameLength < CREDENTIAL_LIMITS.minUsernameDisplayLength
      || usernameLength > CREDENTIAL_LIMITS.maxUsernameDisplayLength) {
    formErrors.registerUsername = 'auth.usernameLength'
  } else if (!isValidNormalizedUsername(username)) {
    formErrors.registerUsername = 'auth.usernameInvalidCharacters'
  } else if (CREDENTIAL_LIMITS.phonePattern.test(username)) {
    formErrors.registerUsername = 'account.usernamePhoneReserved'
  }

  if (!registerForm.password) {
    formErrors.registerPassword = 'auth.passwordRequired'
  } else if (registerForm.password.length < CREDENTIAL_LIMITS.minPasswordLength
      || registerForm.password.length > CREDENTIAL_LIMITS.maxPasswordLength
      || new TextEncoder().encode(registerForm.password).length > CREDENTIAL_LIMITS.maxPasswordBcryptBytes) {
    formErrors.registerPassword = 'auth.passwordLength'
  }

  if (!registerForm.confirmPassword) {
    formErrors.confirmPassword = 'auth.confirmPasswordRequired'
  } else if (registerForm.confirmPassword !== registerForm.password) {
    formErrors.confirmPassword = 'auth.passwordMismatch'
  }

  const valid = validPhone &&
    !formErrors.registerUsername &&
    !formErrors.registerPassword &&
    !formErrors.confirmPassword
  if (!valid) {
    void nextTick(() => {
      if (formErrors.registerPhone) registerPhoneRef.value?.focus()
      else if (formErrors.registerUsername) registerUsernameRef.value?.focus()
      else if (formErrors.registerPassword) registerPasswordRef.value?.focus()
      else confirmPasswordRef.value?.focus()
    })
  }
  return valid
}

const authRequestErrorMessage = (error: any, fallbackKey: string) => {
  const status = Number(error?.response?.status ?? error?.response?.data?.code)
  const data = error?.response?.data?.data
  if (status === 429
    && typeof data?.reasonCode === 'string'
    && data.reasonCode.startsWith('AUTH_')) {
    const seconds = Number(data.retryAfterSeconds)
    const messageKey = data.scope === 'CAPACITY'
      ? 'auth.rateLimitCapacityReached'
      : 'auth.rateLimitReached'
    return Number.isInteger(seconds) && seconds > 0
      ? t(messageKey, { seconds })
      : t(data.scope === 'CAPACITY' ? 'auth.rateLimitCapacityReachedGeneric' : 'auth.rateLimitReachedGeneric')
  }
  if (fallbackKey === 'auth.loginFailed' && status === 401) return t('auth.invalidCredentials')
  if (fallbackKey === 'auth.registerFailed' && (!Number.isInteger(status) || status >= 500)) {
    return t('auth.registerOutcomeUnknown')
  }
  return t(fallbackKey)
}
const requestErrorMessage = computed(() => requestError.value
  ? authRequestErrorMessage(requestError.value.error, requestError.value.key) : '')

const handleLogin = async () => {
  if (loading.value || workspaceNavigation.value) return
  if (!validateLogin()) return
  const requestEpoch = ++authRequestEpoch
  let requestToken = auth.getToken()
  const requestCurrent = () => !disposed && requestEpoch === authRequestEpoch && auth.isCurrentSessionToken(requestToken)

  requestError.value = null
  requestDiagnostics.value = null
  loading.value = true
  try {
    const res = await authApi.login({
      identifier: normalizeAccountIdentifier(loginForm.identifier),
      password: loginForm.password
    })
    if (!requestCurrent()) return

    if (res.code === 200) {
      if (!res.data) {
        throw new Error(t('auth.loginFailed'))
      }
      requestToken = res.data.token
      login(res.data.token, {
        userId: res.data.userId,
        phone: res.data.phone,
        username: res.data.username
      })
      loading.value = true
      await openAuthenticatedWorkspace(res.data.token, redirectTarget.value)
    } else {
      requestError.value = { key: 'auth.loginFailed', error: { response: { data: res } } }
      requestDiagnostics.value = errorDetails({ response: { data: res } })
      await focusRequestError()
    }
  } catch (error: any) {
    if (!requestCurrent()) return
    requestError.value = { key: 'auth.loginFailed', error }
    requestDiagnostics.value = errorDetails(error)
    await focusRequestError()
  } finally {
    if (requestCurrent()) loading.value = false
  }
}

const handleRegister = async () => {
  if (loading.value || workspaceNavigation.value) return
  if (!validateRegister()) return
  const requestEpoch = ++authRequestEpoch
  let requestToken = auth.getToken()
  const requestCurrent = () => !disposed && requestEpoch === authRequestEpoch && auth.isCurrentSessionToken(requestToken)

  requestError.value = null
  requestDiagnostics.value = null
  loading.value = true
  try {
    const res = await authApi.register({
      phone: registerForm.phone.trim(),
      username: normalizeAccountIdentifier(registerForm.username),
      password: registerForm.password
    })
    if (!requestCurrent()) return

    if (res.code === 200) {
      if (!res.data) {
        throw new Error(t('auth.registerFailed'))
      }
      requestToken = res.data.token
      login(res.data.token, {
        userId: res.data.userId,
        phone: res.data.phone,
        username: res.data.username
      })
      loading.value = true
      await openAuthenticatedWorkspace(res.data.token, redirectTarget.value)
    } else {
      requestError.value = { key: 'auth.registerFailed', error: { response: { data: res } } }
      requestDiagnostics.value = errorDetails({ response: { data: res } })
      await focusRequestError()
    }
  } catch (error: any) {
    if (!requestCurrent()) return
    requestDiagnostics.value = errorDetails(error)
    if (requestDiagnostics.value.reasonCode === 'PHONE_ALREADY_EXISTS') {
      formErrors.registerPhone = 'auth.phoneAlreadyExists'
    } else if (requestDiagnostics.value.reasonCode === 'USERNAME_ALREADY_EXISTS') {
      formErrors.registerUsername = 'account.usernameExists'
    } else {
      requestError.value = { key: 'auth.registerFailed', error }
      await focusRequestError()
    }
  } finally {
    if (requestCurrent()) {
      loading.value = false
      await nextTick()
      if (requestCurrent()) {
        if (formErrors.registerPhone) registerPhoneRef.value?.focus()
        else if (formErrors.registerUsername) registerUsernameRef.value?.focus()
      }
    }
  }
}
</script>

<template>
  <div class="landing-wrapper">
    <PublicHeader />

    <main class="hero-section" :class="{ 'hero-section--with-auth': showAuthPanel }">
      <section v-if="!showAuthPanel" class="hero-copy" aria-labelledby="landing-title">
        <p class="hero-eyebrow">{{ t('app.landingEyebrow') }}</p>
        <h1 id="landing-title" class="hero-title">
          {{ t('app.landingHeroLead') }}
          <span class="emphasis">{{ t('app.landingHeroEmphasis') }}</span>
        </h1>
        <p class="hero-subtext">{{ t('app.landingHeroSubtext') }}</p>
        <button class="hero-cta" type="button" @click="openAuthPanel">
          {{ t('app.landingOpenWorkspace') }}
          <span class="material-symbols-outlined" aria-hidden="true">arrow_forward</span>
        </button>
        <ol class="hero-workflow">
          <li v-for="step in workflowSteps" :key="step.number">
            <span class="hero-workflow__number" aria-hidden="true">{{ step.number }}</span>
            <div>
              <h2>{{ t(step.title) }}</h2>
              <p>{{ t(step.description) }}</p>
            </div>
          </li>
        </ol>
      </section>

      <div class="landing-stage">
        <TopologyShowcase v-if="!showAuthPanel" />
        <section
          v-if="showAuthPanel"
          class="auth-panel"
          :aria-busy="loading"
          :aria-labelledby="isRegisterMode ? 'register-title' : 'login-title'"
        >
          <div class="auth-panel__topbar">
            <HintTooltip :content="t('auth.backToOverview')">
              <button
                type="button"
                class="auth-panel__back"
                :aria-label="t('auth.backToOverview')"
                :disabled="loading || Boolean(workspaceNavigation)"
                @click="closeAuthPanel"
              >
                <span class="material-symbols-outlined" aria-hidden="true">arrow_back</span>
              </button>
            </HintTooltip>
            <div class="auth-tabs" role="tablist" :aria-label="t('auth.getStarted')">
              <button
                id="auth-tab-login"
                type="button"
                role="tab"
                aria-controls="login-panel"
                :aria-selected="!isRegisterMode"
                :tabindex="isRegisterMode ? -1 : 0"
                :disabled="loading || Boolean(workspaceNavigation)"
                :class="{ active: !isRegisterMode }"
                @click="setAuthMode('login')"
                @keydown.right.prevent="setAuthModeFromKeyboard('register')"
              >
                {{ t('auth.login') }}
              </button>
              <button
                id="auth-tab-register"
                type="button"
                role="tab"
                aria-controls="register-panel"
                :aria-selected="isRegisterMode"
                :tabindex="isRegisterMode ? 0 : -1"
                :disabled="loading || Boolean(workspaceNavigation)"
                :class="{ active: isRegisterMode }"
                @click="setAuthMode('register')"
                @keydown.left.prevent="setAuthModeFromKeyboard('login')"
              >
                {{ t('auth.register') }}
              </button>
            </div>
            <span aria-hidden="true"></span>
          </div>

          <div class="auth-heading">
            <h1 :id="isRegisterMode ? 'register-title' : 'login-title'">{{ panelTitle }}</h1>
          </div>

          <!-- Session expiry is distinct from a failed sign-in attempt. -->
          <p v-if="accountDeletionUnknown" class="auth-session-notice" role="status" data-testid="auth-account-deletion-notice">
            <span class="material-symbols-outlined" aria-hidden="true">warning</span>
            <span>{{ t('app.deleteAccountOutcomeUnknown') }}</span>
          </p>
          <p
            v-if="sessionExpired"
            class="auth-session-notice"
            role="status"
            data-testid="auth-session-expired"
          >
            <span class="material-symbols-outlined" aria-hidden="true">schedule</span>
            <span>{{ t('auth.sessionExpiredNotice') }}</span>
          </p>

          <p
            v-if="passwordChangeNotice"
            class="auth-session-notice"
            role="status"
            data-testid="auth-password-change-notice"
          >
            <span class="material-symbols-outlined" aria-hidden="true">lock_reset</span>
            <span>{{ passwordChangeNotice }}</span>
          </p>

          <div
            v-if="requestError"
            ref="requestErrorRef"
            class="auth-request-error"
            role="alert"
            tabindex="-1"
          >
            <span class="material-symbols-outlined" aria-hidden="true">error</span>
            <div>
              <p>{{ requestErrorMessage }}</p>
              <FailureDetails v-if="requestDiagnostics" :message="requestErrorMessage" :details="technicalErrorLines(requestDiagnostics)" suggestion="" />
              <button v-if="workspaceNavigation" type="button" class="auth-submit"
                data-testid="auth-workspace-retry" :disabled="loading" @click="retryWorkspace">{{ t('auth.retryWorkspace') }}</button>
            </div>
          </div>

          <form
            v-if="!workspaceNavigation && !isRegisterMode"
            id="login-panel"
            class="auth-form"
            role="tabpanel"
            aria-labelledby="auth-tab-login"
            @submit.prevent="handleLogin"
          >
            <label>
              <span id="login-account-label">{{ t('auth.account') }}</span>
              <input
                class="board-field"
                ref="loginIdentifierRef"
                v-model="loginForm.identifier"
                type="text"
                autocomplete="username"
                aria-labelledby="login-account-label"
                :disabled="loading"
                :aria-invalid="Boolean(formErrors.loginIdentifier)"
                :aria-describedby="formErrors.loginIdentifier ? 'login-account-hint' : undefined"
                @input="clearFieldError('loginIdentifier')"
              />
              <small
                v-if="formErrors.loginIdentifier"
                id="login-account-hint"
                :role="formErrors.loginIdentifier ? 'alert' : undefined"
                :class="{ 'auth-form__hint--error': formErrors.loginIdentifier }"
              >
                {{ t(formErrors.loginIdentifier) }}
              </small>
            </label>

            <label>
              <span id="login-password-label">{{ t('auth.password') }}</span>
              <input
                class="board-field"
                ref="loginPasswordRef"
                v-model="loginForm.password"
                type="password"
                autocomplete="current-password"
                aria-labelledby="login-password-label"
                :disabled="loading"
                :aria-invalid="Boolean(formErrors.loginPassword)"
                :aria-describedby="formErrors.loginPassword ? 'login-password-hint' : undefined"
                @input="clearFieldError('loginPassword')"
              />
              <small
                v-if="formErrors.loginPassword"
                id="login-password-hint"
                :role="formErrors.loginPassword ? 'alert' : undefined"
                :class="{ 'auth-form__hint--error': formErrors.loginPassword }"
              >
                {{ t(formErrors.loginPassword) }}
              </small>
            </label>

            <button
              class="auth-submit"
              :class="{ 'auth-submit--busy': loading }"
              type="submit"
              :disabled="loading"
              :aria-busy="loading ? 'true' : undefined"
            >
              <span v-if="loading" class="material-symbols-outlined auth-submit__spinner" aria-hidden="true">progress_activity</span>
              {{ loading ? loadingLabel : t('auth.signInToConsole') }}
            </button>
          </form>

          <form
            v-else-if="!workspaceNavigation"
            id="register-panel"
            class="auth-form"
            role="tabpanel"
            aria-labelledby="auth-tab-register"
            @submit.prevent="handleRegister"
          >
            <label>
              <span id="register-phone-label">{{ t('auth.phoneNumber') }}</span>
              <input
                class="board-field"
                ref="registerPhoneRef"
                v-model="registerForm.phone"
                type="tel"
                inputmode="numeric"
                autocomplete="tel"
                aria-labelledby="register-phone-label"
                :disabled="loading"
                :aria-invalid="Boolean(formErrors.registerPhone)"
                aria-describedby="register-phone-hint"
                @input="clearFieldError('registerPhone')"
              />
              <small
                id="register-phone-hint"
                :role="formErrors.registerPhone ? 'alert' : undefined"
                :class="{ 'auth-form__hint--error': formErrors.registerPhone }"
              >
                {{ t(formErrors.registerPhone || 'auth.phoneHint') }}
              </small>
            </label>

            <label>
              <span id="register-username-label">{{ t('auth.username') }}</span>
              <input
                class="board-field"
                ref="registerUsernameRef"
                v-model="registerForm.username"
                type="text"
                autocomplete="username"
                aria-labelledby="register-username-label"
                :disabled="loading"
                :aria-invalid="Boolean(formErrors.registerUsername)"
                aria-describedby="register-username-hint"
                @input="clearFieldError('registerUsername')"
              />
              <small
                id="register-username-hint"
                :role="formErrors.registerUsername ? 'alert' : undefined"
                :class="{ 'auth-form__hint--error': formErrors.registerUsername }"
              >
                {{ t(formErrors.registerUsername || 'auth.usernameLength') }}
              </small>
            </label>

            <label>
              <span id="register-password-label">{{ t('auth.password') }}</span>
              <input
                class="board-field"
                ref="registerPasswordRef"
                v-model="registerForm.password"
                type="password"
                autocomplete="new-password"
                aria-labelledby="register-password-label"
                :disabled="loading"
                :aria-invalid="Boolean(formErrors.registerPassword)"
                aria-describedby="register-password-hint"
                @input="clearFieldError('registerPassword')"
              />
              <small
                id="register-password-hint"
                :role="formErrors.registerPassword ? 'alert' : undefined"
                :class="{ 'auth-form__hint--error': formErrors.registerPassword }"
              >
                {{ t(formErrors.registerPassword || 'auth.passwordHint') }}
              </small>
            </label>

            <label>
              <span id="register-confirm-password-label">{{ t('auth.confirmPassword') }}</span>
              <input
                class="board-field"
                ref="confirmPasswordRef"
                v-model="registerForm.confirmPassword"
                type="password"
                autocomplete="new-password"
                aria-labelledby="register-confirm-password-label"
                :disabled="loading"
                :aria-invalid="Boolean(formErrors.confirmPassword)"
                :aria-describedby="formErrors.confirmPassword ? 'register-confirm-password-hint' : undefined"
                @input="clearFieldError('confirmPassword')"
              />
              <small
                v-if="formErrors.confirmPassword"
                id="register-confirm-password-hint"
                :role="formErrors.confirmPassword ? 'alert' : undefined"
                :class="{ 'auth-form__hint--error': formErrors.confirmPassword }"
              >
                {{ t(formErrors.confirmPassword) }}
              </small>
            </label>

            <button
              class="auth-submit"
              :class="{ 'auth-submit--busy': loading }"
              type="submit"
              :disabled="loading"
              :aria-busy="loading ? 'true' : undefined"
            >
              <span v-if="loading" class="material-symbols-outlined auth-submit__spinner" aria-hidden="true">progress_activity</span>
              {{ loading ? loadingLabel : t('auth.registerNow') }}
            </button>
          </form>

          <FailureDetails v-if="requestDiagnostics && !requestError" :details="technicalErrorLines(requestDiagnostics)" suggestion="" />

          <p v-if="loading" class="sr-only" role="status" aria-live="polite">
            {{ loadingLabel }}
          </p>
        </section>
      </div>
    </main>
  </div>
</template>

<style scoped>
.landing-wrapper {
  min-height: 100dvh;
  background:
    radial-gradient(ellipse at 75% 35%, color-mix(in srgb, var(--accent-surface) 60%, transparent), transparent 65%),
    var(--iot-color-bg-canvas);
  color: var(--text);
  font-family: var(--iot-font-body);
}

.hero-section {
  display: grid;
  grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr);
  align-items: center;
  gap: clamp(var(--iot-space-3xl), 5vw, 5rem);
  width: min(100%, 1320px);
  min-height: 100dvh;
  margin: 0 auto;
  padding: 8rem var(--iot-page-gutter) var(--iot-space-4xl);
}

.hero-copy,
.landing-stage {
  min-width: 0;
}

.hero-eyebrow {
  margin: 0 0 var(--iot-space-xl);
  display: flex;
  align-items: center;
  gap: var(--iot-space-md);
  color: var(--text-muted);
  font-size: var(--iot-font-label);
  font-weight: 600;
  letter-spacing: 0.08em;
}

.hero-eyebrow::before {
  content: '';
  width: 1.5rem;
  height: 2px;
  background: var(--accent);
}

.hero-section--with-auth {
  grid-template-columns: minmax(0, 1fr);
  width: min(100%, 600px);
}

.hero-title {
  margin: 0;
  font-family: var(--iot-font-display);
  font-size: clamp(2.5rem, 4.4vw, 4rem);
  font-weight: 600;
  line-height: 1.22;
  letter-spacing: -0.045em;
  text-wrap: balance;
}

.emphasis {
  display: block;
  color: var(--accent);
}

.hero-subtext {
  max-width: 34rem;
  margin: var(--iot-space-xl) 0 0;
  color: var(--text-muted);
  font-size: 1rem;
  line-height: 1.85;
  text-wrap: pretty;
}

.hero-cta,
.auth-submit {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--iot-space-md);
  min-height: 48px;
  border: 1px solid var(--accent-fill);
  border-radius: var(--iot-radius-action);
  background: var(--accent-fill);
  padding: var(--iot-space-md) var(--iot-space-xl);
  color: var(--text-on-fill);
  cursor: pointer;
  transition: background-color var(--iot-motion-fast) ease, border-color var(--iot-motion-fast) ease;
}

.hero-cta {
  font-size: var(--iot-font-base);
  font-weight: 600;
  margin-top: var(--iot-space-2xl);
}

.hero-cta .material-symbols-outlined {
  font-size: 20px;
  transition: transform var(--iot-motion-fast) ease;
}

.hero-cta:hover .material-symbols-outlined {
  transform: translateX(3px);
}

.hero-cta:hover,
.auth-submit:not(:disabled):hover {
  background: var(--accent-fill-hover);
  border-color: var(--accent-fill-hover);
}

.hero-workflow {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--iot-space-lg);
  margin: var(--iot-space-3xl) 0 0;
  border-top: 1px solid var(--border);
  padding: var(--iot-space-xl) 0 0;
  list-style: none;
}

.hero-workflow li {
  display: flex;
  flex-direction: column;
  gap: var(--iot-space-md);
}

.hero-workflow__number {
  padding-top: 0.125rem;
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
  font-variant-numeric: tabular-nums;
}

.hero-workflow h2 {
  margin: 0;
  font-size: var(--iot-font-control);
  font-weight: 600;
}

.hero-workflow p {
  margin: var(--iot-space-xs) 0 0;
  color: var(--text-muted);
  font-size: var(--iot-font-label);
  line-height: 1.6;
}

.auth-panel {
  width: min(100%, 440px);
  margin-inline: auto;
  border: 1px solid var(--border);
  border-radius: var(--iot-radius-surface);
  background: var(--surface-panel);
  box-shadow: var(--shadow-elevated);
  padding: var(--iot-space-2xl);
}

.auth-panel__topbar {
  display: grid;
  grid-template-columns: var(--iot-target-min) minmax(0, 1fr) var(--iot-target-min);
  align-items: center;
  gap: var(--iot-space-sm);
}

.auth-panel__back {
  display: inline-flex;
  width: var(--iot-target-min);
  height: var(--iot-target-min);
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: var(--iot-radius-action);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}

.auth-panel__back:not(:disabled):hover {
  background: var(--surface-control-hover);
  color: var(--text);
}

.auth-panel__back:disabled,
.auth-tabs button:disabled {
  cursor: wait;
}

.auth-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--iot-space-2xs);
  padding: var(--iot-space-2xs);
  border-radius: var(--iot-radius-well);
  background: var(--surface-control);
}

.auth-tabs button {
  min-height: var(--iot-target-min);
  border: 0;
  border-radius: var(--iot-radius-action);
  background: transparent;
  color: var(--text-muted);
  font-size: var(--iot-font-label);
  font-weight: 600;
  cursor: pointer;
}

.auth-tabs button:not(:disabled):hover {
  color: var(--text);
}

.auth-tabs button.active {
  background: var(--surface-panel);
  color: var(--accent);
  box-shadow: var(--shadow-raised);
}

.auth-heading {
  margin: var(--iot-space-xl) 0;
}

.auth-heading h1 {
  margin: 0;
  font-family: var(--iot-font-display);
  font-size: 1.5rem;
  font-weight: 600;
  letter-spacing: -0.025em;
}

.auth-form {
  display: grid;
  gap: var(--iot-space-lg);
}

.auth-form label {
  display: grid;
  gap: var(--iot-space-sm);
}

.auth-form label > span {
  color: var(--text);
  font-size: var(--iot-font-label);
  font-weight: 600;
}

.auth-form input:disabled {
  cursor: wait;
}

.auth-form small {
  color: var(--text-muted);
  font-size: var(--iot-font-caption);
  line-height: 1.5;
}

.auth-form .auth-form__hint--error {
  color: var(--danger);
}

.auth-session-notice,
.auth-request-error {
  display: flex;
  align-items: flex-start;
  gap: var(--iot-space-sm);
  margin: 0 0 var(--iot-space-xl);
  border: 1px solid var(--accent-border);
  border-radius: var(--iot-radius-action);
  background: var(--accent-surface);
  padding: var(--iot-space-md);
  color: var(--accent);
  font-size: var(--iot-font-control);
  line-height: 1.6;
}

.auth-request-error {
  border-color: var(--danger-border);
  background: var(--danger-surface);
  color: var(--danger);
}

.auth-request-error > div {
  min-width: 0;
  overflow-wrap: anywhere;
}

.auth-request-error .material-symbols-outlined,
.auth-session-notice .material-symbols-outlined {
  flex: 0 0 auto;
  font-size: 20px;
}

.auth-submit {
  min-height: var(--iot-target-min);
  width: 100%;
  margin-top: var(--iot-space-2xs);
}

.auth-submit:disabled {
  background: var(--accent-fill-disabled);
  border-color: var(--accent-fill-disabled);
  opacity: 1;
  cursor: not-allowed;
}

.auth-submit--busy:disabled {
  cursor: progress;
}

.auth-submit__spinner {
  font-size: 20px;
  animation: auth-spin 1s linear infinite;
}

.hero-cta:focus-visible,
.auth-panel button:focus-visible,
.auth-request-error:focus {
  outline: 2px solid var(--accent-border);
  outline-offset: 2px;
}

@keyframes auth-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .landing-wrapper *:not(.auth-submit__spinner),
  .landing-wrapper *::before,
  .landing-wrapper *::after {
    animation: none !important;
    transition: none !important;
  }
  .auth-submit__spinner {
    animation-duration: 2.4s;
    animation-timing-function: steps(8);
  }
}

@media (max-width: 900px) {
  .hero-section {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--iot-space-3xl);
    min-height: auto;
    padding-top: 8rem;
  }
  .hero-copy {
    max-width: 38rem;
  }
  .landing-stage {
    width: min(100%, 600px);
  }
  .hero-section--with-auth {
    gap: var(--iot-space-2xl);
  }
}

@media (max-width: 520px) {
  .hero-section {
    padding: 7rem var(--iot-space-lg) var(--iot-space-3xl);
  }
  .hero-title {
    font-size: 2.25rem;
  }
  .hero-section--with-auth {
    gap: var(--iot-space-xl);
  }
  .auth-panel {
    padding: var(--iot-space-xl);
  }
  .auth-heading {
    margin: var(--iot-space-xl) 0;
  }
  .auth-form {
    gap: var(--iot-space-md);
  }
  .hero-workflow {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--iot-space-xl);
  }
  .hero-workflow li {
    flex-direction: row;
    gap: var(--iot-space-lg);
  }
}
</style>
