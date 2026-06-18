<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — single parse row.
 *
 * Mirrors the mockup's `ParseRow` (oct-portal.jsx ~ line 215). One row
 * per file currently being read. Two visual sub-states:
 *
 *  - Header still being read (no `scan` yet) — skeleton shimmer in
 *    the PatientId / scan-date / eye columns.
 *  - Header parsed (`scan` populated) — real values shown + the
 *    trailing "Studie & Visite werden ermittelt…" status line while
 *    /resolve fans out.
 *
 * The row deliberately doesn't render its own border bar; the
 * parent ParseQueue groups them with shared dividers.
 */
import { computed } from 'vue'
import EyeBadge from './EyeBadge.vue'
import type { ReviewRow } from '@/stores/octPortal'

interface Props {
  row: ReviewRow
}

const props = defineProps<Props>()

/** True once the .e2e header has been read — `scan` is populated.
 *  False while we're still reading bytes from disk. */
const done = computed(() => props.row.scan !== undefined)

const sizeLabel = computed(() => formatBytes(props.row.file.size))
const dateLabel = computed(() => {
  if (!props.row.scan?.scanDate) return ''
  return formatScanDateTime(props.row.scan.scanDate)
})

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

function formatScanDateTime(d: Date): string {
  // 17-Jun-2026 · 09:12 — matches the mockup's column rendering.
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
  const dd = String(d.getDate()).padStart(2, '0')
  const mm = months[d.getMonth()]
  const yyyy = d.getFullYear()
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${dd}-${mm}-${yyyy} · ${hh}:${mi}`
}
</script>

<template>
  <div
    class="flex items-center gap-4 px-5 py-3.5 border-t border-slate-100"
    :data-testid="`parse-row-${props.row.rowId}`"
  >
    <div class="w-[224px] flex items-center gap-3 shrink-0">
      <span class="w-9 h-9 rounded-lg flex items-center justify-center shrink-0 bg-muw-blue-50 text-muw-blue">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
        </svg>
      </span>
      <div class="min-w-0">
        <div class="text-[13px] font-semibold text-slate-900 truncate" :title="props.row.file.name">{{ props.row.file.name }}</div>
        <div class="text-[11px] text-slate-400">{{ sizeLabel }}</div>
      </div>
    </div>

    <template v-if="done">
      <div class="w-[96px] shrink-0">
        <span class="font-mono text-[12px] font-medium text-slate-700">{{ props.row.scan?.patientId }}</span>
      </div>
      <div class="w-[150px] shrink-0 text-[12px] text-slate-500">{{ dateLabel }}</div>
      <div class="w-[68px] shrink-0">
        <EyeBadge :laterality="props.row.scan?.laterality ?? null" />
      </div>
      <div class="flex-1 inline-flex items-center gap-2 text-[12px] text-slate-500">
        <span class="text-muw-sky-600 inline-block">
          <svg class="muw-portal-spin" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">
            <path d="M21 12a9 9 0 1 1-6.2-8.5" opacity="0.9" />
          </svg>
        </span>
        Studie &amp; Visite werden ermittelt…
      </div>
    </template>

    <template v-else>
      <div class="w-[96px] shrink-0"><div class="muw-skel h-3.5 w-14"></div></div>
      <div class="w-[150px] shrink-0"><div class="muw-skel h-3.5 w-24"></div></div>
      <div class="w-[68px] shrink-0"><div class="muw-skel h-3.5 w-10"></div></div>
      <div class="flex-1 inline-flex items-center gap-2 text-[12px] text-slate-500">
        <span class="text-muw-blue inline-block">
          <svg class="muw-portal-spin" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">
            <path d="M21 12a9 9 0 1 1-6.2-8.5" opacity="0.9" />
          </svg>
        </span>
        Header wird gelesen…
      </div>
    </template>

    <div class="shrink-0 text-[11px] text-slate-400">{{ done ? '100 %' : '…' }}</div>
  </div>
</template>
