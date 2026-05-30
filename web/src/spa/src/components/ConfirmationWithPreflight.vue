<script setup lang="ts">
/**
 * Phase E.3 primitive — Confirmation with pre-flight.
 *
 * Used by Sign Subject, Study Lock, Archive — every "one-way regulatory
 * action" surface where the user needs to see a checklist of pass/warn/info
 * conditions before reaching the attestation step.
 *
 * The primitive itself is just the pre-flight checklist. The casebook
 * snapshot table + the attestation block are composed by the parent;
 * keeping this primitive narrow makes it reusable for non-signature
 * confirmations (e.g. "lock subject's data" without a casebook snapshot).
 */

export interface PreflightRow {
  /** Stable id used as the key. */
  id: string
  /** pass = green check, warn = amber triangle, info = neutral dot, blocker = red */
  status: 'pass' | 'warn' | 'info' | 'blocker'
  /** Main row label. */
  title: string
  /** Optional supporting text shown beneath. */
  detail?: string
}

interface Props {
  rows: PreflightRow[]
  /** Optional section heading; defaults to no heading rendered. */
  heading?: string
}

defineProps<Props>()
</script>

<template>
  <section class="bg-white border border-slate-200 rounded-muw p-5">
    <h2
      v-if="heading"
      class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-3"
    >{{ heading }}</h2>

    <ul class="divide-y divide-slate-100" :aria-label="heading">
      <li
        v-for="row in rows"
        :key="row.id"
        class="flex items-start gap-3 py-2.5"
      >
        <span
          class="mt-0.5 inline-flex items-center justify-center w-5 h-5 rounded-full shrink-0"
          :class="{
            'bg-muw-teal-100 text-muw-teal-700': row.status === 'pass',
            'bg-amber-100 text-amber-700':       row.status === 'warn',
            'bg-rose-100 text-rose-700':         row.status === 'blocker',
            'bg-slate-100 text-slate-500':       row.status === 'info',
          }"
        >
          <!-- pass: check -->
          <svg v-if="row.status === 'pass'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          <!-- warn + blocker: triangle -->
          <svg v-else-if="row.status === 'warn' || row.status === 'blocker'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
            <line x1="12" x2="12" y1="9" y2="13" />
            <line x1="12" x2="12.01" y1="17" y2="17" />
          </svg>
          <!-- info: circle dot -->
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
            <circle cx="12" cy="12" r="10" />
          </svg>
        </span>

        <div class="flex-1 min-w-0">
          <div class="font-medium text-slate-900">{{ row.title }}</div>
          <div v-if="row.detail" class="text-xs text-slate-500 mt-0.5 leading-relaxed">{{ row.detail }}</div>
        </div>
      </li>
    </ul>
  </section>
</template>
