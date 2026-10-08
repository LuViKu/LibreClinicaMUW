import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
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
        createNew: 'Frage',
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

    const btn = w.get('[role="button"]')
    // The "+" comes from the SVG icon inside the button skin; the i18n
    // value carries the noun only. Guard against the historical double-plus
    // ("+ + Frage") by ensuring the rendered text has no "+ +" sequence
    // and contains the noun exactly once.
    expect(btn.text()).toContain('Frage')
    expect(btn.text()).not.toContain('+ +')
    expect((btn.text().match(/\+/g) ?? []).length).toBe(0)

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

    const btn = w.get('[role="button"]')
    expect(btn.text()).toContain('2 open')

    await btn.trigger('click')
    const events = w.emitted('open')
    expect(events).toBeTruthy()
    expect(events!.length).toBe(1)
    expect(events![0]).toEqual([['n1', 'n2']])
    expect(w.emitted('create')).toBeFalsy()
  })

  /*
   * CrfEntryView locks a read-only or completed CRF with a disabled
   * fieldset. A <button> inside it is disabled and takes no click, so on
   * the read-only CRF, the Monitor's view of the data, no query could be
   * raised or opened. The control must stay usable there.
   */
  it('stays usable inside a disabled fieldset, as on a read-only CRF', async () => {
    const summary: ItemNoteSummary = {
      status: 'open', openCount: 1, totalCount: 1, lastActivityAt: null, noteIds: ['n7'],
    }
    const Host = defineComponent({
      components: { ItemNoteIndicator },
      props: { summary: { type: Object, default: null } },
      emits: ['open', 'create'],
      template: `<fieldset disabled>
        <ItemNoteIndicator :summary="summary" @open="(ids) => $emit('open', ids)" @create="$emit('create')" />
      </fieldset>`,
    })

    const withNote = mount(Host, { props: { summary }, global: { plugins: [i18n] } })
    const chip = withNote.get('fieldset > *')
    expect(chip.element.matches(':disabled')).toBe(false)
    await chip.trigger('keydown', { key: 'Enter' })
    expect(withNote.emitted('open')).toEqual([[['n7']]])

    const empty = mount(Host, { props: { summary: null }, global: { plugins: [i18n] } })
    const create = empty.get('fieldset > *')
    expect(create.element.matches(':disabled')).toBe(false)
    await create.trigger('keydown', { key: ' ' })
    expect(empty.emitted('create')).toEqual([[]])
  })
})
