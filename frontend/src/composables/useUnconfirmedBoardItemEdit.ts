import { computed, onScopeDispose, reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuth } from '@/stores/auth'
import type { ApiErrorDetails } from '@/utils/userMessage'

export const BOARD_ITEM_EDIT_OUTCOME_UNKNOWN = 'BOARD_ITEM_EDIT_OUTCOME_UNKNOWN'

export const useUnconfirmedBoardItemEdit = (draftKey: () => string | null) => {
  const { t } = useI18n()
  const { state, getToken, isCurrentSessionToken } = useAuth()
  const messages = reactive(new Map<string, string>())
  let active = true
  watch(() => [state.token, state.user?.userId], () => messages.clear(), { flush: 'sync' })
  onScopeDispose(() => { active = false })

  const capture = () => {
    const key = draftKey()
    const token = getToken()
    return (failure?: ApiErrorDetails) => {
      if (failure?.reasonCode !== BOARD_ITEM_EDIT_OUTCOME_UNKNOWN) return false
      if (key && active && isCurrentSessionToken(token)) {
        messages.set(key, failure.cause ?? t('app.boardItemEditOutcomeUnknown'))
      }
      return true
    }
  }

  return { message: computed(() => messages.get(draftKey() ?? '')), capture }
}
