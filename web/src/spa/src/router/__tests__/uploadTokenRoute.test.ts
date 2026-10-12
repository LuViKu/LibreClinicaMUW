/**
 * The router and the uploader token's fragment.
 *
 * The public upload page is opened with `/upload#token=mwu_…`. The older
 * paths on the QR codes at the cameras redirect to it and must keep the
 * fragment, and the router must not treat the fragment as an anchor: looking
 * an element up by it would put the token into a development-build warning,
 * and removing it from the address bar afterwards must not scroll the page.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'

import router from '../index'

const TOKEN = 'mwu_' + 'abcXYZ019-_'.repeat(5).slice(0, 43)

function at(path: string, hash = ''): RouteLocationNormalized {
  return { path, hash, fullPath: path + hash, name: undefined, params: {}, query: {}, meta: {}, matched: [], redirectedFrom: undefined } as RouteLocationNormalized
}

type Scroll = (to: RouteLocationNormalized, from: RouteLocationNormalized, saved: unknown) => unknown
const scrollBehavior = router.options.scrollBehavior as unknown as Scroll

describe('the upload page route', () => {
  // The route guard reads the auth store.
  beforeEach(() => { setActivePinia(createPinia()) })

  it('is public, so the person at the acquisition PC needs no login', () => {
    const r = router.resolve('/upload')
    expect(r.name).toBe('upload-portal')
    expect(r.meta.public).toBe(true)
  })

  it.each(['/oct-upload', '/image-upload'])('keeps the fragment when %s redirects to the page', async (old) => {
    await router.replace('/contact')
    await router.push(`${old}#token=${TOKEN}`)
    expect(router.currentRoute.value.path).toBe('/upload')
    expect(router.currentRoute.value.hash).toBe(`#token=${TOKEN}`)
    await router.replace('/contact')
  })
})

describe('scrolling', () => {
  it('does not look for an element called after a token', () => {
    expect(scrollBehavior(at('/upload', `#token=${TOKEN}`), at('/'), null)).toBe(false)
    expect(scrollBehavior(at('/upload', '#token=garbage'), at('/upload'), null)).toBe(false)
  })

  it('does not scroll when the token is taken out of the address bar again', () => {
    expect(scrollBehavior(at('/upload'), at('/upload', `#token=${TOKEN}`), null)).toBe(false)
  })

  it('still scrolls to a real anchor, and to the top otherwise', () => {
    expect(scrollBehavior(at('/subjects', '#events'), at('/'), null)).toEqual({ el: '#events', behavior: 'smooth' })
    expect(scrollBehavior(at('/subjects'), at('/'), null)).toEqual({ top: 0 })
    // Leaving the upload page for another path is an ordinary navigation.
    expect(scrollBehavior(at('/subjects'), at('/upload', `#token=${TOKEN}`), null)).toEqual({ top: 0 })
  })

  it('keeps the saved position of the back button first', () => {
    const saved = { left: 0, top: 120 }
    expect(scrollBehavior(at('/upload', `#token=${TOKEN}`), at('/'), saved)).toBe(saved)
  })
})
