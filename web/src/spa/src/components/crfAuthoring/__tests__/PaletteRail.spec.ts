/**
 * App-feedback Wave 2 (2026-06-19) — PaletteRail spec.
 *
 * Pins:
 *   - the rail renders the configured primitives + presets,
 *   - clicking a primitive emits {@code primitive-activated},
 *   - clicking a preset emits {@code preset-activated},
 *   - the IOP preset card is present (the user's explicit example).
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'

import PaletteRail from '@/components/crfAuthoring/PaletteRail.vue'
import {
  PALETTE_PRIMITIVES,
  PRESET_CATALOG,
} from '@/components/crfAuthoring/presetCatalog'
import deMessages from '@/locales/de.json'

const i18n = createI18n({
  legacy: false,
  locale: 'de-AT',
  fallbackLocale: 'de-AT',
  missingWarn: false,
  fallbackWarn: false,
  messages: { 'de-AT': deMessages },
})

function mountRail() {
  return mount(PaletteRail, {
    global: { plugins: [i18n] },
  })
}

describe('PaletteRail', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders the palette title + primitives + presets headers', () => {
    const w = mountRail()
    const html = w.html()
    expect(html).toContain('Bausteine') // crfAuthoring.canvas.palette.title
    expect(html).toContain('Primitive Items')
    expect(html).toContain('Presets')
  })

  it('renders one button per primitive', () => {
    const w = mountRail()
    for (const p of PALETTE_PRIMITIVES) {
      expect(w.find(`[data-testid="crf-canvas-palette-prim-${p.id}"]`).exists()).toBe(true)
    }
  })

  it('renders one button per preset (IOP + OPHTH_EXAM)', () => {
    const w = mountRail()
    for (const p of PRESET_CATALOG) {
      expect(w.find(`[data-testid="crf-canvas-palette-preset-${p.id}"]`).exists()).toBe(true)
    }
    // Sanity: IOP must be present.
    expect(w.find('[data-testid="crf-canvas-palette-preset-iop"]').exists()).toBe(true)
  })

  it('renders the D4 (2026-06-20) preset batch: BCVA, RNFL, thickness-map, slit-lamp', () => {
    const w = mountRail()
    expect(w.find('[data-testid="crf-canvas-palette-preset-bcva"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-palette-preset-rnfl"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-palette-preset-thicknessMap"]').exists()).toBe(true)
    expect(w.find('[data-testid="crf-canvas-palette-preset-slitLamp"]').exists()).toBe(true)
  })

  it('emits primitive-activated when a primitive is clicked', async () => {
    const w = mountRail()
    await w.find('[data-testid="crf-canvas-palette-prim-INT"]').trigger('click')
    const emitted = w.emitted('primitive-activated')
    expect(emitted).toBeTruthy()
    expect((emitted![0]![0] as { id: string }).id).toBe('INT')
  })

  it('emits preset-activated when a preset is clicked', async () => {
    const w = mountRail()
    await w.find('[data-testid="crf-canvas-palette-preset-iop"]').trigger('click')
    const emitted = w.emitted('preset-activated')
    expect(emitted).toBeTruthy()
    expect((emitted![0]![0] as { id: string }).id).toBe('iop')
  })

  it('sets the drag payload on dragstart of a primitive', async () => {
    const w = mountRail()
    const button = w.find('[data-testid="crf-canvas-palette-prim-REAL"]')
    const setData = (key: string, value: string): void => { recorded[key] = value }
    const recorded: Record<string, string> = {}
    const dataTransfer = {
      setData,
      effectAllowed: '',
    } as unknown as DataTransfer
    await button.element.dispatchEvent(
      Object.assign(new Event('dragstart', { bubbles: true }), { dataTransfer }),
    )
    expect(recorded['application/x-crf-palette']).toContain('"REAL"')
    expect(recorded['application/x-crf-palette']).toContain('"primitive"')
  })

  it('sets the drag payload on dragstart of a preset', async () => {
    const w = mountRail()
    const button = w.find('[data-testid="crf-canvas-palette-preset-iop"]')
    const recorded: Record<string, string> = {}
    const dataTransfer = {
      setData: (key: string, value: string): void => { recorded[key] = value },
      effectAllowed: '',
    } as unknown as DataTransfer
    await button.element.dispatchEvent(
      Object.assign(new Event('dragstart', { bubbles: true }), { dataTransfer }),
    )
    expect(recorded['application/x-crf-palette']).toContain('"iop"')
    expect(recorded['application/x-crf-palette']).toContain('"preset"')
  })
})
