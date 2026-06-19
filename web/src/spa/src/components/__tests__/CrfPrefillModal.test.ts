/**
 * App-feedback Wave 1D (2026-06-19) — CrfPrefillModal spec.
 *
 * <p>Pins the modal's contract surface:
 *
 * <ul>
 *   <li>Renders a per-row checkbox table when the GET response
 *       carries values. Default-checks every row so the common
 *       case (apply all) is one click.</li>
 *   <li>Emits an {@code apply} event with only the checked rows
 *       on confirm.</li>
 *   <li>Renders the empty state on 404.</li>
 *   <li>Renders an error message on 5xx.</li>
 *   <li>Closes on cancel without emitting apply.</li>
 * </ul>
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

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

import { apiGet, ApiError } from '@/api/client'
import CrfPrefillModal from '@/components/CrfPrefillModal.vue'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de',
  fallbackLocale: 'de',
  messages: { de: deMessages },
})

function mountModal(open = true) {
  return mount(CrfPrefillModal, {
    props: { open, currentEventCrfId: '42' },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('CrfPrefillModal', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(apiGet).mockReset()
  })

  it('renders the per-row checkbox table on a successful fetch', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      sourceEventCrfId: 17,
      sourceCompletedAt: '2026-03-15T12:00:00Z',
      values: [
        { itemOid: 'I_AGE', value: '45', itemLabel: 'Alter' },
        { itemOid: 'I_SEX', value: 'M', itemLabel: 'Geschlecht' },
      ],
    })
    const w = mountModal()
    await flushPromises()
    expect(w.find('[data-testid="prefill-table"]').exists()).toBe(true)
    expect(w.find('[data-testid="prefill-row-I_AGE"]').exists()).toBe(true)
    expect(w.find('[data-testid="prefill-row-I_SEX"]').exists()).toBe(true)
    // Default-checked: both checkboxes start checked so the common
    // "apply all" path is one click.
    const c1 = w.find<HTMLInputElement>('[data-testid="prefill-checkbox-I_AGE"]')
    const c2 = w.find<HTMLInputElement>('[data-testid="prefill-checkbox-I_SEX"]')
    expect(c1.element.checked).toBe(true)
    expect(c2.element.checked).toBe(true)
    w.unmount()
  })

  it('emits apply with only the checked rows on confirm', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      sourceEventCrfId: 17,
      sourceCompletedAt: '2026-03-15T12:00:00Z',
      values: [
        { itemOid: 'I_AGE', value: '45', itemLabel: 'Alter' },
        { itemOid: 'I_SEX', value: 'M', itemLabel: 'Geschlecht' },
      ],
    })
    const w = mountModal()
    await flushPromises()
    // Uncheck the I_SEX row.
    const c2 = w.find<HTMLInputElement>('[data-testid="prefill-checkbox-I_SEX"]')
    await c2.setValue(false)
    // Confirm.
    await w.find('[data-testid="prefill-confirm"]').trigger('click')
    const emits = w.emitted('apply')
    expect(emits).toBeTruthy()
    expect(emits?.[0][0]).toEqual({ I_AGE: '45' })
    w.unmount()
  })

  it('emits close without apply on cancel', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      sourceEventCrfId: 17,
      sourceCompletedAt: '2026-03-15T12:00:00Z',
      values: [{ itemOid: 'I_AGE', value: '45', itemLabel: 'Alter' }],
    })
    const w = mountModal()
    await flushPromises()
    await w.find('[data-testid="prefill-cancel"]').trigger('click')
    expect(w.emitted('apply')).toBeFalsy()
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })

  it('renders the empty state on a 404 (no prior visit)', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(
      new ApiError(404, 'Not Found', { message: 'No prior CRF' }, 'req-x'),
    )
    const w = mountModal()
    await flushPromises()
    expect(w.find('[data-testid="prefill-empty"]').exists()).toBe(true)
    expect(w.find('[data-testid="prefill-table"]').exists()).toBe(false)
    // Confirm button stays disabled in the empty state so the operator
    // can't accidentally apply an empty map.
    const confirm = w.find<HTMLButtonElement>('[data-testid="prefill-confirm"]')
    expect(confirm.attributes('disabled')).not.toBeUndefined()
    w.unmount()
  })

  it('renders an error on 5xx', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(
      new ApiError(500, 'Server Error', { message: 'oops' }, 'req-y'),
    )
    const w = mountModal()
    await flushPromises()
    expect(w.find('[data-testid="prefill-error"]').exists()).toBe(true)
    expect(w.text()).toContain('oops')
    w.unmount()
  })

  it('check-all / uncheck-all toggle every row', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      sourceEventCrfId: 17,
      sourceCompletedAt: '2026-03-15T12:00:00Z',
      values: [
        { itemOid: 'I_AGE', value: '45', itemLabel: 'Alter' },
        { itemOid: 'I_SEX', value: 'M', itemLabel: 'Geschlecht' },
      ],
    })
    const w = mountModal()
    await flushPromises()
    await w.find('[data-testid="prefill-uncheck-all"]').trigger('click')
    expect(
      w.find<HTMLInputElement>('[data-testid="prefill-checkbox-I_AGE"]').element.checked,
    ).toBe(false)
    expect(
      w.find<HTMLInputElement>('[data-testid="prefill-checkbox-I_SEX"]').element.checked,
    ).toBe(false)
    await w.find('[data-testid="prefill-check-all"]').trigger('click')
    expect(
      w.find<HTMLInputElement>('[data-testid="prefill-checkbox-I_AGE"]').element.checked,
    ).toBe(true)
    w.unmount()
  })
})
