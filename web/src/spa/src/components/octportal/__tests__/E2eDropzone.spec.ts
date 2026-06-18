/**
 * Phase E retinal-inference (Wave C) — E2eDropzone spec.
 *
 * Covers the two paths that produce a `files-added` emit:
 *  - drag-and-drop (DragEvent.dataTransfer.files)
 *  - browse-button → <input type="file"> change event
 *
 * Both pipe through the same internal `emitFiles` helper so we want
 * to see the emit fire for either entry point. The hero vs slim
 * modes share the wiring; we test the hero variant.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'

import E2eDropzone from '../E2eDropzone.vue'

function makeFile(name: string): File {
  return new File([new Uint8Array(1024)], name, { type: 'application/octet-stream' })
}

describe('E2eDropzone', () => {
  it('renders the hero variant when mode="hero"', () => {
    const w = mount(E2eDropzone, { props: { mode: 'hero' } })
    expect(w.find('[data-testid="e2e-dropzone-hero"]').exists()).toBe(true)
    expect(w.find('[data-testid="e2e-dropzone-slim"]').exists()).toBe(false)
    expect(w.text()).toContain('OCT-Scans hierher ziehen')
  })

  it('renders the slim variant when mode="slim"', () => {
    const w = mount(E2eDropzone, { props: { mode: 'slim' } })
    expect(w.find('[data-testid="e2e-dropzone-slim"]').exists()).toBe(true)
    expect(w.find('[data-testid="e2e-dropzone-hero"]').exists()).toBe(false)
    expect(w.text()).toContain('Weitere')
  })

  it('emits files-added when files are dropped onto the hero zone', async () => {
    const w = mount(E2eDropzone, { props: { mode: 'hero' } })
    const zone = w.find('[data-testid="e2e-dropzone-hero"]')
    const file = makeFile('TEST-001_OD.e2e')

    // jsdom's DataTransfer is minimal; build a DragEvent and patch
    // the dataTransfer.files getter manually so the handler can pick
    // the file up. Same trick FileUploadInput.test.ts uses for the
    // <input>-side path.
    const event = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent
    Object.defineProperty(event, 'dataTransfer', {
      value: { files: [file] },
      configurable: true,
    })
    zone.element.dispatchEvent(event)
    await w.vm.$nextTick()

    const emits = w.emitted('files-added')
    expect(emits).toBeTruthy()
    expect(emits![0][0]).toHaveLength(1)
    expect((emits![0][0] as File[])[0].name).toBe('TEST-001_OD.e2e')
  })

  it('emits files-added when files are picked via the browse input', async () => {
    const w = mount(E2eDropzone, { props: { mode: 'hero' } })
    const input = w.find('[data-testid="e2e-dropzone-input"]')
    const file = makeFile('TEST-002_OS.e2e')

    Object.defineProperty(input.element, 'files', {
      value: [file],
      configurable: true,
    })
    await input.trigger('change')

    const emits = w.emitted('files-added')
    expect(emits).toBeTruthy()
    expect(emits![0][0]).toHaveLength(1)
    expect((emits![0][0] as File[])[0].name).toBe('TEST-002_OS.e2e')
  })

  it('does not emit files-added when an empty file list is dropped', async () => {
    const w = mount(E2eDropzone, { props: { mode: 'slim' } })
    const zone = w.find('[data-testid="e2e-dropzone-slim"]')

    const event = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent
    Object.defineProperty(event, 'dataTransfer', {
      value: { files: [] },
      configurable: true,
    })
    zone.element.dispatchEvent(event)
    await w.vm.$nextTick()

    expect(w.emitted('files-added')).toBeFalsy()
  })
})
