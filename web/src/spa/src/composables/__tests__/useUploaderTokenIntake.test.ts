/**
 * Taking the uploader token from the address bar of the public upload page.
 *
 * Run against jsdom's real location and history through a real web-history
 * router: what matters is what is left in the address bar, in the history
 * entry's state and in storage, not what a mock says.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createWebHistory, type Router } from 'vue-router'

import { useUploaderTokenIntake } from '../useUploaderTokenIntake'
import { stubWebStorage, type MemoryStorage } from '@/test/memoryStorage'
import {
  clearUploaderToken,
  getUploaderToken,
  reportTokenProblem,
  setUploaderToken,
  tokenProblem,
  tokenVersion,
} from '@/lib/uploaderToken'

const BODY = 'abcXYZ019-_'.repeat(5).slice(0, 43)
const TOKEN = `mwu_${BODY}`
const OTHER = 'mwu_' + 'B'.repeat(43)

const Host = defineComponent({
  setup() {
    useUploaderTokenIntake()
    return () => h('div', { 'data-testid': 'host' })
  },
})

const mounted: VueWrapper[] = []

function routerAt(): Router {
  return createRouter({
    history: createWebHistory('/'),
    routes: [
      { path: '/upload', name: 'upload', component: { template: '<div />' } },
      { path: '/other', name: 'other', component: { template: '<div />' } },
    ],
  })
}

/** Opens the page at a URL the way a browser does: the address is there before the app is. */
async function openAt(url: string, withRouter = true): Promise<{ w: VueWrapper; router: Router | null }> {
  window.history.replaceState(null, '', url)
  const router = withRouter ? routerAt() : null
  // The page mounts under a router view, that is, after the router has resolved it.
  if (router) {
    await router.push(window.location.pathname + window.location.search + window.location.hash)
    await router.isReady()
  }
  const w = mount(Host, { global: { plugins: router ? [router] : [] } })
  mounted.push(w)
  await flushPromises()
  return { w, router }
}

const logged = () =>
  [console.log, console.info, console.warn, console.error, console.debug]
    .flatMap((fn) => vi.mocked(fn).mock.calls)
    .map((args) => args.map(String).join(' '))
    .join('\n')

let session: MemoryStorage
let local: MemoryStorage

beforeEach(() => {
  ;({ session, local } = stubWebStorage())
  clearUploaderToken()
  reportTokenProblem(null)
  window.history.replaceState(null, '', '/')
  for (const fn of ['log', 'info', 'warn', 'error', 'debug'] as const) vi.spyOn(console, fn).mockImplementation(() => {})
})

