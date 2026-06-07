/**
 * Phase E.6 per-eye cohort transition workflow — SubjectDetailView spec.
 *
 * Pins three behaviours the worktree owns:
 *  1. Per-eye Transition button gating — role permitted AND eye in scope.
 *  2. Source banner renders when subject.eyeTransitions has a
 *     side='source' row, with the partner label/study correctly rendered.
 *  3. Target banner renders symmetrically for side='target'.
 *
 * The i18n bundle is enriched with the new transition keys so the test
 * is self-contained without coupling to the i18n worktree's locale
 * edits (which land separately during harmonization).
 */
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory, type Router } from 'vue-router'
import { createI18n } from 'vue-i18n'

vi.mock('@/api/client', () => {
  class ApiError extends Error {
    isUnauthorized = false
    isForbidden = false
    isNotFound = false
    constructor(public status = 0, msg = '', public body: unknown = null) {
      super(msg)
      if (status === 401) this.isUnauthorized = true
      if (status === 403) this.isForbidden = true
      if (status === 404) this.isNotFound = true
    }
  }
  class ApiNetworkError extends Error {
    constructor(message: string, public cause: unknown = null) {
      super(message)
    }
  }
  return {
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiDelete: vi.fn(),
    ApiError,
    ApiNetworkError,
  }
})

// eslint-disable-next-line import/first
import { apiGet } from '@/api/client'
// eslint-disable-next-line import/first
import SubjectDetailView from '@/views/SubjectDetailView.vue'
// eslint-disable-next-line import/first
import { useAuthStore } from '@/stores/auth'
// eslint-disable-next-line import/first
import type { SubjectDetail, EyeTransitionDto, StudyEye } from '@/types/subject'
// eslint-disable-next-line import/first
import type { UserRole } from '@/types/auth'
// eslint-disable-next-line import/first
import enMessages from '@/locales/en.json'

const apiGetMock = apiGet as unknown as ReturnType<typeof vi.fn>

// Pre-populate the new transition i18n keys so the test renders the
// real copy paths. The i18n worktree owns the production strings;
// these are placeholders that exercise the same key path.
const messages = {
  en: {
    ...enMessages,
    subjectDetail: {
      ...(enMessages as { subjectDetail: Record<string, unknown> }).subjectDetail,
      eyeTransition: {
        success: 'Transition saved.',
        action: {
          openPartner: 'Open partner record',
          transition: 'Transition',
        },
        banner: {
          source:
            '{eye} on {transitionedAt} transitioned to {partnerStudy} ({partnerLabel}). Reason: {reason}',
          target:
            '{eye} previously recorded in {partnerStudy} as {partnerLabel}.',
        },
        error: {
          network: 'Backend unreachable — please retry.',
        },
      },
    },
  },
}

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  fallbackLocale: 'en',
  messages,
  missingWarn: false,
  fallbackWarn: false,
})

function makeRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/subjects', name: 'subject-matrix', component: { template: '<div />' } },
      { path: '/subjects/new', name: 'subject-new', component: { template: '<div />' } },
      {
        path: '/subjects/:subjectId',
        name: 'subject-detail',
        component: { template: '<div />' },
      },
      {
        path: '/subjects/:subjectId/sign',
        name: 'sign-subject',
        component: { template: '<div />' },
      },
      { path: '/events/:eventId', name: 'event-detail', component: { template: '<div />' } },
    ],
  })
}

function makeDetail(overrides: Partial<SubjectDetail> = {}): SubjectDetail {
  return {
    id: 'M-001',
    secondaryId: null,
    siteOid: 'S_DEFAULTS1',
    siteLabel: 'Vienna',
    studyOid: 'S_DEFAULTS1',
    studyName: 'iAMD',
    gender: 'F',
    yearOfBirth: 1962,
    groupLabel: null,
    enrolledOn: '2026-05-01',
    signed: false,
    locked: false,
    openQueries: 0,
    events: [],
    studyEye: 'OU',
    screeningDate: null,
    status: 'available',
    groupAssignments: [],
    eyeTransitions: [],
    ...overrides,
  } as SubjectDetail
}

interface MountOptions {
  role?: UserRole | null
  detail?: SubjectDetail
  activeStudyOid?: string
  activeStudyName?: string
}

