<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * Phase E.6 — Data Export Phase 5 — per-subject snapshot download.
 *
 * <p>A small "Download data ▾" dropdown that fires
 * {@code POST /pages/api/v1/studies/{studyOid}/subjects/{label}/export}
 * with the chosen format ({@code odm | csv | pdf}). Backend streams
 * the body with a Content-Disposition filename which we honour
 * verbatim; if it's missing we synthesise a sensible fallback so the
 * download doesn't end up named after the random blob URL.
 *
 * <p>The dropdown is mounted next to the Sign button on
 * SubjectDetailView and on the per-row action cell of
 * SubjectMatrixView (compact mode). When {@code compact} is true the
 * trigger is icon-only with no label — the matrix's column gets
 * crowded otherwise.
 */

interface Props {
  /** Active study OID; usually `authStore.user?.activeStudy?.oid`. */
  studyOid: string | null
  /** Subject label (a.k.a. {@code subject.id}). */
  subjectLabel: string
  /** Tighter trigger for row-level placement. */
  compact?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  compact: false,
})

const { t } = useI18n()

const open = ref(false)
const loading = ref<'odm' | 'csv' | 'pdf' | null>(null)
const error = ref<string | null>(null)

type Format = 'odm' | 'csv' | 'pdf'
const FORMATS: Format[] = ['odm', 'csv', 'pdf']

const disabled = computed(() => !props.studyOid || !props.subjectLabel)

function toggle() {
  if (disabled.value) return
  open.value = !open.value
  if (open.value) error.value = null
}

/**
 * RFC 6266 / RFC 5987 filename extraction. Accepts both
 * `filename="x.csv"` and `filename*=UTF-8''x.csv` (we don't decode
 * percent-escapes — the backend's sanitiseFilename keeps the label to
 * ASCII alnum so a UTF-8 escape is unlikely; if it happens, the
 * browser-native decoding takes over once we set anchor.download).
 */
function parseFilename(disposition: string | null): string | null {
  if (!disposition) return null
  // Prefer filename*= (RFC 5987) if present.
  const rfc5987 = /filename\*\s*=\s*[^']*''([^;]+)/i.exec(disposition)
  if (rfc5987?.[1]) {
    try {
      return decodeURIComponent(rfc5987[1].trim().replace(/^"|"$/g, ''))
    } catch {
      return rfc5987[1].trim()
    }
  }
  const rfc6266 = /filename\s*=\s*("([^"]+)"|([^;]+))/i.exec(disposition)
  if (rfc6266) {
    return (rfc6266[2] ?? rfc6266[3] ?? '').trim()
  }
  return null
}

function fallbackFilename(fmt: Format): string {
  const ext = fmt === 'odm' ? 'xml' : fmt
  const yyyymmdd = new Date().toISOString().slice(0, 10).replace(/-/g, '')
  const safeLabel = props.subjectLabel.replace(/[^A-Za-z0-9_-]/g, '_') || 'subject'
  return `${safeLabel}_${fmt}_${yyyymmdd}.${ext}`
}

async function download(fmt: Format) {
  if (disabled.value || loading.value) return
  loading.value = fmt
  error.value = null
  try {
    const url = `/LibreClinica/pages/api/v1/studies/${encodeURIComponent(
      props.studyOid as string,
    )}/subjects/${encodeURIComponent(props.subjectLabel)}/export`

    const response = await fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/octet-stream, application/xml, text/csv, application/pdf',
      },
      body: JSON.stringify({ format: fmt }),
    })

    if (!response.ok) {
      // Backend returns JSON {message: ...} on 4xx/5xx; surface that
      // when present, otherwise fall back to the status line.
      let detail = `${response.status}`
      try {
        const contentType = response.headers.get('content-type') ?? ''
        if (contentType.includes('application/json')) {
          const j = await response.json()
          if (j && typeof j === 'object' && 'message' in j) {
            detail = String((j as { message: unknown }).message)
          }
        }
      } catch {
        // ignore — keep the status code as the detail
      }
      throw new Error(detail)
    }

    const blob = await response.blob()
    const filename = parseFilename(response.headers.get('Content-Disposition')) ?? fallbackFilename(fmt)
    triggerBrowserDownload(blob, filename)
    open.value = false
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = null
  }
}

/**
 * The standard "anchor + click + revoke" pattern. Wrapped in a
 * function so tests can spy on it and so we don't leak object URLs
 * across the long-lived SPA session (Chrome's leak threshold is
 * ~256 MB but every revoke is cheap).
 */
function triggerBrowserDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.rel = 'noopener'
  // Some browsers (Safari) require the anchor to be in the DOM.
  document.body.appendChild(a)
  a.click()
  a.remove()
  // Give the browser a tick to start the download before revoke.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

// Click-away close.
function onDocumentClick(event: MouseEvent) {
  if (!root.value) return
  if (!root.value.contains(event.target as Node)) {
    open.value = false
  }
}

const root = ref<HTMLElement | null>(null)

if (typeof document !== 'undefined') {
  document.addEventListener('click', onDocumentClick)
}

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') {
    document.removeEventListener('click', onDocumentClick)
  }
})
</script>

<template>
  <div ref="root" class="relative inline-block">
    <button
      type="button"
      :class="[
        'inline-flex items-center gap-1.5 border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700 disabled:opacity-50 disabled:cursor-not-allowed',
        compact ? 'px-2 py-1 text-[11px]' : 'px-3 py-2 text-xs',
      ]"
      :aria-haspopup="'menu'"
      :aria-expanded="open"
      :title="t('subjectExport.title')"
      :disabled="disabled"
      :data-testid="'subject-export-trigger'"
      @click.stop="toggle"
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="7 10 12 15 17 10" />
        <line x1="12" x2="12" y1="15" y2="3" />
      </svg>
      <span v-if="!compact">{{ t('subjectExport.title') }}</span>
      <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
        <polyline points="6 9 12 15 18 9" />
      </svg>
    </button>

    <div
      v-if="open"
      role="menu"
      class="absolute right-0 z-20 mt-1 min-w-[180px] rounded-md border border-slate-200 bg-white shadow-lg py-1"
      data-testid="subject-export-menu"
    >
      <p class="px-3 pt-1.5 pb-1 text-[10px] uppercase tracking-wider text-slate-400">
        {{ t('subjectExport.formatLabel') }}
      </p>
      <button
        v-for="fmt in FORMATS"
        :key="fmt"
        type="button"
        role="menuitem"
        class="w-full text-left px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50 inline-flex items-center justify-between gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
        :disabled="loading !== null"
        :data-testid="`subject-export-${fmt}`"
        @click.stop="download(fmt)"
      >
        <span>{{ t(`subjectExport.format.${fmt}`) }}</span>
        <span v-if="loading === fmt" class="text-[10px] text-slate-400">
          {{ t('subjectExport.downloading') }}
        </span>
      </button>
      <p
        v-if="error"
        class="px-3 pt-2 pb-1 text-[11px] text-rose-700 border-t border-slate-100 mt-1"
        data-testid="subject-export-error"
      >
        {{ t('subjectExport.failed') }}: {{ error }}
      </p>
    </div>
  </div>
</template>
