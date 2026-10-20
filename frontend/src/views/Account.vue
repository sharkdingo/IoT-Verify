<script lang="ts">
export const isAccountDeletionOutcomeUncertain = (error: unknown): boolean => {
  const status = Number((error as { response?: { status?: unknown } } | null)?.response?.status)
  return !Number.isInteger(status) || status < 400 || status >= 500
}
</script>

<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { useAuth } from '@/stores/auth'
import { useModalAccessibility } from '@/composables/useModalAccessibility'
import ProductMark from '@/components/common/ProductMark.vue'
import HintTooltip from '@/components/common/HintTooltip.vue'
import ThemeToggle from '@/components/common/ThemeToggle.vue'
import LanguageToggle from '@/components/common/LanguageToggle.vue'
import { resolveAccountReturn } from '@/router/accountReturn'
import { confirmChoice, notifySuccess } from '@/utils/feedback'
import { clearStoredFuzzNotifications, fuzzNotificationStorageKeyForUser } from '@/views/board/fuzzingRecovery'
import type { ChatLogoutPreparation } from '@/types/chat'
import { CREDENTIAL_LIMITS } from '@/constants/requestLimits'
import { loginRedirectTarget, type LoginRedirectReason } from '@/router/loginRedirect'
import { isValidNormalizedUsername, normalizeAccountIdentifier } from '@/utils/accountIdentifier'
import { errorDetails, technicalErrorLines, type ApiErrorDetails } from '@/utils/userMessage'
import FailureDetails from '@/components/FailureDetails.vue'
import type { AccountProfile, Result } from '@/types/auth'

