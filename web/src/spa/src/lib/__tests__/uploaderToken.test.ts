/**
 * The uploader token of the public upload page: its shape, where it may
 * come from (the URL fragment and nowhere else), where it may live
 * (sessionStorage and nowhere else), and what the server's 401 about it says.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { i18n } from '@/i18n'
import { stubWebStorage, type MemoryStorage } from '@/test/memoryStorage'
import {
  UPLOADER_TOKEN_HEADER,
  UploadTokenError,
  clearUploaderToken,
  getUploaderToken,
  isTokenHash,
  isUploaderToken,
  reportTokenProblem,
  setUploaderToken,
  tokenFromHash,
  tokenMessage,
  tokenProblem,
  tokenReasonOf,
  tokenVersion,
  uploadPageLink,
} from '../uploaderToken'

/** 4 + 43 characters, with the two non-alphanumeric base64url ones in it. */
const BODY = 'abcXYZ019-_'.repeat(5).slice(0, 43)
const TOKEN = `mwu_${BODY}`
const KEY = 'muw.uploaderToken'

let session: MemoryStorage
let local: MemoryStorage

beforeEach(() => {
  ;({ session, local } = stubWebStorage())
  clearUploaderToken()
  reportTokenProblem(null)
})

afterEach(() => {
  vi.unstubAllGlobals()
  i18n.global.locale.value = 'de-AT'
})

describe('what a token looks like', () => {
  it('is mwu_ and 43 base64url characters', () => {
    expect(TOKEN.length).toBe(47)
    expect(isUploaderToken(TOKEN)).toBe(true)
    expect(isUploaderToken('mwu_' + 'A'.repeat(43))).toBe(true)
    expect(isUploaderToken('mwu_' + '-'.repeat(43))).toBe(true)
    expect(isUploaderToken('mwu_' + '_'.repeat(43))).toBe(true)
  })

  it.each([
    ['too short', 'mwu_' + 'A'.repeat(42)],
    ['too long', 'mwu_' + 'A'.repeat(44)],
    ['another prefix', 'mwx_' + 'A'.repeat(43)],
    ['no prefix', 'A'.repeat(47)],
    ['plus', 'mwu_' + 'A'.repeat(42) + '+'],
    ['slash', 'mwu_' + 'A'.repeat(42) + '/'],
    ['equals', 'mwu_' + 'A'.repeat(42) + '='],
    ['space', 'mwu_' + 'A'.repeat(42) + ' '],
    ['a newline at the end', TOKEN + '\n'],
    ['upper-case prefix', 'MWU_' + 'A'.repeat(43)],
    ['empty', ''],
  ])('is not one when it is %s', (_label, value) => {
    expect(isUploaderToken(value)).toBe(false)
  })

  it('is not one when it is not a string', () => {
    for (const v of [null, undefined, 42, {}, ['mwu_'], true]) expect(isUploaderToken(v)).toBe(false)
  })
})

