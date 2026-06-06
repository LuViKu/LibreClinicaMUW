/**
 * Phase E.6 polish — CrfAuthoringWizard collapse/expand + locale spec.
 *
 * Pins three load-bearing UX rules introduced by the polish-ux batch:
 *
 *  1. The wizard subheading reads "Create CRF definition" (was a
 *     Milestone-A-vintage technical blurb).
 *
 *  2. The vestigial Milestone-A "scopeNote" card is gone — both the
 *     i18n key and the rendered element.
 *
 *  3. Sections + items in the Sections step start collapsed; clicking
 *     the chevron expands the body. Newly added items / sections
 *     auto-expand so the operator can start typing immediately.
 *     Reorder still works from a collapsed state because the drag
 *     handle lives in the header.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

// The wizard pulls the response-set catalog on mount + on open. The
// catalog isn't exercised by the collapse tests, so stub the client.
vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return {
    ...actual,
    apiGet: vi.fn().mockResolvedValue([]),
    apiPost: vi.fn().mockResolvedValue({}),
  }
})

import CrfAuthoringWizard from '@/components/CrfAuthoringWizard.vue'
import { useCrfAuthoringStore } from '@/stores/crfAuthoring'
import enMessages from '@/locales/en.json'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en: enMessages, de: deMessages },
})

function mountWizard() {
  return mount(CrfAuthoringWizard, {
    props: {
      open: true,
      crfOid: 'crf-1',
      crfName: 'Demographics',
    },
    global: { plugins: [i18n] },
    attachTo: document.body,
  })
}

describe('CrfAuthoringWizard — polish-ux', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  describe('locale strings', () => {
    it('renders the new subheading "Create CRF definition" (EN)', async () => {
      const wrapper = mountWizard()
      await flushPromises()
      const html = document.body.innerHTML
      expect(html).toContain('Create CRF definition')
      // The vestigial Milestone-A blurb is gone.
      expect(html).not.toContain('Phase E.6 Milestone A — vertical slice')
      wrapper.unmount()
    })

    it('renders the new German subheading "CRF-Definition erstellen"', () => {
      // We don't bother mounting in DE; the locale bundle assertion
      // is cheaper and equally load-bearing.
      const de = deMessages as unknown as {
        crfLibrary: { author: { subheading: string; scopeNote?: string } }
      }
      expect(de.crfLibrary.author.subheading).toBe('CRF-Definition erstellen')
    })

    it('drops the scopeNote key entirely from both locales', () => {
      const en = enMessages as unknown as {
        crfLibrary: { author: { scopeNote?: string } }
      }
      const de = deMessages as unknown as {
        crfLibrary: { author: { scopeNote?: string } }
      }
      expect(en.crfLibrary.author.scopeNote).toBeUndefined()
      expect(de.crfLibrary.author.scopeNote).toBeUndefined()
    })

    it('does not render the scopeNote element', async () => {
      const wrapper = mountWizard()
      await flushPromises()
      const html = document.body.innerHTML
      expect(html).not.toContain('Milestone A scope')
      expect(html).not.toContain('Milestone-A-Umfang')
      wrapper.unmount()
    })

    it('relabels leftItemText/rightItemText to the human-facing strings (EN)', () => {
      const en = enMessages as unknown as {
        crfAuthoring: { item: { leftItemText: string; rightItemText: string } }
      }
      expect(en.crfAuthoring.item.leftItemText).toBe('Label')
      expect(en.crfAuthoring.item.rightItemText).toBe('Helper text')
    })

    it('relabels leftItemText/rightItemText to the human-facing strings (DE)', () => {
      const de = deMessages as unknown as {
        crfAuthoring: { item: { leftItemText: string; rightItemText: string } }
      }
      expect(de.crfAuthoring.item.leftItemText).toBe('Beschriftung über Eingabe')
      expect(de.crfAuthoring.item.rightItemText).toBe('Erläuterung unter Eingabe')
    })
  })

  describe('section + item collapse', () => {
    it('starts the default section collapsed (body not visible)', async () => {
      const wrapper = mountWizard()
      await flushPromises()
      // Jump to the Sections step.
      const railSections = document.body.querySelectorAll('button')
      const sectionsRailBtn = Array.from(railSections).find(
        (b) => b.textContent?.includes('2 · Sections'),
      )
      sectionsRailBtn?.click()
      await flushPromises()

      // The chevron toggle is present.
      const toggle = document.body.querySelector('[data-testid="crf-author-section-toggle-0"]')
      expect(toggle).not.toBeNull()
      expect(toggle?.getAttribute('aria-expanded')).toBe('false')

      // The section body (label + title TextInputs) is v-show-hidden.
      const body = document.body.querySelector('#crf-author-section-body-0') as HTMLElement | null
      expect(body).not.toBeNull()
      // v-show toggles display:none — assert the inline style.
      expect(body?.style.display).toBe('none')

      wrapper.unmount()
    })

    it('expands the section body when the chevron is clicked', async () => {
      const wrapper = mountWizard()
      await flushPromises()
      const railSections = document.body.querySelectorAll('button')
      const sectionsRailBtn = Array.from(railSections).find(
        (b) => b.textContent?.includes('2 · Sections'),
      )
      sectionsRailBtn?.click()
      await flushPromises()

      const toggle = document.body.querySelector(
        '[data-testid="crf-author-section-toggle-0"]',
      ) as HTMLElement
      expect(toggle.getAttribute('aria-expanded')).toBe('false')

      toggle.click()
      await flushPromises()

      expect(toggle.getAttribute('aria-expanded')).toBe('true')
      const body = document.body.querySelector('#crf-author-section-body-0') as HTMLElement | null
      expect(body?.style.display).not.toBe('none')

      wrapper.unmount()
    })

    it('auto-expands a freshly added section + item', async () => {
      const wrapper = mountWizard()
      await flushPromises()
      const railSections = document.body.querySelectorAll('button')
      Array.from(railSections)
        .find((b) => b.textContent?.includes('2 · Sections'))
        ?.click()
      await flushPromises()

      // Add a new section — its uid lands at index 1.
      const addSection = document.body.querySelector(
        '[data-testid="crf-author-add-section"]',
      ) as HTMLElement
      addSection.click()
      await flushPromises()

      const newSectionToggle = document.body.querySelector(
        '[data-testid="crf-author-section-toggle-1"]',
      ) as HTMLElement
      expect(newSectionToggle).not.toBeNull()
      expect(newSectionToggle.getAttribute('aria-expanded')).toBe('true')

      // Now add an item into the new section — it should auto-expand
      // its parent (already expanded) and the item itself.
      const addItem = document.body.querySelector(
        '[data-testid="crf-author-add-item-1"]',
      ) as HTMLElement
      addItem.click()
      await flushPromises()

      const itemToggle = document.body.querySelector(
        '[data-testid="crf-author-item-toggle-1-0"]',
      ) as HTMLElement
      expect(itemToggle).not.toBeNull()
      expect(itemToggle.getAttribute('aria-expanded')).toBe('true')

      wrapper.unmount()
    })

    it('item bodies collapse + hide ItemEditor content; chevron-click reveals it', async () => {
      const wrapper = mountWizard()
      await flushPromises()
      const railSections = document.body.querySelectorAll('button')
      Array.from(railSections)
        .find((b) => b.textContent?.includes('2 · Sections'))
        ?.click()
      await flushPromises()

      // Expand the default section so its add-item button is visible.
      const sectionToggle = document.body.querySelector(
        '[data-testid="crf-author-section-toggle-0"]',
      ) as HTMLElement
      sectionToggle.click()
      await flushPromises()

      // Seed an item via the store so we don't rely on the add button's
      // auto-expand (we explicitly want a collapsed item to test).
      const store = useCrfAuthoringStore()
      store.addItem(0)
      await flushPromises()

      const itemToggle = document.body.querySelector(
        '[data-testid="crf-author-item-toggle-0-0"]',
      ) as HTMLElement
      expect(itemToggle).not.toBeNull()
      // Store-driven add bypasses the wrapper's auto-expand; the item
      // starts collapsed.
      expect(itemToggle.getAttribute('aria-expanded')).toBe('false')

      const body = document.body.querySelector('#crf-author-item-body-0-0') as HTMLElement | null
      expect(body).not.toBeNull()
      expect(body?.style.display).toBe('none')

      itemToggle.click()
      await flushPromises()

      expect(itemToggle.getAttribute('aria-expanded')).toBe('true')
      expect(body?.style.display).not.toBe('none')

      wrapper.unmount()
    })
  })
})
