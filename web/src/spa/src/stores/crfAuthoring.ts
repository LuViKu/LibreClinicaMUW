import { defineStore } from 'pinia'
import { ref } from 'vue'
import { apiPost, ApiError, ApiNetworkError } from '@/api/client'
import type { CrfVersion } from '@/types/crfLibrary'

/**
 * Phase E.6 Milestone A — manual eCRF authoring store.
 *
 * <p>Holds the draft tree for one CRF version being authored in the
 * SPA wizard. Milestone A locks the scope to one section + two-three
 * items + one TEXT response set; Milestones B/C/D extend the draft
 * shape to cover the full XLS feature surface.
 *
 * <p><b>Persistence strategy</b>: final-submit only. No POSTs fire
 * until the operator hits "Create" — closing the wizard discards the
 * local draft. This matches the plan's "Wizard state persistence"
 * decision (DR-014-style trade-off: per-step POSTs orphan rows on
 * cancel).
 */

/** Authoring DTO mirrors the backend record CrfVersionAuthoringRequest. */
export type AuthoringDataType = 'ST' | 'INTEGER' | 'BL'

export interface AuthoringItem {
  name: string
  oid: string
  descriptionLabel: string
  leftItemText: string
  dataType: AuthoringDataType
  required: boolean
}

export interface AuthoringSection {
  label: string
  title: string
  instructions: string
  ordinal: number
  items: AuthoringItem[]
}

export interface AuthoringDraft {
  versionName: string
  versionDescription: string
  revisionNotes: string
  sections: AuthoringSection[]
}

export type AuthoringSubmitResult =
  | { ok: true; version: CrfVersion }
  | { ok: false; fieldErrors: Record<string, string>; parseErrors: string[]; message?: string }

function emptyDraft(): AuthoringDraft {
  return {
    versionName: '',
    versionDescription: '',
    revisionNotes: '',
    sections: [
      {
        label: 'S1',
        title: 'Section 1',
        instructions: '',
        ordinal: 1,
        items: [],
      },
    ],
  }
}

function emptyItem(): AuthoringItem {
  return {
    name: '',
    oid: '',
    descriptionLabel: '',
    leftItemText: '',
    dataType: 'ST',
    required: false,
  }
}

export const useCrfAuthoringStore = defineStore('crfAuthoring', () => {
  const draft = ref<AuthoringDraft>(emptyDraft())
  const isSubmitting = ref(false)
  const error = ref<string | null>(null)

  function reset(): void {
    draft.value = emptyDraft()
    error.value = null
    isSubmitting.value = false
  }

  function setMetadata(patch: Partial<Pick<AuthoringDraft, 'versionName' | 'versionDescription' | 'revisionNotes'>>): void {
    if (patch.versionName !== undefined) draft.value.versionName = patch.versionName
    if (patch.versionDescription !== undefined) draft.value.versionDescription = patch.versionDescription
    if (patch.revisionNotes !== undefined) draft.value.revisionNotes = patch.revisionNotes
  }

  function setVersionName(versionName: string): void {
    draft.value.versionName = versionName
  }

  function setVersionDescription(versionDescription: string): void {
    draft.value.versionDescription = versionDescription
  }

  function addSection(seed?: Partial<AuthoringSection>): void {
    const next = draft.value.sections.length + 1
    draft.value.sections.push({
      label: seed?.label ?? `S${next}`,
      title: seed?.title ?? `Section ${next}`,
      instructions: seed?.instructions ?? '',
      ordinal: seed?.ordinal ?? next,
      items: seed?.items ?? [],
    })
  }

  function addItem(sectionIndex: number, seed?: Partial<AuthoringItem>): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    section.items.push({ ...emptyItem(), ...seed })
  }

  function setItemField<K extends keyof AuthoringItem>(
    sectionIndex: number,
    itemIndex: number,
    field: K,
    value: AuthoringItem[K],
  ): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    const item = section.items[itemIndex]
    if (!item) return
    item[field] = value
  }

  function removeItem(sectionIndex: number, itemIndex: number): void {
    const section = draft.value.sections[sectionIndex]
    if (!section) return
    section.items.splice(itemIndex, 1)
  }

  /**
   * Build the wire payload from the draft. Trims whitespace and drops
   * the SPA-only `oid` field on items (the backend auto-generates OIDs
   * via the legacy parser for Milestone A).
   */
  function buildPayload(): AuthoringDraft {
    return {
      versionName: draft.value.versionName.trim(),
      versionDescription: draft.value.versionDescription.trim(),
      revisionNotes: draft.value.revisionNotes.trim(),
      sections: draft.value.sections.map((s, idx) => ({
        label: s.label.trim(),
        title: s.title.trim(),
        instructions: s.instructions.trim(),
        ordinal: s.ordinal || idx + 1,
        items: s.items.map((it) => ({
          name: it.name.trim(),
          oid: it.oid.trim(),
          descriptionLabel: it.descriptionLabel.trim(),
          leftItemText: it.leftItemText.trim(),
          dataType: it.dataType,
          required: it.required,
        })),
      })),
    }
  }

  async function submit(crfOid: string): Promise<AuthoringSubmitResult> {
    isSubmitting.value = true
    error.value = null
    try {
      const payload = buildPayload()
      const version = await apiPost<CrfVersion>(
        `/pages/api/v1/crfs/${encodeURIComponent(crfOid)}/versions`,
        payload,
      )
      return { ok: true, version }
    } catch (e) {
      return mapSubmitError(e)
    } finally {
      isSubmitting.value = false
    }
  }

  function mapSubmitError(e: unknown): AuthoringSubmitResult {
    if (e instanceof ApiError && (e.isUnauthorized || e.isForbidden)) {
      error.value = (e.body as { message?: string } | null)?.message
        ?? `CRF authoring nicht erlaubt (HTTP ${e.status}).`
      return {
        ok: false,
        fieldErrors: {},
        parseErrors: [],
        message: error.value ?? undefined,
      }
    }
    if (e instanceof ApiError) {
      const body = e.body as { message?: string; errors?: Array<{ field: string; message: string }> } | null
      const fieldErrors: Record<string, string> = {}
      const parseErrors: string[] = []
      if (body?.errors) {
        for (const fe of body.errors) {
          // Parser rejections from the synthesised workbook share
          // field="body" — surface them as a separate list so the view
          // can render them in one place rather than overwriting.
          if (fe.field === 'body') parseErrors.push(fe.message)
          else fieldErrors[fe.field] = fe.message
        }
      }
      return {
        ok: false,
        fieldErrors,
        parseErrors,
        message: body?.message ?? `Authoring fehlgeschlagen (HTTP ${e.status}).`,
      }
    }
    if (e instanceof ApiNetworkError) {
      return {
        ok: false,
        fieldErrors: {},
        parseErrors: [],
        message: 'Backend nicht erreichbar — Authoring fehlgeschlagen.',
      }
    }
    return {
      ok: false,
      fieldErrors: {},
      parseErrors: [],
      message: e instanceof Error ? e.message : 'Unbekannter Fehler.',
    }
  }

  return {
    draft,
    isSubmitting,
    error,
    reset,
    setMetadata,
    setVersionName,
    setVersionDescription,
    addSection,
    addItem,
    setItemField,
    removeItem,
    buildPayload,
    submit,
  }
})
