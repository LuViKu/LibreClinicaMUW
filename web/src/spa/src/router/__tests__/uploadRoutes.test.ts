/**
 * DR-029 — one upload page, reachable from the two older addresses.
 */
import { describe, expect, it } from 'vitest'

import router from '../index'

describe('upload routes', () => {
  it('serves the combined page without a login', () => {
    const r = router.resolve('/upload')
    expect(r.name).toBe('upload-portal')
    expect(r.meta.public).toBe(true)
  })

  it('redirects the OCT and image pages there, because they are in bookmarks', () => {
    for (const old of ['/oct-upload', '/image-upload']) {
      const r = router.resolve(old)
      const record = r.matched[r.matched.length - 1]
      expect(record?.redirect, old).toEqual({ name: 'upload-portal' })
    }
    expect(router.hasRoute('oct-upload-portal')).toBe(false)
    expect(router.hasRoute('image-upload-portal')).toBe(false)
  })

  it('offers the staff uploader under the inbox, gated like the inbox', () => {
    const r = router.resolve('/ingest-inbox/upload')
    expect(r.name).toBe('ingest-upload')
    expect(r.meta.public).toBeUndefined()
    const inbox = router.resolve('/ingest-inbox')
    expect(r.meta.role).toEqual(inbox.meta.role)
  })
})