describe('reading the URL fragment', () => {
  it('finds a valid token', () => {
    expect(tokenFromHash(`#token=${TOKEN}`)).toEqual({ present: true, token: TOKEN })
    expect(tokenFromHash(`token=${TOKEN}`)).toEqual({ present: true, token: TOKEN })
  })

  it('finds it among other parts of the fragment', () => {
    expect(tokenFromHash(`#lang=de&token=${TOKEN}&x=1`)).toEqual({ present: true, token: TOKEN })
    expect(tokenFromHash(`#token=${TOKEN}&x=1`)).toEqual({ present: true, token: TOKEN })
  })

  it('says a token is there but unusable when it has the wrong shape', () => {
    for (const bad of ['', 'abc', 'mwu_short', 'mwu_' + 'A'.repeat(44), TOKEN + 'x', 'mwu_' + 'A'.repeat(42) + '!']) {
      expect(tokenFromHash(`#token=${bad}`), bad).toEqual({ present: true, token: null })
    }
    expect(tokenFromHash('#token')).toEqual({ present: true, token: null })
  })

  it('does not decode what it reads: a percent-encoded token is not a token', () => {
    expect(tokenFromHash(`#token=%6Dwu_${BODY}`)).toEqual({ present: true, token: null })
  })

  it('takes the first token it meets and no other key for one', () => {
    const other = 'mwu_' + 'B'.repeat(43)
    expect(tokenFromHash(`#token=${TOKEN}&token=${other}`).token).toBe(TOKEN)
    expect(tokenFromHash(`#access_token=${TOKEN}`)).toEqual({ present: false, token: null })
    expect(tokenFromHash(`#mytoken=${TOKEN}`)).toEqual({ present: false, token: null })
  })

  it('finds nothing in a fragment that is not about a token', () => {
    for (const hash of ['', '#', '#main-content', '#events', '#/upload', '#a=b']) {
      expect(tokenFromHash(hash), hash).toEqual({ present: false, token: null })
    }
  })

  it('knows a token fragment when the router must not treat it as an anchor', () => {
    expect(isTokenHash(`#token=${TOKEN}`)).toBe(true)
    expect(isTokenHash('#token=garbage')).toBe(true)
    expect(isTokenHash('#main-content')).toBe(false)
    expect(isTokenHash('')).toBe(false)
    expect(isTokenHash(undefined)).toBe(false)
    expect(isTokenHash(null)).toBe(false)
  })
})

describe('keeping the token', () => {
  it('keeps it in sessionStorage', () => {
    expect(setUploaderToken(TOKEN)).toBe(true)
    expect(session.getItem(KEY)).toBe(TOKEN)
    expect(getUploaderToken()).toBe(TOKEN)
  })

  it('never puts it in localStorage', () => {
    setUploaderToken(TOKEN)
    expect(local.length).toBe(0)
    expect(local.dump()).not.toContain(BODY)
    expect(local.dump()).not.toContain('mwu_')
  })

  it('keeps nothing that is not a token', () => {
    expect(setUploaderToken('mwu_short')).toBe(false)
    expect(setUploaderToken('')).toBe(false)
    expect(session.length).toBe(0)
    expect(getUploaderToken()).toBeNull()
  })

  it('does not trust what sessionStorage holds: only a token passes', () => {
    session.setItem(KEY, 'something else')
    expect(getUploaderToken()).toBeNull()
    session.setItem(KEY, TOKEN + 'x')
    expect(getUploaderToken()).toBeNull()
  })

  it('is empty when nothing was ever kept, and again after it is cleared', () => {
    expect(getUploaderToken()).toBeNull()
    setUploaderToken(TOKEN)
    clearUploaderToken()
    expect(getUploaderToken()).toBeNull()
    expect(session.getItem(KEY)).toBeNull()
  })

  it('replaces an older token with a newer one', () => {
    const other = 'mwu_' + 'B'.repeat(43)
    setUploaderToken(TOKEN)
    setUploaderToken(other)
    expect(getUploaderToken()).toBe(other)
  })

  it('counts each accepted token and forgets a complaint about the old one', () => {
    const before = tokenVersion.value
    reportTokenProblem('rejected')
    setUploaderToken(TOKEN)
    expect(tokenVersion.value).toBe(before + 1)
    expect(tokenProblem.value).toBeNull()
    // A refused token changes nothing.
    reportTokenProblem('required')
    setUploaderToken('nope')
    expect(tokenVersion.value).toBe(before + 1)
    expect(tokenProblem.value).toBe('required')
  })

  it('keeps it in the page’s memory, and nowhere else, when the browser denies sessionStorage', () => {
    const real = Object.getOwnPropertyDescriptor(window, 'sessionStorage')!
    Object.defineProperty(window, 'sessionStorage', {
      configurable: true,
      get() { throw new DOMException('denied', 'SecurityError') },
    })
    try {
      expect(setUploaderToken(TOKEN)).toBe(true)
      expect(getUploaderToken()).toBe(TOKEN)
      expect(local.dump()).not.toContain(BODY)
      clearUploaderToken()
      expect(getUploaderToken()).toBeNull()
    } finally {
      Object.defineProperty(window, 'sessionStorage', real)
    }
  })
})

