import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiDelete, apiGet, apiPost, apiPut } from '@/api/client'
import type {
  BindingWriteRequest,
  ImagingModality,
  ImagingModalityWriteRequest,
  ItemAnswers,
} from '@/types/imagingModality'

/**
 * P3.4 — per-study imaging catalogue admin.
 *
 * Every write re-loads: a binding change alters what the platform will write
 * into a CRF from that moment on, so the list an administrator is looking at
 * must be what the server actually holds, not an optimistic guess.
 */
export const useImagingModalitiesStore = defineStore('imagingModalities', () => {
  const list = ref<ImagingModality[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const studyOid = ref<string | null>(null)

  function base(oid: string): string {
    return `/pages/api/v1/studies/${encodeURIComponent(oid)}/imaging-modalities`
  }

  async function load(oid: string): Promise<void> {
    studyOid.value = oid
    isLoading.value = true
    error.value = null
    try {
      const res = await apiGet<{ modalities: ImagingModality[] }>(base(oid))
      list.value = res.modalities
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      list.value = []
    } finally {
      isLoading.value = false
    }
  }

  async function reload(): Promise<void> {
    if (studyOid.value) await load(studyOid.value)
  }

  async function create(oid: string, body: ImagingModalityWriteRequest): Promise<void> {
    await apiPost(base(oid), body)
    await load(oid)
  }

  async function update(oid: string, id: number, body: ImagingModalityWriteRequest): Promise<void> {
    await apiPut(`${base(oid)}/${id}`, body)
    await load(oid)
  }

  /** Retires rather than deletes — files filed under it keep naming it. */
  async function retire(oid: string, id: number): Promise<void> {
    await apiDelete(`${base(oid)}/${id}`)
    await load(oid)
  }

  async function putBinding(oid: string, id: number, body: BindingWriteRequest): Promise<void> {
    await apiPut(`${base(oid)}/${id}/bindings`, body)
    await load(oid)
  }

  /**
   * The answers a binding to `itemOid` may write, so the form can offer them
   * instead of a guess. Rejects (404) when no CRF version carries the item.
   */
  async function itemAnswers(oid: string, itemOid: string): Promise<ItemAnswers> {
    return apiGet<ItemAnswers>(`${base(oid)}/item-answers?itemOid=${encodeURIComponent(itemOid)}`)
  }

  async function removeBinding(oid: string, id: number, bindingId: number): Promise<void> {
    await apiDelete(`${base(oid)}/${id}/bindings/${bindingId}`)
    await load(oid)
  }

  /**
   * Tick "performed" for the images already filed in the study, as a bind
   * would have (POST .../apply-performed). Returns how many images were gone
   * through and the ticker's outcome counts.
   */
  async function applyPerformed(oid: string): Promise<{ files: number; outcomes: Record<string, number> }> {
    return apiPost<{ files: number; outcomes: Record<string, number> }>(`${base(oid)}/apply-performed`, {})
  }

  return {
    list, isLoading, error, studyOid, load, reload, create, update, retire,
    putBinding, itemAnswers, removeBinding, applyPerformed,
  }
})
