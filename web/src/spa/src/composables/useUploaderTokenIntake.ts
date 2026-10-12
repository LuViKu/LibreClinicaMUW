import { inject, onBeforeUnmount, onMounted } from 'vue'
import { routerKey, type Router } from 'vue-router'

import {
  isTokenHash,
  reportTokenProblem,
  setUploaderToken,
  tokenFromHash,
} from '@/lib/uploaderToken'

/**
 * Takes the uploader token from the address bar of the public upload page.
 *
 * The Standort-Admin's link is `/upload#token=mwu_…`. This reads the URL
 * fragment (and only the fragment) when the page loads and again whenever it
 * changes under the page, which is what happens when a new link is pasted into
 * the address bar of a tab that is already open (the files already dropped
 * stay where they are). A token of the right shape is kept for the browser
 * session (see `lib/uploaderToken`); anything else the fragment calls a token
 * is refused and the page says the link is not valid.
 *
 * Either way the fragment is then removed from the address bar *and from the
 * history entry*, so it is not left visible, not in the back button's menu and
 * not in the entry's saved state. That is done through the router, because the
 * router keeps a copy of the current location in `history.state` and in
 * memory, and a bare `history.replaceState` would leave the token in both.
 * `history.replaceState` is what the router calls, and replaces rather than
 * pushes. A fragment that is not a token (`#main-content`, the skip link's) is
 * none of this module's business and is left alone.
 *
 * Mounted by the one page that uses the public upload client.
 */
export function useUploaderTokenIntake(): void {
  // Not useRouter(): a page mounted without one (a test, a story) is still a page.
  const router = inject<Router | null>(routerKey, null)

  function intake(): void {
    const found = tokenFromHash(window.location.hash)
    if (!found.present) return
    if (found.token) setUploaderToken(found.token)
    else reportTokenProblem('invalid-link')
    void removeFragment(router)
  }

  onMounted(() => {
    intake()
    window.addEventListener('hashchange', intake)
  })
  onBeforeUnmount(() => window.removeEventListener('hashchange', intake))
}

/** The location without its fragment, as the address bar and the history entry should have it. */
function cleanLocation(): string {
  return window.location.pathname + window.location.search
}

/**
 * Removes the fragment from the address bar and from the history entry.
 * Exported for the tests that pin both.
 */
export async function removeFragment(router: Router | null): Promise<void> {
  const route = router?.currentRoute.value
  // A router that has not resolved this page yet (a page mounted outside a
  // router view) has no location of its own to replace: the fallback below does.
  if (router && route && route.matched.length > 0) {
    try {
      await router.replace({ path: route.path, query: route.query, hash: '' })
    } catch {
      /* the router could not do it: the fallback below does */
    }
  }
  if (isTokenHash(window.location.hash)) {
    // No router, or one that did not reach the address bar. Keep the router's
    // own bookkeeping in `history.state` free of the fragment too.
    const state = window.history.state as Record<string, unknown> | null
    const current = state && typeof state.current === 'string' ? state.current : null
    const scrubbed = current === null
      ? state
      : { ...state, current: current.replace(/#.*$/, '') }
    window.history.replaceState(scrubbed, '', cleanLocation())
  }
}
