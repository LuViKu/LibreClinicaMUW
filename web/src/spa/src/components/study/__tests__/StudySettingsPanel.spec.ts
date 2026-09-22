import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import de from '@/locales/de.json'

/**
 * P3.5 — what this study does, as switches.
 *
 * The behaviour worth pinning is the three-state one. "Not set" is distinct
 * from explicitly on or off: it means the platform decides, which is what
 * every untouched study has always had. Collapsing it into a checkbox would
 * make every study look configured and turn the first save into a decision
 * the administrator never made.
 */

const apiGet = vi.fn()
const apiPut = vi.fn()
vi.mock('@/api/client', () => ({
  apiGet: (...a: unknown[]) => apiGet(...a),
  apiPut: (...a: unknown[]) => apiPut(...a),
}))

import StudySettingsPanel from '../StudySettingsPanel.vue'

function response(over: Partial<Record<string, unknown>> = {}) {
  return {
    studyOid: 'S_HAE',
    settings: [
      { key: 'ingest.dicom.enabled', value: 'true', resolved: 'true' },
      { key: 'ingest.image.enabled', value: null, resolved: 'true' },
      { key: 'ingest.oct.enabled', value: null, resolved: 'false' },
      { key: 'inference.enabled', value: 'false', resolved: 'false' },
      { key: 'export.bundle.enabled', value: null, resolved: 'false' },
      { key: 'portal.todaysVisits', value: null, resolved: 'false' },
      { key: 'ai.arm.shownGroup', value: null, resolved: 'AI_SHOWN' },
      { key: 'ai.arm.hiddenGroup', value: null, resolved: 'AI_HIDDEN' },
    ],
    itemBindings: { 'visit.crf': 'F_NAMD_VISIT' },
    ...over,
  }
}

function mountPanel() {
  const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
  return mount(StudySettingsPanel, {
    props: { studyOid: 'S_HAE' },
    global: { plugins: [i18n] },
  })
}

describe('StudySettingsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiGet.mockResolvedValue(response())
    apiPut.mockResolvedValue(response())
  })

  it('loads this study’s settings', async () => {
    mountPanel()
    await flushPromises()
    expect(apiGet).toHaveBeenCalledWith(
      expect.stringContaining('/studies/S_HAE/settings'),
    )
  })

  /**
   * An unset key shows "not set" AND the answer the platform will use. Showing
   * only the latter would make every study look configured.
   */
  it('distinguishes not-set from explicitly off', async () => {
    const w = mountPanel()
    await flushPromises()
    const unset = w.get('[data-testid="setting-ingest.image.enabled"]')
      .element as HTMLSelectElement
    expect(unset.value).toBe('')
    const explicit = w.get('[data-testid="setting-inference.enabled"]')
      .element as HTMLSelectElement
    expect(explicit.value).toBe('false')
    // …and the effective value is shown beside it, marked as inherited.
    expect(w.text()).toContain(de.studySettings.inherited)
  })

  it('saves nothing until something is changed', async () => {
    const w = mountPanel()
    await flushPromises()
    const btn = w.get('[data-testid="save-study-settings"]').element as HTMLButtonElement
    expect(btn.disabled).toBe(true)
  })

  it('sends only the keys that were touched', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="setting-ingest.oct.enabled"]').setValue('true')
    await w.get('[data-testid="save-study-settings"]').trigger('click')
    await flushPromises()
    expect(apiPut).toHaveBeenCalledWith(
      expect.stringContaining('/studies/S_HAE/settings'),
      { settings: { 'ingest.oct.enabled': 'true' } },
    )
  })

  /** Clearing is a real edit: it restores "as before", not today's default. */
  it('can clear a setting back to not-set', async () => {
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="setting-inference.enabled"]').setValue('')
    await w.get('[data-testid="save-study-settings"]').trigger('click')
    await flushPromises()
    expect(apiPut).toHaveBeenCalledWith(
      expect.anything(),
      { settings: { 'inference.enabled': '' } },
    )
  })

  it('surfaces a failed save rather than looking saved', async () => {
    apiPut.mockRejectedValue(new Error('403 forbidden'))
    const w = mountPanel()
    await flushPromises()
    await w.get('[data-testid="setting-ingest.oct.enabled"]').setValue('true')
    await w.get('[data-testid="save-study-settings"]').trigger('click')
    await flushPromises()
    expect(w.get('[data-testid="study-settings-error"]').text()).toContain('403')
    expect(w.find('[data-testid="study-settings-saved"]').exists()).toBe(false)
  })

  it('says how many item bindings the study has', async () => {
    const w = mountPanel()
    await flushPromises()
    expect(w.text()).toContain('1')
  })
})
