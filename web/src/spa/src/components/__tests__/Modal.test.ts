import { describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import Modal from '../Modal.vue'

const baseProps = {
  open: true,
  labelledBy: 'modal-heading',
}

describe('Modal', () => {
  it('renders the default slot inside the dialog when open', async () => {
    const wrapper = mount(Modal, {
      props: baseProps,
      slots: { default: '<p data-testid="body">Hello</p>' },
      attachTo: document.body,
    })
    await flushPromises()
    expect(document.body.querySelector('[data-testid="body"]')?.textContent).toBe('Hello')
    wrapper.unmount()
  })

  it('exposes role="dialog" + aria-modal + aria-labelledby for accessibility', async () => {
    const wrapper = mount(Modal, {
      props: baseProps,
      slots: { default: '<p>body</p>' },
      attachTo: document.body,
    })
    await flushPromises()
    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).not.toBeNull()
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    expect(dialog?.getAttribute('aria-labelledby')).toBe('modal-heading')
    wrapper.unmount()
  })

  it('emits close + update:open(false) when the Escape key fires', async () => {
    const wrapper = mount(Modal, {
      props: baseProps,
      slots: { default: '<p>body</p>' },
      attachTo: document.body,
    })
    await nextTick()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()

    expect(wrapper.emitted('close')).toBeTruthy()
    expect(wrapper.emitted('update:open')?.[0]).toEqual([false])
    wrapper.unmount()
  })

  it('locks body scroll while open and restores on unmount', async () => {
    const wrapper = mount(Modal, {
      props: baseProps,
      slots: { default: '<p>body</p>' },
      attachTo: document.body,
    })
    await nextTick()
    expect(document.body.style.overflow).toBe('hidden')
    wrapper.unmount()
    expect(document.body.style.overflow).toBe('')
  })

  it('honors closeOnEscape=false', async () => {
    const wrapper = mount(Modal, {
      props: { ...baseProps, closeOnEscape: false },
      slots: { default: '<p>body</p>' },
      attachTo: document.body,
    })
    await nextTick()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await flushPromises()

    expect(wrapper.emitted('close')).toBeUndefined()
    wrapper.unmount()
  })

  it('renders the header slot + close button', async () => {
    const wrapper = mount(Modal, {
      props: baseProps,
      slots: {
        default: '<p>body</p>',
        header: '<h3 id="modal-heading">Add Note</h3>',
      },
      attachTo: document.body,
    })
    await flushPromises()
    expect(document.body.querySelector('#modal-heading')?.textContent).toBe('Add Note')
    expect(document.body.querySelector('[aria-label="Close"]')).not.toBeNull()
    wrapper.unmount()
  })

  it('renders nothing when open=false', () => {
    const wrapper = mount(Modal, {
      props: { ...baseProps, open: false },
      slots: { default: '<p data-testid="body">Hello</p>' },
      attachTo: document.body,
    })
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    wrapper.unmount()
  })
})

describe('Modal — defensive cleanup', () => {
  it('removes the keydown listener on close', async () => {
    const removeSpy = vi.spyOn(document, 'removeEventListener')

    const wrapper = mount(Modal, {
      props: baseProps,
      slots: { default: '<p>body</p>' },
      attachTo: document.body,
    })
    await nextTick()

    await wrapper.setProps({ open: false })
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('keydown', expect.any(Function))
    wrapper.unmount()
    removeSpy.mockRestore()
  })
})
