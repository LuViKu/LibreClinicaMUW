import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import de from '@/locales/de.json'
import AuditValueChange from '../AuditValueChange.vue'

/**
 * An audit row's values. Pinned: both values show side by side; a row with
 * one value shows it, labelled by which side it is on; a row with none
 * shows nothing. Rows with a single value, such as a failure's error, used
 * to show nothing at all.
 */

function mountWith(props: { before?: string | null; after?: string | null }) {
  const i18n = createI18n({ legacy: false, locale: 'de', messages: { de } })
  return mount(AuditValueChange, { props, global: { plugins: [i18n] } })
}

describe('AuditValueChange', () => {
  it('shows before and after side by side', () => {
    const w = mountWith({ before: '2026-09-23 00:00:00', after: '2026-09-24 00:00:00' })
    expect(w.text()).toContain('Vorher')
    expect(w.text()).toContain('2026-09-23 00:00:00')
    expect(w.text()).toContain('Nachher')
    expect(w.text()).toContain('2026-09-24 00:00:00')
    expect(w.find('[data-testid="audit-single-value"]').exists()).toBe(false)
  })

  it('shows a lone new value, such as a failure, as the value', () => {
    const w = mountWith({ before: null, after: 'java.sql.SQLException: boom · request r-1' })
    const box = w.get('[data-testid="audit-single-value"]')
    expect(box.text()).toContain('Wert')
    expect(box.text()).toContain('java.sql.SQLException: boom · request r-1')
  })

  it('shows a lone old value, such as a deleted one, as before', () => {
    const w = mountWith({ before: '42', after: null })
    const box = w.get('[data-testid="audit-single-value"]')
    expect(box.text()).toContain('Vorher')
    expect(box.text()).toContain('42')
  })

  it('shows nothing without values', () => {
    const w = mountWith({})
    expect(w.text()).toBe('')
  })
})
