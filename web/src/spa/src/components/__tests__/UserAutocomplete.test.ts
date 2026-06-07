/**
 * Phase E.6 DN — UserAutocomplete spec.
 *
 * Pins the assignee-picker contract used by the DN reviewer + CRF
 * assignee surfaces: load gating, substring filtering across
 * displayName / username / email, keyboard navigation, and the
 * emit shape (`username` on pick, `''` on clear).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { nextTick } from 'vue'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
  }
})

import { apiGet } from '@/api/client'
import UserAutocomplete from '@/components/UserAutocomplete.vue'
import { useUsersStore } from '@/stores/users'
import type { StudyUser } from '@/types/user'
import enMessages from '@/locales/en.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages },
})

function user(partial: Partial<StudyUser> & Pick<StudyUser, 'username' | 'displayName'>): StudyUser {
  return {
    id: partial.username,
    username: partial.username,
    displayName: partial.displayName,
    email: partial.email ?? `${partial.username}@example.org`,
    role: partial.role ?? 'Investigator',
    siteLabel: partial.siteLabel ?? null,
    auth: partial.auth ?? 'local',
    lastLoginAt: partial.lastLoginAt ?? null,
    active: partial.active ?? true,
    locked: partial.locked ?? false,
  } as StudyUser
}

const ROWS: StudyUser[] = [
  user({ username: 'agruber',  displayName: 'Anna Gruber',   email: 'anna.gruber@meduniwien.ac.at' }),
  user({ username: 'bschmidt', displayName: 'Bernhard Schmidt', email: 'bs@example.org' }),
  user({ username: 'cmayer',   displayName: 'Clara Mayer',   email: 'clara@example.org' }),
  user({ username: 'dhuber',   displayName: 'Daniel Huber',  email: 'dh@example.org' }),
]

function seedRows(rows: StudyUser[] = ROWS) {
  const store = useUsersStore()
  store.rows = [...rows]
  return store
}

function mountAuto(modelValue: string | null = null) {
  return mount(UserAutocomplete, {
    props: { modelValue, id: 'ua-test' },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('UserAutocomplete — Phase E.6 DN', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
    vi.mocked(apiGet).mockResolvedValue([])
  })

  it('calls users.load() on first mount and skips it when rows are already cached', async () => {
    // First mount: rows empty → load() fires once.
    const w1 = mountAuto()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(vi.mocked(apiGet).mock.calls[0]![0]).toBe('/pages/api/v1/users')
    w1.unmount()

    // Seed the cache. A second mount must NOT refetch.
    seedRows()
    const w2 = mountAuto()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledTimes(1)
    w2.unmount()
  })

  it('filters rows by displayName / username / email (case-insensitive substring)', async () => {
    seedRows()
    const w = mountAuto()
    await flushPromises()

    const input = document.body.querySelector('#ua-test') as HTMLInputElement
    input.dispatchEvent(new FocusEvent('focus'))
    await nextTick()

    // No query → all 4 rows visible (capped at 10).
    expect(document.body.querySelectorAll('[role="option"]').length).toBe(4)

    // Substring on displayName.
    input.value = 'clar'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    let opts = Array.from(document.body.querySelectorAll('[role="option"]')) as HTMLElement[]
    expect(opts.length).toBe(1)
    expect(opts[0]!.textContent).toContain('Clara Mayer')

    // Substring on username (case-insensitive).
    input.value = 'AGRU'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    opts = Array.from(document.body.querySelectorAll('[role="option"]')) as HTMLElement[]
    expect(opts.length).toBe(1)
    expect(opts[0]!.textContent).toContain('agruber')

    // Substring on email domain.
    input.value = 'meduniwien'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    opts = Array.from(document.body.querySelectorAll('[role="option"]')) as HTMLElement[]
    expect(opts.length).toBe(1)
    expect(opts[0]!.textContent).toContain('Anna Gruber')

    w.unmount()
  })

  it('caps the dropdown at the first 10 matches', async () => {
    const many: StudyUser[] = Array.from({ length: 25 }, (_, i) =>
      user({ username: `u${String(i).padStart(2, '0')}`, displayName: `User ${i}` }),
    )
    seedRows(many)
    const w = mountAuto()
    await flushPromises()

    const input = document.body.querySelector('#ua-test') as HTMLInputElement
    input.dispatchEvent(new FocusEvent('focus'))
    await nextTick()
    expect(document.body.querySelectorAll('[role="option"]').length).toBe(10)
    w.unmount()
  })

  it('emits update:modelValue with the username when an option is clicked', async () => {
    seedRows()
    const w = mountAuto()
    await flushPromises()

    const input = document.body.querySelector('#ua-test') as HTMLInputElement
    input.dispatchEvent(new FocusEvent('focus'))
    await nextTick()

    const opts = Array.from(document.body.querySelectorAll('[role="option"]')) as HTMLElement[]
    const claraIdx = opts.findIndex((el) => el.textContent?.includes('Clara Mayer'))
    expect(claraIdx).toBeGreaterThanOrEqual(0)
    opts[claraIdx]!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))
    await nextTick()

    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted!.at(-1)).toEqual(['cmayer'])
    w.unmount()
  })

  it("emits update:modelValue with '' when the input is cleared", async () => {
    seedRows()
    const w = mountAuto('cmayer')
    await flushPromises()

    const input = document.body.querySelector('#ua-test') as HTMLInputElement
    input.value = ''
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()

    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted!.at(-1)).toEqual([''])
    w.unmount()
  })

  it('arrow-down/up moves the highlighted row, Enter picks it', async () => {
    seedRows()
    const w = mountAuto()
    await flushPromises()

    const input = document.body.querySelector('#ua-test') as HTMLInputElement
    input.dispatchEvent(new FocusEvent('focus'))
    await nextTick()

    const fire = (key: string) => {
      input.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
    }

    // Initial highlight is row 0 (Anna). ArrowDown → row 1 (Bernhard).
    fire('ArrowDown')
    await nextTick()
    let highlighted = document.body.querySelector('[role="option"][aria-selected="true"]') as HTMLElement
    expect(highlighted.textContent).toContain('Bernhard Schmidt')

    // ArrowDown again → row 2 (Clara).
    fire('ArrowDown')
    await nextTick()
    highlighted = document.body.querySelector('[role="option"][aria-selected="true"]') as HTMLElement
    expect(highlighted.textContent).toContain('Clara Mayer')

    // ArrowUp → back to row 1 (Bernhard).
    fire('ArrowUp')
    await nextTick()
    highlighted = document.body.querySelector('[role="option"][aria-selected="true"]') as HTMLElement
    expect(highlighted.textContent).toContain('Bernhard Schmidt')

    // Enter picks the highlighted row.
    fire('Enter')
    await nextTick()
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted!.at(-1)).toEqual(['bschmidt'])
    w.unmount()
  })
})
