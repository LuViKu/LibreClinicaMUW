import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import { apiDownload } from '@/api/download'
import type { StudyOption } from '@/types/auth'
import type { AdminStudy, StudyRemovalPreview } from '@/types/study'

/**
 * Every study on the platform, for a system administrator.
 *
 * Hydrates from `GET /pages/api/v1/admin/studies`: top-level studies with
 * their sites nested, removed ones included. It is the one source the
 * SPA has for "what studies exist", so three screens read it for a system
 * administrator: the study list under System, the study picker, and the
 * study choice in the user-roles dialog. `GET /pages/api/v1/studies`
 * answers only "where am I bound", which is empty for an administrator
 * without study roles.
 */
export const useAdminStudiesStore = defineStore('adminStudies', () => {
  const studies = ref<AdminStudy[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const loaded = ref(false)

  /**
   * The studies and sites that can be opened or granted a role on, in the
   * picker's shape: parents in name order, each followed by its sites.
   * Removed and auto-removed ones are left out, as the legacy study
   * switch refuses them.
   */
  const openable = computed<StudyOption[]>(() => {
    const out: StudyOption[] = []
    for (const s of studies.value) {
      if (isRemoved(s)) continue
      out.push(toOption(s, null))
      for (const site of s.sites) {
        if (!isRemoved(site)) out.push(toOption(site, s))
      }
    }
    return out
  })

  async function load(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      studies.value = await apiGet<AdminStudy[]>('/pages/api/v1/admin/studies')
      loaded.value = true
    } catch (e) {
      studies.value = []
      error.value = messageOf(e, 'Failed to load the studies.')
    } finally {
      isLoading.value = false
    }
  }

  /** Loads once; later calls reuse the list until {@link load} is called again. */
  async function ensureLoaded(): Promise<void> {
    if (!loaded.value && !isLoading.value) await load()
  }

  /** What removing the study would take with it, for the confirmation. */
  async function previewRemoval(oid: string): Promise<StudyRemovalPreview> {
    return apiGet<StudyRemovalPreview>(
      `/pages/api/v1/studies/${encodeURIComponent(oid)}/removal-preview`,
    )
  }

  /** Removes the study and everything under it. The reason is audited. */
  async function remove(oid: string, reason: string) {
    return lifecycle(oid, 'disable', reason)
  }

  /** Restores a removed study and what its removal took. The reason is audited. */
  async function restore(oid: string, reason: string) {
    return lifecycle(oid, 'restore', reason)
  }

  async function lifecycle(
    oid: string,
    op: 'disable' | 'restore',
    reason: string,
  ): Promise<{ ok: true } | { ok: false; message: string }> {
    try {
      await apiPost(`/pages/api/v1/studies/${encodeURIComponent(oid)}/${op}`, { reason })
      await load()
      return { ok: true }
    } catch (e) {
      return { ok: false, message: messageOf(e, 'The study could not be changed.') }
    }
  }

  /** Downloads the study's ODM metadata document. */
  async function downloadMetadata(oid: string): Promise<void> {
    await apiDownload(
      `/pages/api/v1/studies/${encodeURIComponent(oid)}/metadata`,
      `${oid}_metadata.xml`,
    )
  }

  function reset() {
    studies.value = []
    isLoading.value = false
    error.value = null
    loaded.value = false
  }

  return {
    studies,
    isLoading,
    error,
    openable,
    load,
    ensureLoaded,
    previewRemoval,
    remove,
    restore,
    downloadMetadata,
    reset,
  }
})

export function isRemoved(s: AdminStudy): boolean {
  return s.status === 'REMOVED' || s.status === 'AUTO_REMOVED'
}

function toOption(s: AdminStudy, parent: AdminStudy | null): StudyOption {
  return {
    oid: s.oid,
    name: s.name,
    uniqueIdentifier: s.uniqueIdentifier ?? '',
    parentOid: parent?.oid ?? null,
    parentName: parent?.name ?? null,
    // A system administrator is Administrator wherever they are.
    role: 'Administrator',
    isSite: parent !== null,
    isActive: false,
  }
}

function messageOf(e: unknown, fallback: string): string {
  if (e instanceof ApiError) {
    const body = e.body as { message?: string; errors?: Array<{ message: string }> } | null
    return body?.errors?.[0]?.message ?? body?.message ?? `${fallback} (HTTP ${e.status})`
  }
  if (e instanceof ApiNetworkError) return 'Backend unreachable.'
  return e instanceof Error ? e.message : fallback
}