async function mountAt(options: MountOptions = {}) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  if (options.role !== null) {
    auth.user = {
      username: 'demo',
      displayName: 'Demo',
      email: null,
      role: (options.role ?? 'Investigator') as UserRole,
      siteLabel: null,
      source: 'local',
      mfaSatisfied: true,
      profileComplete: true,
      mustChangePassword: false,
      passwordChangeReason: null,
      locale: null,
      timezone: null,
      activeStudy: {
        id: 1,
        oid: options.activeStudyOid ?? 'S_DEFAULTS1',
        name: options.activeStudyName ?? 'iAMD',
        isSite: false,
      },
    } as unknown as ReturnType<typeof useAuthStore>['user']['value']
  }
  // availableStudies is consumed by the otherStudies computed.
  auth.availableStudies = [
    { oid: 'S_DEFAULTS1', name: 'iAMD' },
    { oid: 'S_GA001', name: 'GA' },
  ] as unknown as typeof auth.availableStudies

  const router = makeRouter()
  router.push('/subjects/M-001')
  await router.isReady()

  const detail = options.detail ?? makeDetail()
  apiGetMock.mockReset()
  apiGetMock.mockResolvedValueOnce(detail)

  const wrapper = mount(SubjectDetailView, {
    global: { plugins: [router, i18n] },
  })
  await flushPromises()
  return wrapper
}

describe('SubjectDetailView — per-eye Transition button gating', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
  })

  it('shows the Transition button for both eyes when role is Investigator and studyEye is OU', async () => {
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({ studyEye: 'OU' }),
    })
    expect(w.find('[data-testid="transition-OD"]').exists()).toBe(true)
    expect(w.find('[data-testid="transition-OS"]').exists()).toBe(true)
  })

  it('hides the Transition button on the eye that is not in scope', async () => {
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({ studyEye: 'OS' as StudyEye }),
    })
    expect(w.find('[data-testid="transition-OD"]').exists()).toBe(false)
    expect(w.find('[data-testid="transition-OS"]').exists()).toBe(true)
  })

  it('hides the Transition button entirely for a Monitor (role not permitted)', async () => {
    const w = await mountAt({
      role: 'Monitor',
      detail: makeDetail({ studyEye: 'OU' }),
    })
    expect(w.find('[data-testid="transition-OD"]').exists()).toBe(false)
    expect(w.find('[data-testid="transition-OS"]').exists()).toBe(false)
  })

  it('hides both Transition buttons when studyEye is null (no enrolled eye)', async () => {
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({ studyEye: null }),
    })
    expect(w.find('[data-testid="transition-OD"]').exists()).toBe(false)
    expect(w.find('[data-testid="transition-OS"]').exists()).toBe(false)
  })
})

describe('SubjectDetailView — cross-reference banners', () => {
  beforeEach(() => {
    apiGetMock.mockReset()
  })

  it('renders the source banner when an eyeTransitions row has side="source"', async () => {
    const sourceRow: EyeTransitionDto = {
      transitionId: 11,
      eye: 'OD',
      side: 'source',
      partnerStudyOid: 'S_GA001',
      partnerStudyName: 'GA',
      partnerLabel: 'M-101',
      transitionedAt: '2026-06-07T09:00:00Z',
      reason: 'Progression to GA confirmed on OCT',
    }
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({ eyeTransitions: [sourceRow] }),
    })
    const banner = w.find('[data-testid="eye-transition-banner-source"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('M-101')
    expect(banner.text()).toContain('GA')
    expect(banner.text()).toContain('Progression to GA')
    // Target banner stays hidden when no target rows are present.
    expect(w.find('[data-testid="eye-transition-banner-target"]').exists()).toBe(false)
  })

  it('renders the target banner symmetrically when side="target"', async () => {
    const targetRow: EyeTransitionDto = {
      transitionId: 22,
      eye: 'OD',
      side: 'target',
      partnerStudyOid: 'S_DEFAULTS1',
      partnerStudyName: 'iAMD',
      partnerLabel: 'M-001',
      transitionedAt: '2026-06-07T09:00:00Z',
      reason: 'Progression to GA confirmed on OCT',
    }
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({
        id: 'M-101',
        studyEye: 'OD' as StudyEye,
        eyeTransitions: [targetRow],
      }),
      activeStudyOid: 'S_GA001',
      activeStudyName: 'GA',
    })
    const banner = w.find('[data-testid="eye-transition-banner-target"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('M-001')
    expect(banner.text()).toContain('iAMD')
    expect(w.find('[data-testid="eye-transition-banner-source"]').exists()).toBe(false)
  })

  it('renders no banners when eyeTransitions is empty', async () => {
    const w = await mountAt({
      role: 'Investigator',
      detail: makeDetail({ eyeTransitions: [] }),
    })
    expect(w.find('[data-testid="eye-transition-banner-source"]').exists()).toBe(false)
    expect(w.find('[data-testid="eye-transition-banner-target"]').exists()).toBe(false)
  })
})