const props = defineProps<{
  prepareChatForLogout: () => Promise<ChatLogoutPreparation>
}>()
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuth()
const profile = ref<AccountProfile | null>(null)
const loading = ref(false)
const pending = ref<'username' | 'password' | 'logout' | 'delete' | null>(null)
const pendingToken = ref<string | null>(null)
const showLogoutDialog = ref(false)
const showDeleteAccountDialog = ref(false)
const sessionError = ref('')
const deleteError = ref('')
const deleteFailure = shallowRef<ApiErrorDetails | null>(null)
const LogoutConfirmDialog = defineAsyncComponent(() => import('@/components/LogoutConfirmDialog.vue'))
const AccountDeleteDialog = defineAsyncComponent(() => import('@/components/AccountDeleteDialog.vue'))
const returnTo = computed(() => resolveAccountReturn(route.query.returnTo))
const loadError = ref('')
const loadFailure = shallowRef<ApiErrorDetails | null>(null)
type AccountFailure = { key: string; error?: unknown }
const usernameError = shallowRef<AccountFailure | null>(null)
const usernameErrorField = ref<'username' | 'currentPassword' | null>(null)
const passwordError = shallowRef<AccountFailure | null>(null)
const passwordErrorField = ref<'currentPassword' | 'newPassword' | null>(null)
const usernameForm = reactive({ username: '', currentPassword: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
const showPasswords = ref(false)
const activeEditor = ref<'username' | 'password' | null>(null)
const usernameNeedsRefresh = ref(false)
let epoch = 0
let disposed = false

const clearSecrets = () => {
  usernameForm.currentPassword = ''
  passwordForm.currentPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmation = ''
  showPasswords.value = false
}
const owner = () => {
  const token = auth.getToken()
  const userId = auth.state.user?.userId
  const requestEpoch = epoch
  return {
    token,
    userId,
    current: () => !disposed && epoch === requestEpoch
      && token !== null && auth.isCurrentSessionToken(token) && auth.state.user?.userId === userId
  }
}
const usernameConfirmation = shallowRef<{
  username: string
  expectedUsername: string
  request: ReturnType<typeof owner>
} | null>(null)
let confirmationOwnerCurrent = () => false
const closeUsernameConfirmation = () => {
  if (pending.value === 'username') return
  usernameConfirmation.value = null
  usernameForm.currentPassword = ''
  if (usernameErrorField.value === 'currentPassword') {
    usernameError.value = null
    usernameErrorField.value = null
  }
}
const { setDialogRef, handleModalKeydown } = useModalAccessibility(
  computed(() => usernameConfirmation.value !== null), closeUsernameConfirmation,
  () => document.querySelector<HTMLElement>('[data-testid="account-save-username"]')
    ?? document.querySelector<HTMLElement>('[data-testid="account-edit-username"]'),
  { shouldRestoreFocus: () => confirmationOwnerCurrent() }
)
const readProfile = (response: Result<AccountProfile>, userId: number | undefined): AccountProfile => {
  const data = response.data
  if (!data || data.userId !== userId) {
    throw new Error('Account response belongs to another user')
  }
  return data
}
const adoptProfile = (data: AccountProfile, token: string) => {
  profile.value = data
  usernameForm.username = data.username
  auth.updateUserIfTokenMatches(token, {
    userId: data.userId, phone: data.phone, username: data.username
  })
}
const loadProfile = async () => {
  if (pending.value || loading.value) return
  const request = owner()
  if (!request.token || !request.current()) return
  loading.value = true
  loadError.value = ''
  loadFailure.value = null
  try {
    const response = await authApi.getAccount(request.token)
    if (!request.current()) return
    adoptProfile(readProfile(response, request.userId), request.token)
    usernameError.value = null
    usernameErrorField.value = null
    usernameNeedsRefresh.value = false
  } catch (error) {
    if (request.current()) {
      loadError.value = 'account.loadFailed'
      loadFailure.value = errorDetails(error)
    }
  } finally {
    if (request.current()) loading.value = false
  }
}

const normalizedUsername = computed(() => normalizeAccountIdentifier(usernameForm.username))
const usernameIssue = computed(() => {
  const name = normalizedUsername.value
  if (!name) return t('auth.usernameRequired')
  const length = Array.from(name).length
  if (length < CREDENTIAL_LIMITS.minUsernameDisplayLength
      || length > CREDENTIAL_LIMITS.maxUsernameDisplayLength) return t('auth.usernameLength')
  if (!isValidNormalizedUsername(name)) return t('auth.usernameInvalidCharacters')
  if (CREDENTIAL_LIMITS.phonePattern.test(name)) return t('account.usernamePhoneReserved')
  return ''
})
const usernameBlockedReason = computed(() => {
  if (pending.value) return t('account.operationPending')
  if (loading.value || loadError.value) return t('account.profileRequired')
  if (usernameNeedsRefresh.value) return t('account.refreshBeforeRetry')
  if (usernameIssue.value) return usernameIssue.value
  if (normalizedUsername.value === profile.value?.username) return t('account.usernameUnchanged')
  return ''
})
const usernameBlockedDescriptionId = computed(() => {
  if (!usernameBlockedReason.value) return undefined
  return loadError.value && usernameBlockedReason.value === t('account.profileRequired')
    ? 'account-load-error' : 'account-username-blocked'
})
const usernameConfirmationBlockedReason = computed(() => {
  if (pending.value) return t('account.operationPending')
  if (!usernameConfirmation.value?.request.current()) return t('account.profileRequired')
  if (!usernameForm.currentPassword) return t('account.currentPasswordRequired')
  return ''
})
const newPasswordIssue = computed(() => {
  const value = passwordForm.newPassword
  if (!value) return t('account.newPasswordRequired')
  if (value.length < CREDENTIAL_LIMITS.minPasswordLength || value.length > CREDENTIAL_LIMITS.maxPasswordLength
      || new TextEncoder().encode(value).length > CREDENTIAL_LIMITS.maxPasswordBcryptBytes) return t('auth.passwordLength')
  if (value === passwordForm.currentPassword) return t('account.newPasswordUnchanged')
  return ''
})
const passwordBlockedReason = computed(() => {
  if (pending.value) return t('account.operationPending')
  if (loading.value || loadError.value) return t('account.profileRequired')
  if (!passwordForm.currentPassword) return t('account.currentPasswordRequired')
  if (newPasswordIssue.value) return newPasswordIssue.value
  if (!passwordForm.confirmation) return t('auth.confirmPasswordRequired')
  if (passwordForm.confirmation !== passwordForm.newPassword) return t('auth.passwordMismatch')
  return ''
})
const passwordBlockedDescriptionId = computed(() => {
  if (!passwordBlockedReason.value) return undefined
  if (loadError.value && passwordBlockedReason.value === t('account.profileRequired')) return 'account-load-error'
  return passwordForm.newPassword && passwordBlockedReason.value === newPasswordIssue.value
    ? 'account-new-password-hint' : 'account-password-blocked'
})
const errorMessage = (error: any, fallback: string) => {
  const data = error?.response?.data?.data
  switch (data?.reasonCode) {
    case 'CURRENT_PASSWORD_INCORRECT': return t('account.currentPasswordIncorrect')
    case 'NEW_PASSWORD_UNCHANGED': return t('account.newPasswordUnchanged')
    case 'USERNAME_ALREADY_EXISTS': return t('account.usernameExists')
    case 'ACCOUNT_PROFILE_CHANGED': return t('account.profileChanged')
    case 'AUTH_ACCOUNT_CHANGE_RATE_LIMIT_REACHED': {
      const seconds = Number(data.retryAfterSeconds)
      const capacity = data.scope === 'CAPACITY'
      return Number.isInteger(seconds) && seconds > 0
        ? t(capacity ? 'auth.rateLimitCapacityReached' : 'auth.rateLimitReached', { seconds })
        : t(capacity ? 'auth.rateLimitCapacityReachedGeneric' : 'auth.rateLimitReachedGeneric')
    }
  }
  return fallback
}
const usernameErrorMessage = computed(() => usernameError.value
  ? errorMessage(usernameError.value.error, t(usernameError.value.key)) : '')
const passwordErrorMessage = computed(() => passwordError.value
  ? errorMessage(passwordError.value.error, t(passwordError.value.key)) : '')
const usernameTechnicalDetails = computed(() => usernameError.value
  ? technicalErrorLines(errorDetails(usernameError.value.error)) : [])
const passwordTechnicalDetails = computed(() => passwordError.value
  ? technicalErrorLines(errorDetails(passwordError.value.error)) : [])
const outcomeUnknown = (error: any) => !error?.response || Number(error.response.status) >= 500
const focusError = async (id: string) => {
  await nextTick()
  document.getElementById(id)?.focus()
}

const clearEditorErrors = () => {
  usernameError.value = null
  usernameErrorField.value = null
  passwordError.value = null
  passwordErrorField.value = null
}
const focusElement = async (selector: string) => {
  await nextTick()
  document.querySelector<HTMLElement>(selector)?.focus()
}
const startEditor = async (editor: 'username' | 'password') => {
  if (pending.value || loading.value || activeEditor.value === editor) return
  const request = owner()
  if (!request.current()) return
  closeUsernameConfirmation()
  clearSecrets()
  clearEditorErrors()
  activeEditor.value = null
  // A new edit starts from a fresh baseline; an already-open draft keeps its own baseline.
  if (profile.value?.username !== auth.state.user?.username) await loadProfile()
  if (!request.current() || loadError.value || !profile.value) return
  usernameForm.username = profile.value.username
  activeEditor.value = editor
  await focusElement(editor === 'username' ? '#account-username' : '#account-current-password')
}
const cancelEditor = async () => {
  if (pending.value) return
  const editor = activeEditor.value
  closeUsernameConfirmation()
  clearSecrets()
  clearEditorErrors()
  activeEditor.value = null
  await focusElement(`[data-testid="account-edit-${editor}"]`)
}
const refreshProfile = async () => {
  closeUsernameConfirmation()
  await loadProfile()
  if (activeEditor.value === 'username') await focusElement('#account-username')
}
const openUsernameConfirmation = () => {
  if (usernameBlockedReason.value || !profile.value) return
  const request = owner()
  if (!request.token || !request.current()) return
  usernameForm.currentPassword = ''
  usernameError.value = null
  usernameErrorField.value = null
  // Confirmation authorizes this candidate and baseline, never a later change to the page draft.
  confirmationOwnerCurrent = request.current
  usernameConfirmation.value = {
    username: normalizedUsername.value,
    expectedUsername: profile.value.username,
    request
  }
}
const saveUsername = async () => {
  const confirmation = usernameConfirmation.value
  if (usernameConfirmationBlockedReason.value || !confirmation) return
  const request = confirmation.request
  if (!request.token || !request.current()) return
  pending.value = 'username'
  pendingToken.value = request.token
  usernameError.value = null
  usernameErrorField.value = null
  try {
    const response = await authApi.updateUsername({
      username: confirmation.username,
      currentPassword: usernameForm.currentPassword,
      expectedUsername: confirmation.expectedUsername
    }, request.token)
    if (!request.current()) return
    adoptProfile(readProfile(response, request.userId), request.token)
    usernameForm.currentPassword = ''
    usernameConfirmation.value = null
    activeEditor.value = null
  } catch (error) {
    if (!request.current()) return
    if (outcomeUnknown(error)) {
      try {
        const response = await authApi.getAccount(request.token)
        if (!request.current()) return
        adoptProfile(readProfile(response, request.userId), request.token)
        usernameError.value = { key: 'account.usernameOutcomeRefreshed' }
      } catch (profileError) {
        if (!request.current()) return
        loadError.value = 'account.usernameOutcomeUnknown'
        loadFailure.value = errorDetails(profileError)
        usernameError.value = null
      }
      usernameForm.currentPassword = ''
      usernameConfirmation.value = null
    } else {
      usernameError.value = { key: 'account.usernameSaveFailed', error }
      const data = (error as any)?.response?.data?.data
      usernameErrorField.value = data?.reasonCode === 'CURRENT_PASSWORD_INCORRECT' || data?.errors?.currentPassword
        ? 'currentPassword'
        : ['USERNAME_ALREADY_EXISTS', 'ACCOUNT_PROFILE_CHANGED'].includes(data?.reasonCode) || data?.errors?.username
          ? 'username' : null
      if (usernameErrorField.value === 'username') {
        usernameForm.currentPassword = ''
        usernameConfirmation.value = null
        usernameNeedsRefresh.value = data?.reasonCode === 'ACCOUNT_PROFILE_CHANGED'
      }
    }
    await focusError('account-username-error')
  } finally {
    if (request.current()) { pending.value = null; pendingToken.value = null }
  }
}

const finishPasswordChange = async (token: string, reason: LoginRedirectReason) => {
  const target = loginRedirectTarget({ path: '/board', fullPath: returnTo.value }, reason)
  clearSecrets()
  if (auth.logoutIfTokenMatches(token) && target) await router.replace(target)
}
const savePassword = async () => {
  if (passwordBlockedReason.value) return
  const request = owner()
  if (!request.token || !request.current()) return
  pending.value = 'password'
  pendingToken.value = request.token
  passwordError.value = null
  passwordErrorField.value = null
  let submitted = false
  try {
    const prepared = await prepareSessionEnd(request.current, 'password')
    if (!request.current()) return
    if (prepared !== 'ready') {
      if (prepared === 'reconciliation-failed') {
        passwordError.value = { key: 'account.passwordPreparationBlocked' }
        await focusError('account-password-error')
      }
      return
    }
    if (!auth.beginPasswordChange(request.token)) return
    submitted = true
    const response = await authApi.changePassword({
      currentPassword: passwordForm.currentPassword,
      newPassword: passwordForm.newPassword
    }, request.token)
    if (!request.current()) return
    if (response.code !== 200) throw new Error('Password change response is incomplete')
    await finishPasswordChange(request.token, 'password-changed')
  } catch (error) {
    if (!request.current()) return
    if (submitted && outcomeUnknown(error)) {
      await finishPasswordChange(request.token, 'password-change-unknown')
    } else {
      passwordError.value = { key: 'account.passwordSaveFailed', error }
      const data = (error as any)?.response?.data?.data
      passwordErrorField.value = data?.reasonCode === 'CURRENT_PASSWORD_INCORRECT' || data?.errors?.currentPassword
        ? 'currentPassword'
        : data?.reasonCode === 'NEW_PASSWORD_UNCHANGED' || data?.errors?.newPassword
          ? 'newPassword' : null
      await focusError('account-password-error')
    }
  } finally {
    if (submitted) auth.endPasswordChange(request.token)
    if (request.current()) { pending.value = null; pendingToken.value = null }
  }
}


const prepareSessionEnd = async (current: () => boolean, action: 'password' | 'logout' | 'delete') => {
  let result: ChatLogoutPreparation = 'ready'
  try {
    result = await props.prepareChatForLogout()
  } catch {
    result = 'reconciliation-failed'
  }
  if (!current()) return 'cancelled'
  if (result === 'reconciliation-failed') return result
  if (result !== 'outcome-unknown') return 'ready'
  const proceed = await confirmChoice({
    title: action === 'password' ? t('account.passwordChatUnknownTitle') : t('app.chat.logoutOutcomeUnknownTitle'),
    message: action === 'password' ? t('account.passwordChatUnknownMessage')
      : action === 'delete' ? t('account.deleteChatUnknownMessage') : t('app.chat.logoutOutcomeUnknownMessage'),
    confirmText: action === 'password' ? t('account.passwordProceed')
      : action === 'delete' ? t('app.deleteAccountConfirm') : t('app.chat.logoutOutcomeUnknownConfirm')
  })
  return current() && proceed ? 'ready' : 'cancelled'
}

const handleLogoutConfirm = async () => {
  if (pending.value) return
  const request = owner()
  if (!request.token || !request.current()) return
  pending.value = 'logout'
  pendingToken.value = request.token
  sessionError.value = ''
  try {
    const prepared = await prepareSessionEnd(request.current, 'logout')
    if (prepared !== 'ready') {
      if (request.current() && prepared === 'reconciliation-failed') {
        showLogoutDialog.value = false
        sessionError.value = 'account.sessionPreparationBlocked'
      }
      return
    }
    try {
      // A failed revocation request still permits the user's explicit local sign-out.
      await authApi.logout(request.token)
    } catch { /* Local sign-out does not depend on Redis or the logout response. */ }
    if (!request.current()) return
    const target = loginRedirectTarget({ path: '/board', fullPath: returnTo.value })
    clearSecrets()
    if (auth.logoutIfTokenMatches(request.token) && target) await router.replace(target)
  } finally {
    if (request.current()) { pending.value = null; pendingToken.value = null }
  }
}

const handleDeleteAccountConfirm = async (payload: { password: string; confirmation: string }) => {
  if (pending.value) return
  const request = owner()
  if (!request.token || !request.current()) return
  const notificationKey = fuzzNotificationStorageKeyForUser(request.userId)
  pending.value = 'delete'
  pendingToken.value = request.token
  deleteError.value = ''
  deleteFailure.value = null
  let submitted = false
  try {
    const prepared = await prepareSessionEnd(request.current, 'delete')
    if (prepared !== 'ready') {
      if (request.current() && prepared === 'reconciliation-failed') {
        showDeleteAccountDialog.value = false
        deleteError.value = 'account.deletePreparationBlocked'
      }
      return
    }
    submitted = true
    const response = await authApi.deleteAccount(payload, request.token)
    if (!request.current()) return
    if (response.code !== 200) throw new Error('Account deletion response is incomplete')
    clearStoredFuzzNotifications(notificationKey)
    clearSecrets()
    if (auth.logoutIfTokenMatches(request.token)) {
      await router.replace({ path: '/', query: { mode: 'register' } })
      await nextTick()
      notifySuccess(t('app.deleteAccountSuccess'))
    }
  } catch (error) {
    if (!request.current()) return
    if (submitted && isAccountDeletionOutcomeUncertain(error)) {
      clearStoredFuzzNotifications(notificationKey)
      clearSecrets()
      const target = loginRedirectTarget({ path: '/board', fullPath: returnTo.value }, 'account-deletion-unknown')
      if (auth.logoutIfTokenMatches(request.token) && target) {
        await router.replace(target)
      }
    } else {
      deleteFailure.value = errorDetails(error)
    }
  } finally {
    if (request.current()) { pending.value = null; pendingToken.value = null }
  }
}

watch(() => auth.state.token, () => {
  epoch += 1
  clearSecrets()
  usernameConfirmation.value = null
  activeEditor.value = null
  usernameNeedsRefresh.value = false
  profile.value = null
  usernameForm.username = ''
  loadError.value = ''
  loadFailure.value = null
  usernameError.value = null
  usernameErrorField.value = null
  passwordError.value = null
  passwordErrorField.value = null
  sessionError.value = ''
  deleteError.value = ''
  deleteFailure.value = null
  loading.value = false
  pending.value = null
  pendingToken.value = null
  showLogoutDialog.value = false
  showDeleteAccountDialog.value = false
  if (auth.state.isLoggedIn) void loadProfile()
}, { immediate: true })

onBeforeRouteLeave(() => {
  // Authentication loss must be able to leave even while its rejected write is settling.
  if (pending.value && pendingToken.value && auth.isCurrentSessionToken(pendingToken.value)) return false
  return true
})
onBeforeUnmount(() => {
  disposed = true
  epoch += 1
  clearSecrets()
})
const displayUsername = computed(() => auth.state.user?.username ?? '')
</script>

<template>
  <div class="account-shell">
    <header class="account-header" data-toast-anchor>
      <button type="button" class="account-brand" :disabled="Boolean(pending)" :aria-label="t('account.backToBoard')" @click="router.push(returnTo)">
        <ProductMark /><span>IoT-Verify</span>
      </button>
      <div class="account-header-actions">
        <HintTooltip :content="t('account.backToBoard')">
          <button type="button" class="account-button account-back" data-testid="account-back" :disabled="Boolean(pending)" :aria-label="t('account.backToBoard')" @click="router.push(returnTo)">
            <span class="material-symbols-outlined" aria-hidden="true">arrow_back</span><span>{{ t('account.backToBoard') }}</span>
          </button>
        </HintTooltip>
        <ThemeToggle compact /><LanguageToggle compact />
      </div>
    </header>

    <section class="account-page" data-testid="account-page" aria-labelledby="account-title">
      <h1 id="account-title">{{ t('account.title') }}</h1>
      <p v-if="loading" class="account-loading" role="status">{{ t('app.loading') }}</p>
      <div v-if="loadError" id="account-load-error" class="account-notice account-notice--error account-load-error" role="alert" data-testid="account-load-error">
        <div class="account-load-error__message">
          <p>{{ t(loadError) }}</p>
          <FailureDetails v-if="loadFailure" :message="t(loadError)" :details="technicalErrorLines(loadFailure)" suggestion="" />
        </div>
        <button type="button" class="account-button" :disabled="loading || Boolean(pending)" data-testid="account-reload" @click="refreshProfile">{{ t('app.retry') }}</button>
      </div>

      <section class="account-group" aria-labelledby="account-profile-heading" :aria-busy="loading">
        <div class="account-group-heading">
          <h2 id="account-profile-heading">{{ t('account.profileTitle') }}</h2>
          <button v-if="profile" type="button" class="account-button account-refresh" :disabled="loading || Boolean(pending) || Boolean(usernameConfirmation)" data-testid="account-refresh-profile" @click="refreshProfile">{{ t('account.refreshProfile') }}</button>
        </div>
        <div v-if="profile" class="account-setting-row">
          <div class="board-form-label account-setting-label">{{ t('auth.username') }}</div>
          <div class="account-setting-content">
            <template v-if="activeEditor !== 'username'"><p class="account-setting-value" data-testid="account-username-value">{{ displayUsername }}</p></template>
            <form v-else novalidate class="account-editor" data-testid="account-username-form" @submit.prevent="openUsernameConfirmation">
              <label class="board-form-label account-field sr-only" for="account-username">{{ t('auth.username') }}</label>
              <input id="account-username" v-model="usernameForm.username" class="account-input board-field" type="text" autocomplete="username"
                :maxlength="CREDENTIAL_LIMITS.maxUsernameLength" :disabled="loading || Boolean(pending)" :aria-invalid="Boolean(usernameIssue) || usernameErrorField === 'username'"
                :aria-describedby="usernameErrorField === 'username' ? 'account-username-error' : usernameBlockedDescriptionId" @input="usernameError = null; usernameErrorField = null">
              <p v-if="usernameBlockedDescriptionId === 'account-username-blocked'" id="account-username-blocked" class="account-hint" :class="{ 'account-hint--error': usernameIssue }">{{ usernameBlockedReason }}</p>
              <div class="account-editor-actions">
                <button type="button" class="account-button" data-testid="account-cancel-username" :disabled="Boolean(pending)" @click="cancelEditor">{{ t('app.cancel') }}</button>
                <button type="submit" class="account-button account-button--primary" :disabled="Boolean(usernameBlockedReason)" :aria-describedby="usernameBlockedDescriptionId" data-testid="account-save-username">{{ t('account.continueUsername') }}</button>
              </div>
            </form>
            <div v-if="usernameError && !usernameConfirmation" id="account-username-error" class="account-notice"
              :class="usernameError.key === 'account.usernameOutcomeRefreshed' ? 'account-notice--warning' : 'account-notice--error'"
              role="alert" tabindex="-1" data-testid="account-username-error">
              <p>{{ usernameErrorMessage }}</p>
              <FailureDetails :message="usernameErrorMessage" :details="usernameTechnicalDetails" suggestion="" />
            </div>
          </div>
          <button v-if="activeEditor !== 'username'" type="button" class="account-button account-button--outlined" data-testid="account-edit-username" :disabled="loading || Boolean(pending) || Boolean(loadError)" @click="startEditor('username')">{{ t('app.edit') }}</button>
        </div>
        <div v-if="profile" class="account-setting-row account-phone-row">
          <div class="board-form-label account-setting-label">{{ t('account.signInPhone') }}</div>
          <div class="account-setting-content"><p class="account-setting-value" data-testid="account-phone">{{ profile.phone }}</p></div>
        </div>
      </section>

      <section class="account-group" aria-labelledby="account-security-heading">
        <div class="account-group-heading"><h2 id="account-security-heading">{{ t('account.securityTitle') }}</h2></div>
        <div class="account-setting-row">
          <div class="board-form-label account-setting-label">{{ t('auth.password') }}</div>
          <div class="account-setting-content">
            <p v-if="activeEditor !== 'password'" class="account-row-description">{{ t('account.passwordDescription') }}</p>
            <form v-else novalidate class="account-editor" data-testid="account-password-form" @submit.prevent="savePassword">
              <p id="account-password-impact" class="account-row-description">{{ t('account.passwordImpact') }}</p>
              <label class="board-form-label account-field" for="account-current-password">{{ t('account.currentPassword') }}</label>
              <input id="account-current-password" v-model="passwordForm.currentPassword" class="account-input board-field" :type="showPasswords ? 'text' : 'password'"
                autocomplete="current-password" :disabled="loading || Boolean(pending)" :aria-invalid="passwordErrorField === 'currentPassword'"
                :aria-describedby="passwordErrorField === 'currentPassword' ? 'account-password-error' : undefined" @input="passwordError = null; passwordErrorField = null">
              <label class="board-form-label account-field" for="account-new-password">{{ t('account.newPassword') }}</label>
              <input id="account-new-password" v-model="passwordForm.newPassword" class="account-input board-field" :type="showPasswords ? 'text' : 'password'"
                autocomplete="new-password" :disabled="loading || Boolean(pending)" :aria-invalid="Boolean(passwordForm.newPassword && newPasswordIssue) || passwordErrorField === 'newPassword'"
                :aria-describedby="passwordErrorField === 'newPassword' ? 'account-new-password-hint account-password-error' : 'account-new-password-hint'" @input="passwordError = null; passwordErrorField = null">
              <p id="account-new-password-hint" class="account-hint" :class="{ 'account-hint--error': passwordForm.newPassword && newPasswordIssue }">{{ passwordForm.newPassword && newPasswordIssue || t('auth.passwordHint') }}</p>
              <label class="board-form-label account-field" for="account-confirm-password">{{ t('auth.confirmPassword') }}</label>
              <input id="account-confirm-password" v-model="passwordForm.confirmation" class="account-input board-field" :type="showPasswords ? 'text' : 'password'"
                autocomplete="new-password" :disabled="loading || Boolean(pending)"
                :aria-invalid="Boolean(passwordForm.confirmation && passwordForm.confirmation !== passwordForm.newPassword)" @input="passwordError = null">
              <button type="button" class="account-button account-visibility" :aria-pressed="showPasswords" :disabled="Boolean(pending)" @click="showPasswords = !showPasswords"><span class="material-symbols-outlined" aria-hidden="true">{{ showPasswords ? 'visibility_off' : 'visibility' }}</span>{{ showPasswords ? t('account.hidePasswords') : t('account.showPasswords') }}</button>
              <div v-if="passwordError" id="account-password-error" class="account-notice account-notice--error" role="alert" tabindex="-1" data-testid="account-password-error">
                <p>{{ passwordErrorMessage }}</p>
                <FailureDetails :message="passwordErrorMessage" :details="passwordTechnicalDetails" suggestion="" />
              </div>
              <p v-if="passwordBlockedDescriptionId === 'account-password-blocked'" id="account-password-blocked" class="account-hint">{{ passwordBlockedReason }}</p>
              <div class="account-editor-actions">
                <button type="button" class="account-button" data-testid="account-cancel-password" :disabled="Boolean(pending)" @click="cancelEditor">{{ t('app.cancel') }}</button>
                <button type="submit" class="account-button account-button--primary" :disabled="Boolean(passwordBlockedReason)" :aria-describedby="passwordBlockedDescriptionId ? `account-password-impact ${passwordBlockedDescriptionId}` : 'account-password-impact'" data-testid="account-save-password"><span v-if="pending === 'password'" class="iot-dialog-btn__spinner" aria-hidden="true"></span>{{ t('account.savePassword') }}</button>
              </div>
            </form>
          </div>
          <button v-if="activeEditor !== 'password'" type="button" class="account-button account-button--outlined" data-testid="account-edit-password" :disabled="loading || Boolean(pending) || !profile || Boolean(loadError)" @click="startEditor('password')">{{ t('app.edit') }}</button>
        </div>
        <div class="account-setting-row">
          <div class="board-form-label account-setting-label">{{ t('app.logout') }}</div>
          <div class="account-setting-content"><p v-if="sessionError" class="account-notice account-notice--error" role="alert" data-testid="account-session-error">{{ t(sessionError) }}</p></div>
          <button type="button" class="account-button account-button--outlined" data-testid="account-logout" :disabled="Boolean(pending)" @click="sessionError = ''; showLogoutDialog = true">{{ t('app.logout') }}</button>
        </div>
      </section>

      <div class="account-delete-row">
        <p class="account-hint">{{ t('account.deleteDescription') }}</p>
        <button type="button" class="account-button account-button--danger" data-testid="open-account-delete" :disabled="Boolean(pending)" @click="deleteError = ''; deleteFailure = null; showDeleteAccountDialog = true">{{ t('app.deleteAccountEntry') }}</button>
        <p v-if="deleteError" class="account-notice account-notice--error" role="alert" data-testid="account-delete-error">{{ t(deleteError) }}</p>
      </div>
    </section>

    <Teleport to="body">
      <Transition name="iot-dialog">
        <div v-if="usernameConfirmation" class="iot-dialog-overlay iot-dialog-overlay--session" @click.self="closeUsernameConfirmation" @keydown="handleModalKeydown">
          <form
            :ref="setDialogRef"
            class="iot-dialog iot-dialog--sm"
            data-testid="account-confirm-username-dialog"
            role="dialog"
            aria-modal="true"
            aria-labelledby="account-username-confirmation-title"
            tabindex="-1"
            @submit.prevent="saveUsername"
          >
            <div class="iot-dialog__header">
              <div class="iot-dialog__icon"><span class="material-symbols-outlined" aria-hidden="true">lock</span></div>
              <div class="iot-dialog__heading">
                <h2 id="account-username-confirmation-title" class="iot-dialog__title">{{ t('account.confirmIdentity') }}</h2>
              </div>
            </div>
            <div class="iot-dialog__body">
              <p class="account-verification-message">{{ t('account.confirmUsernameMessage', { username: usernameConfirmation.username }) }}</p>
              <label class="board-form-label account-field" for="account-username-password">{{ t('account.currentPassword') }}</label>
              <input id="account-username-password" v-model="usernameForm.currentPassword" class="account-input board-field" type="password" autocomplete="current-password" autofocus :disabled="Boolean(pending)" :aria-invalid="usernameErrorField === 'currentPassword'" :aria-describedby="usernameErrorField === 'currentPassword' ? 'account-username-error' : 'account-username-confirmation-blocked'" @input="usernameError = null; usernameErrorField = null">
              <div v-if="usernameError" id="account-username-error" class="account-notice account-notice--error" role="alert" tabindex="-1" data-testid="account-username-error">
                <p>{{ usernameErrorMessage }}</p>
                <FailureDetails :message="usernameErrorMessage" :details="usernameTechnicalDetails" suggestion="" />
              </div>
              <p id="account-username-confirmation-blocked" class="account-hint">{{ usernameConfirmationBlockedReason }}</p>
            </div>
            <div class="iot-dialog__footer">
              <button
                type="button"
                class="iot-dialog-btn iot-dialog-btn--ghost"
                data-testid="account-cancel-username-confirmation"
                :disabled="Boolean(pending)"
                @click="closeUsernameConfirmation"
              >{{ t('app.cancel') }}</button>
              <button
                type="submit"
                class="iot-dialog-btn iot-dialog-btn--primary"
                data-testid="account-confirm-username"
                :disabled="Boolean(usernameConfirmationBlockedReason)"
                aria-describedby="account-username-confirmation-blocked"
              >
                <span v-if="pending === 'username'" class="iot-dialog-btn__spinner" aria-hidden="true"></span>{{ t('account.saveUsername') }}
              </button>
            </div>
          </form>
        </div>
      </Transition>
    </Teleport>
    <LogoutConfirmDialog v-model:visible="showLogoutDialog" :loading="pending === 'logout'" @confirm="handleLogoutConfirm" />
    <AccountDeleteDialog v-model:visible="showDeleteAccountDialog" :username="displayUsername" :phone="auth.state.user?.phone ?? ''" :loading="pending === 'delete'" :failure="deleteFailure ? { message: t('app.deleteAccountFailed'), diagnostics: deleteFailure } : null" @clear-failure="deleteFailure = null" @confirm="handleDeleteAccountConfirm" />
  </div>
</template>

<style scoped>
.account-shell { min-height: 100dvh; background: var(--iot-color-bg-body); color: var(--text); }
.account-header { display: flex; align-items: center; justify-content: space-between; gap: var(--iot-space-lg); min-height: var(--iot-header-height); border-bottom: 1px solid var(--border); padding: var(--iot-space-md) var(--iot-page-gutter); background: var(--surface-panel); }
.account-brand { display: inline-flex; align-items: center; gap: var(--iot-space-sm); min-width: var(--iot-target-min); min-height: var(--iot-target-min); padding: 0; border: 0; background: transparent; color: var(--text); font-family: var(--iot-font-display); font-size: 1.125rem; font-weight: 600; letter-spacing: -0.035em; }
.account-header-actions { display: flex; align-items: center; gap: var(--iot-space-sm); }
.account-page { max-width: 60rem; margin: 0 auto; padding: var(--iot-space-3xl) var(--iot-page-gutter) var(--iot-space-3xl); }
.account-page h1 { margin: 0 0 var(--iot-space-2xl); font-size: 1.75rem; font-weight: 600; line-height: 1.4; letter-spacing: -0.025em; }
.account-group { margin-bottom: var(--iot-space-2xl); }
.account-group-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--iot-space-lg); min-height: var(--iot-target-min); border-bottom: 1px solid var(--border-strong); padding-bottom: var(--iot-space-sm); }
.account-group h2 { margin: 0; font-size: var(--iot-font-base); font-weight: 600; line-height: 1.5; }
.account-setting-row { display: grid; grid-template-columns: 9rem minmax(0, 1fr) auto; align-items: start; gap: var(--iot-space-xl); border-bottom: 1px solid var(--border); padding-block: var(--iot-space-xl); }
.account-setting-label { padding-top: var(--iot-space-sm); }
.account-setting-content { min-width: 0; padding-top: var(--iot-space-sm); }
.account-setting-value { margin: 0; font-size: var(--iot-font-control); line-height: 1.5; overflow-wrap: anywhere; }
.account-row-description { margin: 0; color: var(--text-muted); font-size: var(--iot-font-caption); line-height: 1.5; }
.account-editor { max-width: 26rem; }
.account-field { display: block; margin: var(--iot-space-lg) 0 var(--iot-space-sm); }
.account-hint { margin: var(--iot-space-sm) 0 0; color: var(--text-muted); font-size: var(--iot-font-caption); line-height: 1.5; }
.account-hint--error { color: var(--danger); }
.account-notice { min-width: 0; margin: var(--iot-space-lg) 0 0; border-left: 2px solid var(--accent-border); padding: var(--iot-space-md); color: var(--accent-strong); background: var(--accent-surface); font-size: var(--iot-font-control); line-height: 1.6; overflow-wrap: anywhere; }
.account-notice--error { color: var(--danger); background: var(--danger-surface); border-color: var(--danger-border); }
.account-notice--warning { color: var(--warning); background: var(--warning-surface); border-color: var(--warning-border); }
.account-notice p { margin: 0; }
.account-load-error { display: flex; align-items: center; justify-content: space-between; gap: var(--iot-space-lg); margin-bottom: var(--iot-space-xl); }
.account-load-error__message { min-width: 0; flex: 1; }
.account-loading { color: var(--text-muted); font-size: var(--iot-font-control); }
.account-editor-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: var(--iot-space-sm); margin-top: var(--iot-space-lg); }
.account-button { display: inline-flex; align-items: center; justify-content: center; gap: var(--iot-space-sm); min-width: var(--iot-target-min); min-height: var(--iot-target-min); flex-shrink: 0; border: 1px solid transparent; border-radius: var(--iot-radius-action); padding: var(--iot-space-sm) var(--iot-space-md); background: transparent; color: var(--text-muted); }
.account-button .material-symbols-outlined { font-size: 1.125rem; }
.account-button:hover:not(:disabled) { color: var(--text); background: var(--surface-control-hover); }
.account-button--primary { background: var(--accent-fill); color: var(--text-on-fill); }
.account-button--primary:hover:not(:disabled) { color: var(--text-on-fill); background: var(--accent-fill-hover); }
.account-button--outlined { border-color: var(--control-border); color: var(--text); }
.account-button--danger { color: var(--danger); }
.account-button--danger:hover:not(:disabled) { background: var(--danger-surface); color: var(--danger); }
.account-button:disabled { color: var(--text-muted); background: var(--surface-muted); border-color: var(--border); opacity: 1; cursor: not-allowed; }
.account-button--primary:disabled { color: var(--text-on-fill); background: var(--accent-fill-disabled); }
.account-button:not(:disabled), .account-brand:not(:disabled) { cursor: pointer; }
.account-button:focus-visible, .account-brand:focus-visible { outline: 2px solid var(--accent-border); outline-offset: 2px; }
.account-visibility { margin: var(--iot-space-sm) 0 0 calc(var(--iot-space-lg) * -1); }
.account-delete-row { display: flex; align-items: center; flex-wrap: wrap; justify-content: space-between; gap: var(--iot-space-lg); }
.account-delete-row > .account-hint { max-width: 30rem; margin: 0; }
.account-delete-row > .account-notice { flex: 1 0 100%; }
.account-verification-message { margin: 0; color: var(--text-muted); font-size: var(--iot-font-control); line-height: 1.6; overflow-wrap: anywhere; }
@media (max-width: 639.98px) {
  .account-header { gap: var(--iot-space-sm); padding-inline: var(--iot-space-lg); }
  .account-header-actions { gap: var(--iot-space-2xs); }
  .account-brand { font-size: 1rem; }
  .account-back { width: var(--iot-target-min); padding: 0; }
  .account-back > span:last-child { display: none; }
  .account-page { padding: var(--iot-space-2xl) var(--iot-space-lg); }
  .account-page h1 { font-size: 1.5rem; margin-bottom: var(--iot-space-xl); }
  .account-setting-row { grid-template-columns: minmax(0, 1fr) auto; gap: var(--iot-space-sm) var(--iot-space-lg); }
  .account-setting-label { padding-top: var(--iot-space-sm); }
  .account-setting-content { grid-column: 1 / -1; grid-row: 2; padding-top: 0; }
  .account-setting-row > .account-button { grid-column: 2; grid-row: 1; }
  .account-editor { max-width: none; }
  .account-editor-actions .account-button--primary { flex: 1; }
  .account-delete-row { align-items: flex-start; flex-direction: column; gap: var(--iot-space-sm); }
  .account-load-error { align-items: flex-start; flex-direction: column; }
}
@media (max-width: 359.98px) {
  .account-brand > span { display: none; }
}
</style>
