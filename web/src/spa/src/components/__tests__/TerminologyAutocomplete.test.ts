/**
 * #26 (2026-08-12) — TerminologyAutocomplete spec.
 *
 * Pins the reusable coded-text-field contract: a ≥2-char query hits the
 * terminology search endpoint, results render as a listbox, free text is a
 * valid value, and picking a suggestion emits BOTH the "CODE — Display"
 * value AND the parsed properties (so a caller can fan strength/unit out
 * into sibling fields).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises, DOMWrapper, enableAutoUnmount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})

import { apiGet } from '@/api/client'
import TerminologyAutocomplete from '@/components/TerminologyAutocomplete.vue'
import enMessages from '@/locales/en.json'

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function mountAuto(props: Record<string, unknown> = {}) {
  return mount(TerminologyAutocomplete, {
    props: { modelValue: '', system: 'medication', id: 'ta', ...props },
    global: { plugins: [i18n] },
  })
}

// The results list is teleported to <body>; auto-unmount so each test's
// teleported nodes are cleaned up before the next.
enableAutoUnmount(afterEach)
const body = () => new DOMWrapper(document.body)

beforeEach(() => {
  vi.mocked(apiGet).mockReset()
})

describe('TerminologyAutocomplete', () => {
  it('does not query for a <2-char prefix', async () => {
    const w = mountAuto()
    await w.find('input').setValue('a')
    await new Promise((r) => setTimeout(r, 260))
    expect(apiGet).not.toHaveBeenCalled()
    // free text still propagates as the model value
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual(['a'])
  })

  it('queries the terminology endpoint and renders results', async () => {
    vi.mocked(apiGet).mockResolvedValue([
      { code: 'B01AC06', display: 'Acetylsalicylsäure', properties: '{"strength":"100","unit":"mg"}' },
    ])
    const w = mountAuto()
    await w.find('input').setValue('aspir')
    await new Promise((r) => setTimeout(r, 260))
    await flushPromises()

    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('/terminology/search?system=medication&q=aspir'))
    const list = body().find('[data-testid="terminology-autocomplete-list"]')
    expect(list.exists()).toBe(true)
    expect(list.text()).toContain('Acetylsalicylsäure')
  })

  it('emits value + parsed properties on pick', async () => {
    vi.mocked(apiGet).mockResolvedValue([
      { code: 'B01AC06', display: 'Acetylsalicylsäure', properties: '{"strength":"100","unit":"mg"}' },
    ])
    const w = mountAuto()
    await w.find('input').setValue('aspir')
    await new Promise((r) => setTimeout(r, 260))
    await flushPromises()

    await body().find('[role="option"]').trigger('mousedown')

    // The stored value is the drug name only; the code rides along as a `code`
    // fill property (so it can populate a dedicated column instead).
    expect(w.emitted('update:modelValue')?.at(-1)).toEqual(['Acetylsalicylsäure'])
    const pick = w.emitted('pick')?.at(-1)?.[0] as { code: string; value: string; properties: Record<string, string> }
    expect(pick.code).toBe('B01AC06')
    expect(pick.value).toBe('Acetylsalicylsäure')
    expect(pick.properties).toEqual({ strength: '100', unit: 'mg', code: 'B01AC06' })
  })

  it('survives non-JSON / FHIR-array properties (no crash, empty props)', async () => {
    vi.mocked(apiGet).mockResolvedValue([
      { code: 'H40', display: 'Glaukom', properties: '[{"code":"classKind"}]' },
    ])
    const w = mountAuto({ system: 'icd10gm' })
    await w.find('input').setValue('glauk')
    await new Promise((r) => setTimeout(r, 260))
    await flushPromises()
    await body().find('[role="option"]').trigger('mousedown')
    const pick = w.emitted('pick')?.at(-1)?.[0] as { properties: Record<string, string> }
    // No JSONB properties survive the non-object shape, but the code is still
    // surfaced (the diagnosis ICD-code fill source).
    expect(pick.properties).toEqual({ code: 'H40' })
  })
})
