import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'

import en from '@/locales/en.json'

/**
 * The sysadmin System Status page — specifically its retinal-cluster panel
 * (2026-09-22).
 *
 * What is worth pinning is the reason the panel exists: an operator must be
 * able to see, per node, that a server is up but missing tasks (the silent
 * failure the launcher bug produced), and must be told when the monitor log
 * cannot be read rather than shown a reassuring blank. And the cluster
 * request failing must not take the JVM/DB panels down with it — they are
 * separate requests for exactly that reason.
 */

vi.mock('@/api/client', async () => {
  const actual = await vi.importActual<typeof import('@/api/client')>('@/api/client')
  return { ...actual, apiGet: vi.fn() }
})

import { apiGet } from '@/api/client'
import AdminSystemStatusView from '../AdminSystemStatusView.vue'

const apiGetMock = vi.mocked(apiGet)

const SYSTEM = {
  jvm: { javaVersion: '25', vmName: 'OpenJDK', heapMaxMb: 1024, heapUsedMb: 300, heapFreeMb: 724, threadCount: 40, availableProcessors: 2 },
  database: { liquibaseChangelogCount: 42, reachable: true, databaseProductName: 'PostgreSQL', databaseProductVersion: '14' },
  application: { status: 'OK', upSinceMillis: 90_000 },
}

const CLUSTER = {
  configured: true,
  remotePushUrl: 'http://nginx:8088',
  expectedTasks: ['bm', 'fluid', 'ga', 'layers', 'onl', 'pr'],
  nodes: [
    { name: 'on3', url: 'http://149.148.108.144:8000', state: 'healthy', supportedTasks: ['bm', 'fluid', 'ga', 'layers', 'onl', 'pr'], missingTasks: [], latencyMs: 14, node: 'on3', gpuDevice: '2', gpuName: 'NVIDIA GeForce RTX 2080 Ti', error: null },
    { name: 'cn6', url: 'http://149.148.108.170:8000', state: 'degraded', supportedTasks: ['fluid', 'ga', 'onl', 'pr'], missingTasks: ['bm', 'layers'], latencyMs: 9, node: 'cn6', gpuDevice: '3', gpuName: 'NVIDIA GeForce RTX 2080 Ti', error: null },
    { name: 'cn5', url: 'http://149.148.108.173:8000', state: 'unreachable', supportedTasks: [], missingTasks: ['bm', 'fluid', 'ga', 'layers', 'onl', 'pr'], latencyMs: 3001, node: null, gpuDevice: null, gpuName: null, error: 'HttpTimeoutException: request timed out' },
  ],
  monitor: { path: '/var/lib/libreclinica/retinal-cluster-monitor.log', readable: true, lines: ['RETINAL CLUSTER PROBLEM (http://149.148.108.173:8000/health), failed check #1'] },
}

function routeBy(cluster: unknown, opts: { clusterFails?: boolean } = {}) {
  apiGetMock.mockImplementation((path: string) => {
    if (path.endsWith('/retinal-cluster')) {
      return opts.clusterFails ? Promise.reject(new Error('boom')) : Promise.resolve(cluster)
    }
    return Promise.resolve(SYSTEM)
  })
}

function mountView() {
  const i18n = createI18n({ legacy: false, locale: 'en', messages: { en } })
  // The view carries the System section rail, which reads the route. This
  // test has no router and is not about navigation, so the rail is stubbed —
  // as the build-page tests stub BuildStudyRail. SystemRail has its own test.
  return mount(AdminSystemStatusView, { global: { plugins: [i18n], stubs: { SystemRail: true } } })
}

describe('AdminSystemStatusView — retinal cluster panel', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
  })

  it('renders one row per node, naming the missing tasks on a degraded node and the error on an unreachable one', async () => {
    routeBy(CLUSTER)
    const w = mountView()
    await flushPromises()

    const rows = w.findAll('tbody tr')
    expect(rows).toHaveLength(3)

    const on3 = w.find('tr[data-node="on3"]')
    expect(on3.text()).toContain('Healthy')
    expect(on3.text()).toContain('6 / 6')
    expect(on3.text()).toContain('NVIDIA GeForce RTX 2080 Ti #2')
    expect(on3.text()).toContain('14 ms')

    const cn6 = w.find('tr[data-node="cn6"]')
    expect(cn6.text()).toContain('Degraded')
    expect(cn6.text()).toContain('4 / 6')
    expect(cn6.text()).toContain('missing bm, layers')

    const cn5 = w.find('tr[data-node="cn5"]')
    expect(cn5.text()).toContain('Unreachable')
    expect(cn5.text()).toContain('HttpTimeoutException')
    expect(cn5.text()).toContain('—') // no GPU to show

    expect(w.text()).toContain('Jobs are sent to http://nginx:8088')
    expect(w.find('[data-testid="monitor-log"]').text()).toContain('failed check #1')
  })

  it('says so when no cluster is configured, and still shows the local panels', async () => {
    routeBy({ configured: false, remotePushUrl: '', expectedTasks: [], nodes: [] })
    const w = mountView()
    await flushPromises()

    expect(w.text()).toContain('No remote cluster configured')
    expect(w.find('tbody').exists()).toBe(false)
    expect(w.text()).toContain('PostgreSQL') // the DB panel rendered
  })

  it('reports an unreadable monitor log instead of a reassuring blank', async () => {
    routeBy({ ...CLUSTER, monitor: { path: '/var/lib/libreclinica/retinal-cluster-monitor.log', readable: false, lines: [] } })
    const w = mountView()
    await flushPromises()

    expect(w.text()).toContain('Monitor log not readable: /var/lib/libreclinica/retinal-cluster-monitor.log')
    expect(w.find('[data-testid="monitor-log"]').exists()).toBe(false)
  })

  it('shows "no alerts" when the log is readable but empty', async () => {
    routeBy({ ...CLUSTER, monitor: { path: '/x.log', readable: true, lines: [] } })
    const w = mountView()
    await flushPromises()

    expect(w.text()).toContain('No alerts recorded.')
  })

  it('keeps the JVM/DB/application panels when only the cluster request fails', async () => {
    routeBy(CLUSTER, { clusterFails: true })
    const w = mountView()
    await flushPromises()

    expect(w.text()).toContain('Failed to load cluster status.')
    expect(w.text()).toContain('PostgreSQL')
    expect(w.find('tbody').exists()).toBe(false)
  })
})