describe('what the server’s 401 says', () => {
  it('knows its two answers about the token', () => {
    expect(tokenReasonOf(401, { message: 'uploader token required' })).toBe('required')
    expect(tokenReasonOf(401, { message: 'uploader token rejected' })).toBe('rejected')
  })

  it('does not mind case or padding', () => {
    expect(tokenReasonOf(401, { message: '  Uploader Token Required ' })).toBe('required')
    expect(tokenReasonOf(401, { message: 'UPLOADER TOKEN REJECTED' })).toBe('rejected')
  })

  it('leaves every other answer alone', () => {
    expect(tokenReasonOf(401, { message: 'Unauthorized' })).toBeNull()
    expect(tokenReasonOf(401, { message: 'uploader token required, please' })).toBeNull()
    expect(tokenReasonOf(401, {})).toBeNull()
    expect(tokenReasonOf(401, null)).toBeNull()
    expect(tokenReasonOf(401, 'uploader token required')).toBeNull()
    expect(tokenReasonOf(401, { message: 42 })).toBeNull()
    // Only a 401 is about the token.
    expect(tokenReasonOf(403, { message: 'uploader token required' })).toBeNull()
    expect(tokenReasonOf(200, { message: 'uploader token rejected' })).toBeNull()
    expect(tokenReasonOf(429, { message: 'uploader token required' })).toBeNull()
  })
})

describe('the page’s own words', () => {
  it('say in German what is wrong and what to do', () => {
    i18n.global.locale.value = 'de-AT'
    expect(tokenMessage('required')).toBe(
      'Diese Seite braucht einen Uploader-Token. Bitte den Link verwenden, den Ihr Standort-Admin Ihnen gegeben hat.',
    )
    expect(tokenMessage('rejected')).toBe(
      'Der Token wurde abgelehnt oder gesperrt. Bitte beim Standort-Admin einen neuen Link anfordern.',
    )
    expect(tokenMessage('invalid-link')).toContain('Der Token im Link ist unvollständig oder ungültig.')
  })

  it('and in English', () => {
    i18n.global.locale.value = 'en'
    expect(tokenMessage('required')).toBe('This page needs an uploader token. Please use the link your location admin gave you.')
    expect(tokenMessage('rejected')).toBe('The token was rejected or revoked. Please ask your location admin for a new link.')
  })

  it('are the message of the error, so that anything showing the message shows them', () => {
    const e = new UploadTokenError('required', { message: 'uploader token required' })
    expect(e).toBeInstanceOf(Error)
    expect(e.name).toBe('UploadTokenError')
    expect(e.status).toBe(401)
    expect(e.reason).toBe('required')
    expect(e.message).toBe(tokenMessage('required'))
    expect(e.body).toEqual({ message: 'uploader token required' })
  })

  it('never carry the token', () => {
    setUploaderToken(TOKEN)
    const e = new UploadTokenError('rejected', { message: 'uploader token rejected' })
    expect(`${e.message} ${e.name} ${JSON.stringify(e.body)} ${e.stack ?? ''}`).not.toContain(BODY)
  })
})

describe('the link for the upload page', () => {
  it('is origin, base path, upload and the token in the fragment', () => {
    expect(uploadPageLink('https://ecrf.example.org', '/', TOKEN)).toBe(`https://ecrf.example.org/upload#token=${TOKEN}`)
    expect(uploadPageLink('https://ecrf.example.org', '/LibreClinica/app/', TOKEN))
      .toBe(`https://ecrf.example.org/LibreClinica/app/upload#token=${TOKEN}`)
  })

  it('does not mind a base without its trailing slash, or an origin with one', () => {
    expect(uploadPageLink('https://ecrf.example.org/', '/app', TOKEN)).toBe(`https://ecrf.example.org/app/upload#token=${TOKEN}`)
  })

  it('puts the token in the fragment, never in the query', () => {
    const link = uploadPageLink('https://ecrf.example.org', '/', TOKEN)
    expect(new URL(link).search).toBe('')
    expect(new URL(link).hash).toBe(`#token=${TOKEN}`)
  })
})

it('names the header the public upload client sends', () => {
  expect(UPLOADER_TOKEN_HEADER).toBe('X-MUW-Uploader-Token')
})
