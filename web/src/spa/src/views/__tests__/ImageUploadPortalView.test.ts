import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import ImageUploadPortalView from '../ImageUploadPortalView.vue'
import ImageDropzone from '@/components/imageportal/ImageDropzone.vue'
import enMessages from '@/locales/en.json'

// DR-025 — the public upload view talks to the portal client; stub it so the
// test doesn't hit the network.
const { commitMock, resolveMock } = vi.hoisted(() => ({
  commitMock: vi.fn(),
  resolveMock: vi.fn(),
}))
vi.mock('@/api/imagePortal', () => ({
  commitImage: (...args: unknown[]) => commitMock(...args),
  resolvePatient: (...args: unknown[]) => resolveMock(...args),
}))

const i18n = createI18n({ legacy: false, locale: 'en', fallbackLocale: 'en', messages: { en: enMessages } })

function mountView() {
  return mount(ImageUploadPortalView, { global: { plugins: [i18n] } })
}

beforeEach(() => {
  commitMock.mockReset()
  resolveMock.mockReset()
  // jsdom has no object-URL support.
  ;(URL as unknown as { createObjectURL: () => string }).createObjectURL = vi.fn(() => 'blob:mock')
  ;(URL as unknown as { revokeObjectURL: () => void }).revokeObjectURL = vi.fn()
})

describe('ImageUploadPortalView', () => {
  it('shows the dropzone first, with the submit button disabled until a file is picked', () => {
    const w = mountView()
    expect(w.find('[data-testid="image-dropzone"]').exists()).toBe(true)
    const btn = w.find('[data-testid="upload-submit"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('picking a file shows a preview + submit; submitting calls commitImage and shows success', async () => {
    commitMock.mockResolvedValue({ imageIngestId: 1, status: 'UNBOUND' })
    const w = mountView()
    const file = new File([new Uint8Array([1, 2, 3])], 'fundus.jpg', { type: 'image/jpeg' })

    w.findComponent(ImageDropzone).vm.$emit('file-selected', file)
    await flushPromises()

    expect(w.find('[data-testid="image-preview"]').exists()).toBe(true)
    expect(w.find('[data-testid="upload-submit"]').exists()).toBe(true)

    await w.find('form').trigger('submit')
    await flushPromises()

    expect(commitMock).toHaveBeenCalledTimes(1)
    expect(commitMock.mock.calls[0][0]).toBe(file)
    expect(w.find('[data-testid="upload-success"]').exists()).toBe(true)
  })
})
