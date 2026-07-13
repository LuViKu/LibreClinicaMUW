<script setup lang="ts">
/**
 * Phase E.8 Slice L4 (2026-06-20) — Print-friendly CRF view, SPA
 * replacement for the legacy {@code PrintEventCRFServlet} /
 * {@code PrintCRFByIdServlet} /
 * {@code PrintAllEventCRFServlet} /
 * {@code PrintAllSiteEventCRFServlet}.
 *
 * Option A per the Phase C plan: read-only HTML view + a
 * {@code @media print} stylesheet. The operator triggers the
 * browser's "Print to PDF" — same flow MUW already uses for
 * institutional forms — without the platform having to bake an
 * openhtmltopdf dependency.
 *
 * Backend: NO new endpoint. We reuse the existing
 * {@code GET /pages/api/v1/eventCrfs/{id}} the {@code CrfEntryView}
 * already consumes, so the data shape can never drift between the
 * entry form and the printable rendering.
 *
 * URL params:
 *   - {@code eventCrfOid} (path) — the event_crf_id.
 *   - {@code auto} (query, default 1) — when truthy, calls
 *     {@code window.print()} as soon as the data has loaded. Pass
 *     {@code ?auto=0} to skip the auto-print (useful when QA wants
 *     to inspect the layout in-browser).
 */
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'

import { useCrfEntryStore } from '@/stores/crfEntry'

const { t } = useI18n()
const route = useRoute()
const store = useCrfEntryStore()

const ready = ref(false)
const printed = ref(false)

function valueText(itemOid: string, currentValues: Record<string, unknown>): string {
  const v = currentValues[itemOid]
  if (v === undefined || v === null || v === '') return '—'
  if (Array.isArray(v)) {
    return v.length === 0 ? '—' : v.map(String).join(', ')
  }
  if (typeof v === 'boolean') return v ? t('printableCrf.yes') : t('printableCrf.no')
  return String(v)
}

function optionLabel(itemOid: string, values: Record<string, unknown>): string {
  if (!store.entry) return valueText(itemOid, values)
  for (const section of store.entry.schema.sections) {
    for (const item of section.items) {
      if (item.oid !== itemOid) continue
      if (item.options?.length) {
        const v = values[itemOid]
        if (v === undefined || v === null || v === '') return '—'
        // select-multi may carry a comma string or an array.
        const keys: string[] = Array.isArray(v) ? v.map(String) : String(v).split(',')
        const labels = keys.map((k) => {
          const o = item.options!.find((opt) => String(opt.code) === k.trim())
          return o ? o.label : k.trim()
        })
        return labels.join(', ')
      }
    }
  }
  return valueText(itemOid, values)
}

function printNow() {
  // Two-tick delay so Vue commits the print-only stylesheet + the
  // browser lays out before the dialog opens; without it Firefox
  // sometimes captures a half-rendered page.
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      window.print()
      printed.value = true
    })
  })
}

onMounted(async () => {
  const oid = String(route.params.eventCrfOid ?? '')
  await store.load(oid)
  ready.value = true

  // Auto-print unless explicitly disabled. Stays out of the way for
  // automated tests that drive the route without a print dialog.
  const auto = String(route.query.auto ?? '1')
  if (auto !== '0' && store.entry) {
    printNow()
  }
})
</script>

