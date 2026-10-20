import {
  createRouter,
  createWebHashHistory,
  type RouteLocationNormalized,
  type RouteLocationRaw,
  type RouteRecordRaw
} from 'vue-router';
import { useAuth } from '@/stores/auth';

declare module 'vue-router' {
  interface RouteMeta {
    /** Reachable without a session. Everything else requires authentication. */
    public?: boolean;
    /** Document title for the route. Absent on pure redirect records. */
    title?: string;
  }
}

// `base: './'` (the default in vite.config.ts) yields a relative BASE_URL, which tells us
// nothing about where the app is mounted. Only an absolute base can be stripped from a
// direct deep link; otherwise treat the whole pathname as the route, as before.
const APP_BASE = import.meta.env.BASE_URL.startsWith('/') ? import.meta.env.BASE_URL : '/';

/**
 * The app is served from hash history, so a deep link written without the `#`
 * (`/board`) has to be folded back into `/#/board` before the router boots.
 */
const normalizeDirectPathForHashHistory = () => {
  const { hash, pathname, search } = window.location;
  if (hash) return;

  const base = APP_BASE.endsWith('/') ? APP_BASE : `${APP_BASE}/`;
  const withoutBase = pathname.startsWith(base) ? pathname.slice(base.length - 1) : pathname;
  const route = withoutBase.replace(/\/index\.html$/, '').replace(/\/+$/, '') || '/';
  if (route === '/') return;

  window.history.replaceState(null, '', `${base}#${route}${search}`);
};

normalizeDirectPathForHashHistory();

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'landing',
    component: () => import('../views/Landing.vue'),
    meta: { title: 'IoT-Verify', public: true }
  },
  {
    path: '/board',
    name: 'board',
    component: () => import('../views/Board.vue'),
    meta: { title: 'IoT-Verify' }
  },
  {
    path: '/account',
    name: 'account',
    component: () => import('../views/Account.vue'),
    meta: { title: 'IoT-Verify' }
  },
  {
    path: '/404',
    name: '404',
    component: () => import('../views/NotFound.vue'),
    meta: { title: 'IoT-Verify · 404', public: true }
  },
  {
    path: '/:catchAll(.*)',
    // Carry the address that was actually requested. A bare `redirect: '/404'` discarded it, so the
    // page could only say "this does not exist" without saying what "this" was — and the most common
    // cause is a shared link cut short, which is obvious the moment the address is visible.
    redirect: to => ({ path: '/404', query: { from: to.fullPath } })
  }
];

const router = createRouter({
  // No argument: vue-router derives the hash base from location.pathname, which is
  // correct for both root and sub-path deployments. Passing a relative base breaks it.
  history: createWebHashHistory(),
  routes
});

// The auth store derives its initial state from localStorage at module load, so it is
// already authoritative here — the guard must not re-read storage and risk disagreeing
// with the state the rest of the app renders from.
let navigationInProgress = false;

export const resolveAuthenticatedEntry = (
  to: Pick<RouteLocationNormalized, 'path' | 'fullPath' | 'meta'>,
  isLoggedIn: boolean
): RouteLocationRaw | undefined => {
  if (to.meta.public) {
    return isLoggedIn && to.path === '/' ? '/board' : undefined;
  }
  return isLoggedIn ? undefined : { path: '/', query: { mode: 'login', redirect: to.fullPath } };
};

router.beforeEach((to, _from, next) => {
  // `revalidateSession()` drops a session whose token expired while the tab was open, so
  // a stale tab cannot navigate into a private route and only then discover it is signed
  // out. The store stays the single source of truth for that decision.
  //
  // It can also flip `isLoggedIn`, which App.vue watches with `flush: 'sync'` — so without this
  // flag its `router.replace` would run *inside* this guard, building `redirect=` from the route
  // being left rather than from `to`, and cancelling the in-flight navigation with an unhandled
  // NavigationAborted. The resolution below already produces the right target from `to`.
  navigationInProgress = true;
  try {
    const target = resolveAuthenticatedEntry(to, useAuth().revalidateSession());
    if (target === undefined) {
      next();
      return;
    }
    next(target);
  } finally {
    navigationInProgress = false;
  }
});

router.afterEach(to => {
  if (to.meta.title) document.title = to.meta.title;
});

/**
 * True while `beforeEach` is resolving a navigation.
 *
 * Read by App.vue's auth watcher: the guard's own `revalidateSession()` can flip `isLoggedIn`
 * synchronously, and a competing `router.replace` from that watcher would both misbuild `redirect=`
 * (from the route being left) and abort the navigation the guard is about to answer correctly.
 */
export const isNavigationInProgress = () => navigationInProgress;

export { router };
export default router;
