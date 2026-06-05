/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiGet, apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type {
  ImportCrfPreview,
  ImportCrfRowsPage,
  ImportCrfCommitResult,
  ImportOverwriteMode,
  ImportUploadResult,
  ImportRowsPageResult,
  ImportCommitResult,
} from '@/types/importCrf'

/**
 * Phase E.6 {@code bulk-import} — Pinia store for the ImportCrfDataView
 * upload → preview → commit pipeline.
 *
 * <h2>State model</h2>
 *
 * <ul>
 *   <li>{@code preview} — the {@link ImportCrfPreview} returned by
 *       upload. Reset on study change + on every fresh upload.</li>
 *   <li>{@code extraRows} — accumulator for windowed
 *       {@link ImportCrfRowsPage} responses; the inline payload's
 *       {@code rows} (first 200) lives on {@code preview.rows}.</li>
 *   <li>{@code commitResult} — server-side {@link ImportCrfCommitResult}
 *       returned by the commit endpoint on success.</li>
 *   <li>{@code isUploading} / {@code isFetchingRows} / {@code isCommitting}
 *       — per-action busy flags; views render spinners on these without
 *       collapsing all three into one global flag.</li>
 *   <li>{@code error} — last operator-visible error message (4xx / 5xx /
 *       network). Set + cleared per call.</li>
 *   <li>{@code tokenExpired} — true when the commit / rows endpoint
 *       returned 410 so the view can render a "re-upload" CTA instead
 *       of a plain error toast.</li>
 * </ul>
 *
 * <h2>Per-study reset</h2>
 *
 * The store exports {@code reset()} which the {@code useAuthStore.pickStudy}
 * action calls before swapping the active study. Without this, a preview
 * issued under study A would still appear in the view after switching
 * to study B — and the commit endpoint would reject the token with 410
 * because the parked session checks the active study OID, leading to a
 * confusing "expired" UX.
 *
 * <h2>Auth-error policy</h2>
 *
 * 401 / 403 propagate (the global router guard handles them); every
 * other failure mode collapses into the discriminated-union return type
 * so the view can branch on {@code ok} without touching error
 * envelopes.
 */