<template>
  <div class="printable-crf">
    <!-- Header — hidden in print, kept on screen for re-print + back link -->
    <header class="screen-only flex items-baseline justify-between px-6 py-3 border-b border-slate-200">
      <div class="text-sm">
        <span class="font-medium">{{ t('printableCrf.title') }}</span>
        <span v-if="store.entry" class="text-slate-500 ml-2">{{ store.entry.schema.name }}</span>
      </div>
      <div class="flex items-center gap-2">
        <button type="button" class="px-3 py-1.5 text-xs border border-slate-300 rounded bg-white hover:bg-slate-50 muw-focus" @click="printNow">
          {{ t('printableCrf.printAgain') }}
        </button>
        <button type="button" class="px-3 py-1.5 text-xs border border-slate-300 rounded bg-white hover:bg-slate-50 muw-focus" @click="$router.back()">
          {{ t('printableCrf.back') }}
        </button>
      </div>
    </header>

    <main v-if="ready && store.entry" class="px-6 py-6 max-w-3xl mx-auto text-sm">
      <h1 class="text-base font-semibold mb-1">{{ store.entry.schema.name }}<span v-if="store.entry.schema.version" class="text-slate-400 font-normal text-xs ml-2">{{ store.entry.schema.version }}</span></h1>

      <div class="text-[11px] text-slate-500 mb-6 grid grid-cols-3 gap-2">
        <div v-if="store.entry.subjectId"><dt class="inline text-slate-400">{{ t('printableCrf.subject') }}:</dt> <dd class="inline ml-1 font-medium text-slate-700">{{ store.entry.subjectId }}</dd></div>
        <div v-if="store.entry.eventLabel"><dt class="inline text-slate-400">{{ t('printableCrf.event') }}:</dt> <dd class="inline ml-1">{{ store.entry.eventLabel }}</dd></div>
        <div><dt class="inline text-slate-400">{{ t('printableCrf.eventCrfId') }}:</dt> <dd class="inline ml-1">{{ store.entry.eventCrfOid }}</dd></div>
        <div><dt class="inline text-slate-400">{{ t('printableCrf.status') }}:</dt> <dd class="inline ml-1">{{ store.entry.status }}</dd></div>
        <div v-if="store.entry.lastSavedAt"><dt class="inline text-slate-400">{{ t('printableCrf.lastSaved') }}:</dt> <dd class="inline ml-1">{{ new Date(store.entry.lastSavedAt).toLocaleString() }}</dd></div>
      </div>

      <section v-for="section in store.entry.schema.sections" :key="section.oid" class="mb-6 break-inside-avoid">
        <h2 class="text-sm font-semibold border-b border-slate-300 pb-1 mb-2">{{ section.title }}</h2>
        <p v-if="section.instructions" class="text-[11px] text-slate-500 mb-2">{{ section.instructions }}</p>

        <dl class="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1.5 text-[12px]">
          <div v-for="item in section.items" :key="item.oid" class="break-inside-avoid border-b border-slate-100 pb-1">
            <dt class="text-slate-500 text-[11px]">{{ item.label }}</dt>
            <dd class="font-medium">{{ optionLabel(item.oid, store.entry.values) }}</dd>
          </div>
        </dl>
      </section>

      <section v-for="group in store.entry.groups" :key="group.oid" class="mb-6 break-inside-avoid">
        <h2 class="text-sm font-semibold border-b border-slate-300 pb-1 mb-2">{{ group.label }}</h2>
        <table v-if="group.rows.length > 0" class="w-full text-[11px] border border-slate-200">
          <thead class="bg-slate-50">
            <tr>
              <th class="px-2 py-1 text-left">#</th>
              <th v-for="oid in group.itemOids" :key="oid" class="px-2 py-1 text-left">{{ oid }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in group.rows" :key="row.ordinal" class="border-t border-slate-100">
              <td class="px-2 py-1">{{ row.ordinal }}</td>
              <td v-for="oid in group.itemOids" :key="oid" class="px-2 py-1">{{ valueText(oid, row.values) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="text-[11px] text-slate-400 italic">{{ t('printableCrf.noRows') }}</p>
      </section>

      <footer class="mt-8 pt-3 border-t border-slate-300 text-[10px] text-slate-500 flex justify-between">
        <span>{{ t('printableCrf.footerBrand') }}</span>
        <span>{{ new Date().toLocaleString() }}</span>
      </footer>
    </main>

    <div v-else-if="ready && store.error" class="px-6 py-10 text-sm text-rose-700">{{ store.error }}</div>
    <div v-else class="px-6 py-10 text-xs text-slate-500">{{ t('common.loading') }}</div>
  </div>
</template>

<style scoped>
/* Phase E.8 L4: print-friendly stylesheet — strips chrome so the
   browser's Print to PDF captures only the form content. Scoped to
   this component so the rest of the SPA's print behaviour is
   unaffected. */
@media print {
  .screen-only { display: none !important; }
  .printable-crf {
    background: white;
    color: black;
    font-size: 11pt;
  }
  /* Avoid splitting a section across pages mid-row. */
  .break-inside-avoid { break-inside: avoid; page-break-inside: avoid; }
}

@media screen {
  .printable-crf { background: white; }
}
</style>
