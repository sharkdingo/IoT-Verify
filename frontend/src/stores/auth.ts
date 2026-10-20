// src/stores/auth.ts - 认证状态管理（使用Vue reactive）
import { reactive, readonly } from 'vue';
import type { UserInfo } from '../types/auth';
import { isLocallyUsableJwt } from '@/utils/jwt';

interface AuthState {
  token: string | null;
  user: UserInfo | null;
  isLoggedIn: boolean;
}

const TOKEN_KEY = 'iot_verify_token';
const USER_KEY = 'iot_verify_user';
const AUTH_SYNC_KEY = 'iot_verify_auth_sync';

// 初始化状态
const getInitialState = (): AuthState => {
  const token = localStorage.getItem(TOKEN_KEY);
  const userStr = localStorage.getItem(USER_KEY);
  
  if (isLocallyUsableJwt(token) && userStr) {
    try {
      const user = JSON.parse(userStr);
      return {
        token,
        user,
        isLoggedIn: true
      };
    } catch {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem(USER_KEY);
      return {
        token: null,
        user: null,
        isLoggedIn: false
      };
    }
  }

  if (token || userStr) {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }
  
  return {
    token: null,
    user: null,
    isLoggedIn: false
  };
};

const state = reactive<AuthState>(getInitialState());
let pendingPasswordChangeToken: string | null = null;

const applyAuthState = (token: string | null, user: UserInfo | null) => {
  const authenticated = isLocallyUsableJwt(token) && Boolean(user);
  state.token = authenticated ? token : null;
  state.user = authenticated ? user : null;
  state.isLoggedIn = authenticated;
};

const publishAuthState = (token: string | null, user: UserInfo | null) => {
  localStorage.setItem(AUTH_SYNC_KEY, JSON.stringify({ token, user, updatedAt: Date.now() }));
};

if (typeof window !== 'undefined') {
  const authWindow = window as Window & { __iotVerifyAuthStorageListener?: (event: StorageEvent) => void };
  if (authWindow.__iotVerifyAuthStorageListener) {
    window.removeEventListener('storage', authWindow.__iotVerifyAuthStorageListener);
  }
  authWindow.__iotVerifyAuthStorageListener = (event: StorageEvent) => {
    if (event.storageArea && event.storageArea !== localStorage) return;
    if (event.key === null) {
      const next = getInitialState();
      applyAuthState(next.token, next.user);
      return;
    }
    if (event.key !== AUTH_SYNC_KEY || !event.newValue) return;
    // A queued event can predate a newer login or profile update in this same tab.
    if (event.newValue !== localStorage.getItem(AUTH_SYNC_KEY)) return;
    try {
      const next = JSON.parse(event.newValue) as { token?: string | null; user?: UserInfo | null };
      applyAuthState(next.token ?? null, next.user ?? null);
    } catch {
      applyAuthState(null, null);
    }
  };
  window.addEventListener('storage', authWindow.__iotVerifyAuthStorageListener);
}

// 只读引用，供外部使用
export const useAuth = () => {
  // 登录成功
  const login = (token: string, user: UserInfo) => {
    state.token = token;
    state.user = user;
    state.isLoggedIn = true;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    publishAuthState(token, user);
  };

  // 登出
  const logout = () => {
    state.token = null;
    state.user = null;
    state.isLoggedIn = false;
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    publishAuthState(null, null);
  };

  // A response may arrive after another tab has logged in as a different user.
  // Only the request's own token is allowed to invalidate the current session.
  // Storage is a write fence: another tab may have changed sessions before its event arrives here.
  const isCurrentSessionToken = (requestToken: string | null): boolean =>
    state.token === requestToken && localStorage.getItem(TOKEN_KEY) === requestToken;

  const logoutIfTokenMatches = (requestToken: string | null): boolean => {
    if (!isCurrentSessionToken(requestToken)) return false;
    logout();
    return true;
  };

  const updateUserIfTokenMatches = (requestToken: string, user: UserInfo): boolean => {
    if (!isCurrentSessionToken(requestToken) || state.user?.userId !== user.userId) return false;
    state.user = user;
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    publishAuthState(requestToken, user);
    return true;
  };

  // A background 401 can arrive after a password commit but before its response.
  // The interceptor must report the unconfirmed password change rather than ordinary expiry.
  const beginPasswordChange = (requestToken: string): boolean => {
    if (!isCurrentSessionToken(requestToken)) return false;
    pendingPasswordChangeToken = requestToken;
    return true;
  };
  const endPasswordChange = (requestToken: string) => {
    if (pendingPasswordChangeToken === requestToken) pendingPasswordChangeToken = null;
  };
  const isPasswordChangePending = (requestToken: string | null): boolean =>
    requestToken !== null && pendingPasswordChangeToken === requestToken;

  // 获取token
  const getToken = (): string | null => state.token;

  // 获取用户信息
  const getUser = (): UserInfo | null => state.user;

  // 是否已登录
  const isAuthenticated = (): boolean => state.isLoggedIn;

  /**
   * Re-check the locally visible expiry of the current token and drop the session if it
   * has lapsed. `isLoggedIn` is otherwise only decided at load/login, so a token that
   * expires while the tab stays open would keep reading as authenticated.
   *
   * This is a UX precheck only — the backend remains the authentication authority.
   */
  const revalidateSession = (): boolean => {
    if (state.isLoggedIn && !isLocallyUsableJwt(state.token)) {
      if (!logoutIfTokenMatches(state.token)) {
        // A newer shared session may precede its queued storage event.
        const next = getInitialState();
        applyAuthState(next.token, next.user);
      }
    }
    return state.isLoggedIn;
  };

  return {
    state: readonly(state),
    login,
    logout,
    logoutIfTokenMatches,
    updateUserIfTokenMatches,
    isCurrentSessionToken,
    beginPasswordChange,
    endPasswordChange,
    isPasswordChangePending,
    getToken,
    getUser,
    isAuthenticated,
    revalidateSession
  };
};