export const useImportCrfStore = defineStore('importCrf', () => {
  const preview = ref<ImportCrfPreview | null>(null)
  const extraRows = ref<ImportCrfPreview['rows']>([])
  const commitResult = ref<ImportCrfCommitResult | null>(null)
  const isUploading = ref(false)
  const isFetchingRows = ref(false)
  const isCommitting = ref(false)
  const error = ref<string | null>(null)
  const tokenExpired = ref(false)

  /**
   * Upload an ODM 1.3 XML file to {@code POST /api/v1/import}.
   *
   * <p>Uses {@code fetch} directly rather than {@code apiPost} because
   * the request body is {@code multipart/form-data} — the standard
   * client wraps JSON.
   */
  async function uploadFile(file: File): Promise<ImportUploadResult> {
    isUploading.value = true
    error.value = null
    tokenExpired.value = false
    extraRows.value = []
    commitResult.value = null
    const form = new FormData()
    form.append('file', file)
    try {
      const res = await fetch('/LibreClinica/pages/api/v1/import', {
        method: 'POST',
        body: form,
        credentials: 'include',
      })
      if (!res.ok) {
        let body: { message?: string; errors?: Array<{ field?: string; message?: string }> } = {}
        try { body = await res.json() } catch { /* not JSON */ }
        if (res.status === 401 || res.status === 403) {
          throw new ApiError(res.status, body.message ?? res.statusText, body)
        }
        const messages = (body.errors ?? [])
          .map((e) => e?.message)
          .filter((m): m is string => typeof m === 'string')
        const message = body.message
          ?? (res.status === 415 ? 'errorFileType'
              : res.status === 422 ? 'errorWrongStudy'
              : res.status === 400 ? 'errorXsd'
              : `Upload failed (HTTP ${res.status}).`)
        error.value = message
        return { ok: false, message, errors: messages }
      }
      const body: ImportCrfPreview = await res.json()
      preview.value = body
      return { ok: true, preview: body }
    } catch (e) {
      if (e instanceof ApiError) throw e
      if (e instanceof ApiNetworkError) {
        const message = 'Backend unreachable — upload failed.'
        error.value = message
        return { ok: false, message, errors: [] }
      }
      const message = e instanceof Error ? e.message : 'Upload failed.'
      error.value = message
      return { ok: false, message, errors: [] }
    } finally {
      isUploading.value = false
    }
  }

  /**
   * Fetch a window of preview rows beyond the inline cap. Wraps
   * {@code GET /api/v1/import/{token}/rows?offset=&limit=}.
   *
   * <p>410 marks the parked session as gone — the view renders a
   * "re-upload" CTA. We flip {@code tokenExpired} and clear
   * {@code preview} so the wizard rolls back to step 1 cleanly.
   */
  async function fetchMoreRows(offset: number, limit = 200): Promise<ImportRowsPageResult> {
    if (preview.value == null) {
      const message = 'No preview to page — upload the XML first.'
      error.value = message
      return { ok: false, message, expired: false }
    }
    const token = preview.value.previewToken
    isFetchingRows.value = true
    error.value = null
    try {
      const page = await apiGet<ImportCrfRowsPage>(
        `/pages/api/v1/import/${encodeURIComponent(token)}/rows?offset=${offset}&limit=${limit}`,
      )
      // Append-only — the view caches each window so re-rendering a
      // table scrolled past row 200 is O(1).
      for (const row of page.rows) extraRows.value.push(row)
      return { ok: true, page }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiError && e.status === 410) {
        tokenExpired.value = true
        preview.value = null
        extraRows.value = []
        const message = 'Preview expired — re-upload the XML.'
        error.value = message
        return { ok: false, message, expired: true }
      }
      if (e instanceof ApiError) {
        const b = e.body as { message?: string } | null
        const message = b?.message ?? `Fetch rows failed (HTTP ${e.status}).`
        error.value = message
        return { ok: false, message, expired: false }
      }
      if (e instanceof ApiNetworkError) {
        const message = 'Backend unreachable — could not page rows.'
        error.value = message
        return { ok: false, message, expired: false }
      }
      const message = e instanceof Error ? e.message : 'Fetch rows failed.'
      error.value = message
      return { ok: false, message, expired: false }
    } finally {
      isFetchingRows.value = false
    }
  }

  /**
   * Commit the parked preview. Wraps
   * {@code POST /api/v1/import/commit}. The 14d cluster ships a
   * backend that returns 501 (persistence extraction pending) — the
   * SPA branch lights up the {@code expired:false} path with the
   * backend message so the operator sees the deterministic
   * "pipeline staged" notice rather than a silent success.
   */
  async function commit(
    reasonForChange: string | null,
    overwriteMode: ImportOverwriteMode = 'replace',
  ): Promise<ImportCommitResult> {
    if (preview.value == null) {
      const message = 'No preview to commit — upload the XML first.'
      error.value = message
      return { ok: false, message, expired: false }
    }
    const token = preview.value.previewToken
    isCommitting.value = true
    error.value = null
    try {
      const body = await apiPost<ImportCrfCommitResult>(
        '/pages/api/v1/import/commit',
        {
          previewToken: token,
          reasonForChange: reasonForChange?.trim() || undefined,
          overwriteMode,
        },
      )
      commitResult.value = body
      return { ok: true, result: body }
    } catch (e) {
      if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) throw e
      if (e instanceof ApiError && e.status === 410) {
        tokenExpired.value = true
        preview.value = null
        extraRows.value = []
        const message = 'Preview token expired — re-upload the XML.'
        error.value = message
        return { ok: false, message, expired: true }
      }
      if (e instanceof ApiError) {
        const b = e.body as { message?: string } | null
        const message = b?.message ?? `Commit failed (HTTP ${e.status}).`
        error.value = message
        return { ok: false, message, expired: false }
      }
      if (e instanceof ApiNetworkError) {
        const message = 'Backend unreachable — commit failed.'
        error.value = message
        return { ok: false, message, expired: false }
      }
      const message = e instanceof Error ? e.message : 'Commit failed.'
      error.value = message
      return { ok: false, message, expired: false }
    } finally {
      isCommitting.value = false
    }
  }

  /**
   * Clear all store state. Called by
   * {@code useAuthStore.pickStudy} so a study switch wipes any
   * in-flight preview cleanly. Also called by the view's
   * "start over" affordance.
   */
  function reset(): void {
    preview.value = null
    extraRows.value = []
    commitResult.value = null
    isUploading.value = false
    isFetchingRows.value = false
    isCommitting.value = false
    error.value = null
    tokenExpired.value = false
  }

  return {
    preview,
    extraRows,
    commitResult,
    isUploading,
    isFetchingRows,
    isCommitting,
    error,
    tokenExpired,
    uploadFile,
    fetchMoreRows,
    commit,
    reset,
  }
})
