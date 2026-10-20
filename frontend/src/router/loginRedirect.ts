import type { RouteLocationNormalizedLoaded, RouteLocationRaw } from 'vue-router';
import { router } from './index';
import { resolveAccountReturn } from './accountReturn';

/**
 * Why the user is being sent to the login surface.
 *
 * Expiry, deliberate sign-out and a changed password need different recovery instructions.
 * The caller owns that intent; the destination retains only a reason and the return path.
 */
export type LoginRedirectReason = 'session-expired' | 'password-changed' | 'password-change-unknown' | 'account-deletion-unknown';

/**
 * Single owner of the login destination. Public login callers need no additional navigation.
 * Component callers can use their own router; only the transport wrapper below uses the singleton.
 */
export const loginRedirectTarget = (
  current: Pick<RouteLocationNormalizedLoaded, 'path' | 'fullPath'>
    & Partial<Pick<RouteLocationNormalizedLoaded, 'query'>>,
  reason?: LoginRedirectReason
): RouteLocationRaw | null => {
  if (current.path === '/') return null;

  const query: Record<string, string> = { mode: 'login' };
  if (current.fullPath && current.fullPath !== '/') {
    query.redirect = current.path === '/account'
      && (reason === 'password-changed' || reason === 'password-change-unknown')
      ? resolveAccountReturn(current.query?.returnTo)
      : current.fullPath;
    // Only stated when the caller says so, and only alongside a return path — a `reason` without somewhere to
    // return to would be describing a session the user never had.
    if (reason) query.reason = reason;
  }
  return { path: '/', query };
};

/**
 * For non-component callers (axios interceptor, SSE transport) that only have the singleton.
 *
 * A rejected token defaults to expiry. HTTP callers pass the pending password-change intent
 * when a background request rejects a credential whose password mutation has not settled.
 */
export const redirectToLogin = async (
  options: { replace?: boolean, reason?: LoginRedirectReason } = {}
): Promise<void> => {
  const target = loginRedirectTarget(router.currentRoute.value, options.reason ?? 'session-expired');
  if (!target) return;
  await (options.replace ? router.replace(target) : router.push(target));
};
