import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import ItemNoteIndicator from '@/components/ItemNoteIndicator.vue'
import type { ItemNoteSummary } from '@/types/crf'

const messages = {
  en: {
    crfEntry: {
      itemNote: {
        openThread: 'Open discussion thread',
        statusNew: '{count} open',
        statusResolved: '{count} resolved',
        createNew: '+ Frage',
      },
    },
  },
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages,
})

function mountIndicator(props: { summary: ItemNoteSummary | null }) {
  return mount(ItemNoteIndicator, {
    props,
    global: { plugins: [i18n] },
  })
}

describe('ItemNoteIndicator', () => {
  it('renders the "+ Frage" ghost button when summary is null and emits create on click', async () => {
    const w = mountIndicator({ summary: null })

    const btn = w.get('button')
    expect(btn.text()).toContain('+ Frage')

    await btn.trigger('click')
    const events = w.emitted('create')
    expect(events).toBeTruthy()
    expect(events!.length).toBe(1)
    // No payload on create.
    expect(events![0]).toEqual([])
    // The "open" event must not fire from the empty-state branch.
    expect(w.emitted('open')).toBeFalsy()
  })

  it('renders the chip and emits open with noteIds when summary has open notes', async () => {
    const summary: ItemNoteSummary = {
      status: 'open',
      openCount: 2,
      totalCount: 2,
      lastActivityAt: null,
      noteIds: ['n1', 'n2'],
    }
    const w = mountIndicator({ summary })

    const btn = w.get('button')
    expect(btn.text()).toContain('2 open')

    await btn.trigger('click')
    const events = w.emitted('open')
    expect(events).toBeTruthy()
    expect(events!.length).toBe(1)
    expect(events![0]).toEqual([['n1', 'n2']])
    expect(w.emitted('create')).toBeFalsy()
  })
})