afterEach(() => {
  for (const w of mounted.splice(0)) w.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('a link that carries a token', () => {
  it('keeps the token for the browser session and takes the fragment out of the address bar', async () => {
    const { router } = await openAt(`/upload#token=${TOKEN}`)
    expect(getUploaderToken()).toBe(TOKEN)
    expect(session.getItem('muw.uploaderToken')).toBe(TOKEN)
    expect(window.location.hash).toBe('')
    expect(window.location.pathname).toBe('/upload')
    expect(window.location.href).not.toContain('token')
    expect(window.location.href).not.toContain(BODY)
    expect(router!.currentRoute.value.hash).toBe('')
    expect(router!.currentRoute.value.fullPath).toBe('/upload')
  })

  it('removes it from the history entry too, not only from the address bar', async () => {
    await openAt(`/upload#token=${TOKEN}`)
    // The router keeps its own copy of the current location in the entry's state.
    expect(JSON.stringify(window.history.state)).not.toContain(BODY)
    expect(JSON.stringify(window.history.state)).not.toContain('token')
    expect((window.history.state as { current?: string }).current).toBe('/upload')
  })

  it('replaces the entry instead of adding one, so "back" does not return to the token', async () => {
    window.history.replaceState(null, '', '/')
    const lengthBefore = window.history.length
    window.history.pushState(null, '', `/upload#token=${TOKEN}`)
    expect(window.history.length).toBe(lengthBefore + 1)
    const router = routerAt()
    await router.push(window.location.pathname + window.location.hash)
    await router.isReady()
    const w = mount(Host, { global: { plugins: [router] } })
    mounted.push(w)
    await flushPromises()
    expect(window.history.length).toBe(lengthBefore + 1)
    expect(window.location.hash).toBe('')
  })

  it('leaves the entries it later pushes free of the token as well', async () => {
    const { router } = await openAt(`/upload#token=${TOKEN}`)
    await router!.push('/other')
    expect(JSON.stringify(window.history.state)).not.toContain(BODY)
    window.history.back()
    await new Promise((r) => setTimeout(r, 30))
    expect(JSON.stringify(window.history.state)).not.toContain(BODY)
    expect(window.location.href).not.toContain(BODY)
  })

  it('keeps the query string it finds, and ignores a token in it', async () => {
    await openAt(`/upload?lang=de&token=${TOKEN}`)
    // The query is never read: a token there would already be in every access log on its way in.
    expect(getUploaderToken()).toBeNull()
    expect(window.location.search).toBe(`?lang=de&token=${TOKEN}`)
  })

  it('reads the token among other parts of the fragment and removes them all', async () => {
    await openAt(`/upload#lang=de&token=${TOKEN}`)
    expect(getUploaderToken()).toBe(TOKEN)
    expect(window.location.hash).toBe('')
  })

  it('also works where there is no router around the page', async () => {
    await openAt(`/upload#token=${TOKEN}`, false)
    expect(getUploaderToken()).toBe(TOKEN)
    expect(window.location.hash).toBe('')
    expect(window.location.pathname).toBe('/upload')
  })

  it('never writes the token to the console', async () => {
    await openAt(`/upload#token=${TOKEN}`)
    await openAt(`/upload#token=${OTHER}`, false)
    expect(logged()).not.toContain(BODY)
    expect(logged()).not.toContain('B'.repeat(43))
  })

  it('never writes it to localStorage', async () => {
    await openAt(`/upload#token=${TOKEN}`)
    expect(local.length).toBe(0)
    expect(local.dump()).not.toContain(BODY)
  })
})

describe('a link that arrives after the page is open', () => {
  it('is taken when the fragment changes under the page, files and all', async () => {
    const { router } = await openAt('/upload')
    expect(getUploaderToken()).toBeNull()

    window.location.hash = `#token=${TOKEN}`
    await vi.waitFor(() => expect(getUploaderToken()).toBe(TOKEN))
    await vi.waitFor(() => expect(window.location.hash).toBe(''))
    expect(window.location.pathname).toBe('/upload')
    expect(JSON.stringify(window.history.state ?? null)).not.toContain(BODY)
    expect(router!.currentRoute.value.path).toBe('/upload')
  })

  it('replaces the token it had, announces the new one and forgets the complaint about the old', async () => {
    await openAt(`/upload#token=${TOKEN}`)
    reportTokenProblem('rejected')
    const version = tokenVersion.value

    window.location.hash = `#token=${OTHER}`
    await vi.waitFor(() => expect(getUploaderToken()).toBe(OTHER))
    expect(tokenVersion.value).toBe(version + 1)
    expect(tokenProblem.value).toBeNull()
  })

  it('is not taken any more once the page is gone', async () => {
    const { w } = await openAt('/upload')
    w.unmount()
    mounted.pop()
    window.location.hash = `#token=${TOKEN}`
    await new Promise((r) => setTimeout(r, 30))
    expect(getUploaderToken()).toBeNull()
  })
})

describe('a link that carries no usable token', () => {
  it('keeps nothing from a token of the wrong shape, says the link is not valid and still clears the address bar', async () => {
    await openAt('/upload#token=mwu_short')
    expect(getUploaderToken()).toBeNull()
    expect(tokenProblem.value).toBe('invalid-link')
    expect(window.location.hash).toBe('')
  })

  it('keeps the token it already had when a broken link comes after it', async () => {
    setUploaderToken(TOKEN)
    await openAt('/upload#token=nonsense')
    expect(getUploaderToken()).toBe(TOKEN)
  })

  it('does nothing without a fragment', async () => {
    await openAt('/upload')
    expect(getUploaderToken()).toBeNull()
    expect(tokenProblem.value).toBeNull()
    expect(window.location.pathname).toBe('/upload')
  })

  it('leaves a fragment that is no token alone: the skip link’s anchor, for one', async () => {
    await openAt('/upload#main-content')
    expect(window.location.hash).toBe('#main-content')
    expect(getUploaderToken()).toBeNull()
    expect(tokenProblem.value).toBeNull()
  })
})
