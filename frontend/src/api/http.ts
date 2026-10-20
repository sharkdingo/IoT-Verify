// src/api/http.ts - Axios配置（带Token自动携带和401处理）
import axios from 'axios';
import type { InternalAxiosRequestConfig } from 'axios';
import { useAuth } from '../stores/auth';
import { redirectToLogin } from '../router/loginRedirect';
import { publishBoardInvalidation } from '@/utils/boardInvalidation';

const api = axios.create({
  // Default to a relative "/api" so dev goes through the Vite proxy and prod through
  // the same-origin reverse proxy (Nginx). Set VITE_API_BASE_URL for cross-origin.
  baseURL: (import.meta.env.VITE_API_BASE_URL || '') + '/api',
  timeout: 100000,
  headers: {
    'Content-Type': 'application/json'
  }
});

type BoardAwareRequestConfig = InternalAxiosRequestConfig & {
  boardInvalidationUserId?: number
  authTokenAtRequest?: string | null
}

export const isBoardMutationRequest = (config: { url?: string; method?: string }) => {
  const method = (config.method || 'get').toLowerCase()
  if (method === 'get' || method === 'head' || method === 'options') return false
  const path = (config.url || '').split('?')[0]
  if (/^\/?board\/(?:rules\/check-(?:duplicate|similarity)|(?:rules|specs)\/recommend)$/.test(path)) return false
  // `edits/availability` is a GET and already excluded above; `edits/undo|redo` change rules and
  // specifications when they apply, so other tabs must be invalidated exactly as for a direct
  // mutation. The response interceptor skips the no-op case (`applied: false`).
  return /^\/?board\/(nodes|environment|specs|rules|templates|scene|batch|edits)(?:\/|$)/.test(path)
    || /^\/?verify\/traces\/[^/]+\/fix\/apply$/.test(path)
}

// 请求拦截器 - 自动添加Token
api.interceptors.request.use(
  (config) => {
    const { getToken, getUser } = useAuth();
    const currentToken = getToken();
    const existingAuthorization = config.headers.get('Authorization');
    if (!existingAuthorization && currentToken) {
      config.headers.Authorization = `Bearer ${currentToken}`;
    }
    const effectiveAuthorization = config.headers.get('Authorization');
    const token = typeof effectiveAuthorization === 'string'
      && effectiveAuthorization.startsWith('Bearer ')
      ? effectiveAuthorization.slice(7)
      : currentToken;
    ;(config as BoardAwareRequestConfig).authTokenAtRequest = token
    ;(config as BoardAwareRequestConfig).boardInvalidationUserId = getUser()?.userId
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

/**
 * Whether a completed response should invalidate other tabs' board state.
 *
 * <p>Split from the interceptor so the no-op case is testable: an undo/redo that applied nothing
 * changed no rule or spec, and invalidating would force a pointless snapshot reload in every tab.
 */
export const shouldPublishBoardInvalidation = (
  config: { url?: string; method?: string },
  body?: unknown
) => {
  if (!isBoardMutationRequest(config)) return false
  // Scoped to the undo endpoints on purpose: `FixApplyResultDto` also carries `applied`, with a
  // different meaning ("did not persist"), so keying on the field alone would silently suppress a
  // fix-apply invalidation that other tabs need.
  const path = (config.url || '').split('?')[0]
  if (!/^\/?board\/edits\/(undo|redo)$/.test(path)) return true
  return (body as { data?: { applied?: boolean } })?.data?.applied !== false
}

api.interceptors.response.use(
  (response) => {
    if (shouldPublishBoardInvalidation(response.config, response.data)) {
      publishBoardInvalidation(
        (response.config as BoardAwareRequestConfig).boardInvalidationUserId,
        'http-mutation'
      )
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      const requestConfig = (error.config || error.response?.config) as BoardAwareRequestConfig | undefined
      const requestToken = requestConfig?.authTokenAtRequest ?? null
      const { logoutIfTokenMatches, isPasswordChangePending } = useAuth();
      const reason = isPasswordChangePending(requestToken) ? 'password-change-unknown' : 'session-expired';
      if (logoutIfTokenMatches(requestToken)) {
        void redirectToLogin({ reason });
      }
    }
    return Promise.reject(error);
  }
);

export default api;
