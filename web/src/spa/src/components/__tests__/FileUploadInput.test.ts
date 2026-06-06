import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import FileUploadInput from '../FileUploadInput.vue'

const I18N_PROPS = {
  dropPromptLabel: 'Drop a file',
  browseLabel: 'Browse',
  uploadingLabel: 'Uploading…',
  removeLabel: 'Remove',
  replaceLabel: 'Replace',
  tooBigMessage: 'File too big',
  badExtensionMessage: 'Bad extension',
}

function makeFile(name: string, sizeBytes: number, type = 'application/octet-stream'): File {
  // jsdom doesn't expose a real `size` setter via the File ctor with a
  // tiny stub blob, so we craft a Blob whose .size matches what we need.
  const buf = new Uint8Array(sizeBytes)
  return new File([buf], name, { type })
}

describe('FileUploadInput', () => {
  it('renders the dropzone when modelValue is null', () => {
    const wrapper = mount(FileUploadInput, {
      props: { modelValue: null, idPrefix: 'i', maxBytes: 1024, allowedExtensions: 'pdf,jpg', ...I18N_PROPS },
    })
    expect(wrapper.text()).toContain('Drop a file')
    expect(wrapper.text()).toContain('Browse')
    expect(wrapper.find('input[type="file"]').exists()).toBe(true)
  })

  it('renders the persisted state when modelValue is a stored ref', () => {
    const wrapper = mount(FileUploadInput, {
      props: {
        modelValue: { filename: 'retina.jpg', bytes: 2_048_000 },
        idPrefix: 'i', maxBytes: 10_000_000, allowedExtensions: 'jpg,png',
        ...I18N_PROPS,
      },
    })
    expect(wrapper.text()).toContain('retina.jpg')
    expect(wrapper.text()).toContain('Replace')
    expect(wrapper.text()).toContain('Remove')
  })

  it('emits clear when Remove is clicked', async () => {
    const wrapper = mount(FileUploadInput, {
      props: {
        modelValue: { filename: 'x.pdf', bytes: 1024 },
        idPrefix: 'i', maxBytes: 0, allowedExtensions: '',
        ...I18N_PROPS,
      },
    })
    const remove = wrapper.findAll('button').find((b) => b.text() === 'Remove')!
    await remove.trigger('click')
    expect(wrapper.emitted('clear')).toBeTruthy()
  })

  it('rejects files larger than maxBytes without emitting upload', async () => {
    const wrapper = mount(FileUploadInput, {
      props: { modelValue: null, idPrefix: 'i', maxBytes: 100, allowedExtensions: '', ...I18N_PROPS },
    })
    const big = makeFile('huge.bin', 500)
    const input = wrapper.find('input[type="file"]')
    // jsdom doesn't support FileList constructors directly, so set files
    // via Object.defineProperty on the input element.
    Object.defineProperty(input.element, 'files', { value: [big], configurable: true })
    await input.trigger('change')
    expect(wrapper.emitted('upload')).toBeFalsy()
    expect(wrapper.text()).toContain('File too big')
  })

  it('rejects files whose extension is not in the allowlist', async () => {
    const wrapper = mount(FileUploadInput, {
      props: { modelValue: null, idPrefix: 'i', maxBytes: 0, allowedExtensions: 'pdf,jpg', ...I18N_PROPS },
    })
    const exe = makeFile('virus.exe', 100, 'application/x-msdownload')
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [exe], configurable: true })
    await input.trigger('change')
    expect(wrapper.emitted('upload')).toBeFalsy()
    expect(wrapper.text()).toContain('Bad extension')
  })

  it('emits upload when the file passes size + extension checks', async () => {
    const wrapper = mount(FileUploadInput, {
      props: { modelValue: null, idPrefix: 'i', maxBytes: 10_000_000, allowedExtensions: 'pdf,jpg', ...I18N_PROPS },
    })
    const ok = makeFile('scan.jpg', 1024, 'image/jpeg')
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [ok], configurable: true })
    await input.trigger('change')
    const emits = wrapper.emitted('upload')
    expect(emits).toBeTruthy()
    expect(emits![0][0]).toBeInstanceOf(File)
    expect((emits![0][0] as File).name).toBe('scan.jpg')
  })
})
