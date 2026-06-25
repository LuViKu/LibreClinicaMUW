import { describe, expect, it, beforeEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createMemoryHistory } from 'vue-router'

import TopBar from '../TopBar.vue'

/**
 * Pluggable study-module SPI — TopBar nav.modules slot wiring.
 *
 * <p>The framework exposes a {@code nav.modules} injection slot; this
 * spec asserts TopBar consumes it correctly. The rest of TopBar's
 * surface (breadcrumb, role gates, profile popover) is covered by
 * higher-level integration specs — keeping this focused on the slot
 * contract.
 */

// Mock the studyModules store so the spec can drive injectionsFor()
// without booting a real auth+study chain.
const injectionsForMock = vi.fn()
vi.mock('@/stores/studyModules', () => ({
  useStudyModuleStore: () => ({ injectionsFor: injectionsForMock }),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', name: 'home', component: { template: '<div />' } }],
  })
}

function makeI18n() {
  return createI18n({
    legacy: false,
    locale: 'de-AT',
    fallbackLocale: 'en',
    missingWarn: false,
    fallbackWarn: false,
    messages: {
      'de-AT': { topBar: { systemAuditLog: 'Audit', retinalParked: 'Geparkt' } },
      en: { topBar: { systemAuditLog: 'Audit', retinalParked: 'Parked' } },
    },
  })
}

const NavStub = defineComponent({
  name: 'NavStub',
  setup: () => () => h('a', { 'data-testid': 'nav-stub-link' }, 'Open'),
})

describe('TopBar — nav.modules slot', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    injectionsForMock.mockReset()
  })

  // 2026-06-21 user-feedback batch — the nav.modules consumer was
  // removed from TopBar (see TopBar.vue:50). Module entry points are
  // now driven by the StudyModuleConsumer wrapper inside the workspace
  // route, not by the top-bar. These specs were leftover assertions
  // against the old direct-consumer wiring; skip them so the contract
  // change is documented rather than re-enabled by accident.
  it.skip('mounts one entry per nav.modules injection returned by the store', async () => {
    injectionsForMock.mockReturnValue([
      { key: 'topbar-workspace', labelKey: 'studyModules.namd.label', component: NavStub },
    ])
    const wrapper = mount(TopBar, {
      props: { userName: 'Root', userRoles: ['Investigator'] as const },
      global: { plugins: [makeRouter(), makeI18n()] },
    })
    await wrapper.vm.$nextTick()
    expect(injectionsForMock).toHaveBeenCalledWith('nav.modules')
  })

  it.skip('renders no slot chrome when injectionsFor returns an empty array', async () => {
    injectionsForMock.mockReturnValue([])
    const wrapper = mount(TopBar, {
      props: { userName: 'Root', userRoles: ['Investigator'] as const },
      global: { plugins: [makeRouter(), makeI18n()] },
    })
    await wrapper.vm.$nextTick()
    expect(injectionsForMock).toHaveBeenCalledWith('nav.modules')
  })
})
