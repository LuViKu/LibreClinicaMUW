<script setup lang="ts">
/**
 * App-feedback Wave 1D (2026-06-19) — "Vom letzten Besuch übernehmen"
 * modal. Fetches the most-recent COMPLETED event_crf of the same CRF
 * definition for the current subject and shows each item's prior
 * value with a per-row checkbox. The operator picks which values to
 * carry forward; on confirm the modal emits the selected map back to
 * {@link CrfEntryView} which applies it to the local
 * {@code crfEntry} store. Nothing is auto-applied without confirmation.
 *
 * <p>Wire contract for the GET endpoint:
 * <pre>
 * GET /pages/api/v1/eventCrfs/{eventCrfId}/previous-values
 * 200 { sourceEventCrfId, sourceCompletedAt, values: [{ itemOid, value, itemLabel }] }
 * 404 — no prior completed CRF exists
 * 403 — site visibility / study-mismatch
 * </pre>
 *
 * <p>The modal stays focused on the per-item checkbox grid: there is
 * no inline edit affordance. Operators who want to change a copied
 * value un-check the row and type it themselves in the entry form
 * after the modal closes.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from './Modal.vue'
import { apiGet, ApiError, ApiNetworkError } from '@/api/client'

interface PreviousValue {
  itemOid: string
  value: string
  itemLabel: string
}

interface PreviousValuesResponse {
  sourceEventCrfId: number
  sourceCompletedAt: string
  values: PreviousValue[]
}

interface Props {
  open: boolean
  /** Numeric event_crf_id of the currently-open entry. */
  currentEventCrfId: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
  'update:open': [value: boolean]
  /**
   * The operator confirmed the selection. Payload is a map of
   * itemOid → value, one entry per checked row. The receiver is
   * responsible for applying the values to its store via setValue.
   */
  apply: [values: Record<string, string>]
}>()

const { t } = useI18n()

const isLoading = ref(false)
const loadError = ref<string | null>(null)
/** null = pre-fetch or 404 (no source visit); non-null = fetched data. */
const response = ref<PreviousValuesResponse | null>(null)
/** itemOid → "checked?" map. Defaults to true on every loaded row. */
const checkedByOid = ref<Record<string, boolean>>({})

watch(
  () => props.open,
  (open) => {
    if (open) {
      void fetchPrevious()
    } else {
      // Reset so a re-open re-fetches.
      response.value = null
      loadError.value = null
      checkedByOid.value = {}
    }
  },
  { immediate: true },
)

async function fetchPrevious() {
  isLoading.value = true
  loadError.value = null
  response.value = null
  try {
    const data = await apiGet<PreviousValuesResponse>(
      `/pages/api/v1/eventCrfs/${encodeURIComponent(props.currentEventCrfId)}/previous-values`,
    )
    response.value = data
    // Default-check every row so the common case (apply all) needs
    // only one click.
    const initialChecked: Record<string, boolean> = {}
    for (const v of data.values) {
      initialChecked[v.itemOid] = true
    }
    checkedByOid.value = initialChecked
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      // No prior completed visit — render the empty state.
      response.value = null
      loadError.value = null
    } else if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      loadError.value = body?.message ?? t('crfEntry.prefill.errorFetch')
    } else if (e instanceof ApiNetworkError) {
      loadError.value = t('crfEntry.prefill.errorFetch')
    } else {
      loadError.value = e instanceof Error ? e.message : t('crfEntry.prefill.errorFetch')
    }
  } finally {
    isLoading.value = false
  }
}

const hasValues = computed(() => response.value != null && response.value.values.length > 0)

const sourceDateLabel = computed(() => {
  if (!response.value) return ''
  // The wire format is an ISO instant or date — render the locale
  // date-only form so the operator sees "Quelle: Visite vom 15.03.2026".
  const d = new Date(response.value.sourceCompletedAt)
  if (Number.isNaN(d.getTime())) return response.value.sourceCompletedAt
  return d.toLocaleDateString()
})

function onConfirm() {
  if (!response.value) {
    emit('close')
    emit('update:open', false)
    return
  }
  const out: Record<string, string> = {}
  for (const row of response.value.values) {
    if (checkedByOid.value[row.itemOid]) {
      out[row.itemOid] = row.value
    }
  }
  emit('apply', out)
  emit('close')
  emit('update:open', false)
}

function onCancel() {
  emit('close')
  emit('update:open', false)
}

function toggleAll(value: boolean) {
  if (!response.value) return
  const next: Record<string, boolean> = {}
  for (const v of response.value.values) {
    next[v.itemOid] = value
  }
  checkedByOid.value = next
}
</script>

<template>
  <Modal :open="open" labelled-by="crf-prefill-modal-title" panel-class="max-w-3xl" @close="onCancel">
    <template #header>
      <h2 id="crf-prefill-modal-title" class="text-base font-semibold text-slate-900">
        {{ t('crfEntry.prefill.modalTitle') }}
      </h2>
      <p v-if="response" class="mt-0.5 text-xs text-slate-500" data-testid="prefill-source-label">
        {{ t('crfEntry.prefill.sourceLabel', { date: sourceDateLabel }) }}
      </p>
    </template>

    <div v-if="isLoading" class="text-sm text-slate-500 italic" data-testid="prefill-loading">
      {{ t('common.loading') }}
    </div>

    <div
      v-else-if="loadError"
      class="rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-sm text-rose-800"
      role="alert"
      data-testid="prefill-error"
    >
      {{ loadError }}
    </div>

    <div
      v-else-if="!hasValues"
      class="text-sm text-slate-500 italic"
      data-testid="prefill-empty"
    >
      {{ t('crfEntry.prefill.empty') }}
    </div>

    <div v-else>
      <div class="flex items-center justify-end gap-3 pb-2 text-xs">
        <button
          type="button"
          class="text-muw-blue underline-offset-2 hover:underline"
          data-testid="prefill-check-all"
          @click="toggleAll(true)"
        >
          {{ t('crfEntry.prefill.checkAll') }}
        </button>
        <button
          type="button"
          class="text-slate-500 underline-offset-2 hover:underline"
          data-testid="prefill-uncheck-all"
          @click="toggleAll(false)"
        >
          {{ t('crfEntry.prefill.uncheckAll') }}
        </button>
      </div>
      <table class="w-full border border-slate-200 rounded-md overflow-hidden text-sm" data-testid="prefill-table">
        <thead class="bg-slate-50">
          <tr class="text-left text-[12px] uppercase tracking-wide text-slate-500">
            <th class="px-3 py-2 w-12">{{ t('crfEntry.prefill.colApply') }}</th>
            <th class="px-3 py-2">{{ t('crfEntry.prefill.colItem') }}</th>
            <th class="px-3 py-2">{{ t('crfEntry.prefill.colValue') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in response!.values"
            :key="row.itemOid"
            class="border-t border-slate-100 hover:bg-slate-50/60"
            :data-testid="`prefill-row-${row.itemOid}`"
          >
            <td class="px-3 py-2 align-top">
              <input
                v-model="checkedByOid[row.itemOid]"
                type="checkbox"
                class="rounded border-slate-300 text-muw-blue focus:ring-2 focus:ring-muw-blue-500"
                :data-testid="`prefill-checkbox-${row.itemOid}`"
                :aria-label="row.itemLabel"
              />
            </td>
            <td class="px-3 py-2 align-top text-slate-900">
              <div class="font-medium">{{ row.itemLabel }}</div>
              <div class="text-[11px] text-slate-400 font-mono">{{ row.itemOid }}</div>
            </td>
            <td class="px-3 py-2 align-top text-slate-700 break-words">
              {{ row.value }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <template #footer>
      <button
        type="button"
        class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
        data-testid="prefill-cancel"
        @click="onCancel"
      >
        {{ t('crfEntry.prefill.cancel') }}
      </button>
      <button
        type="button"
        class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:bg-slate-300"
        :disabled="isLoading || !hasValues"
        data-testid="prefill-confirm"
        @click="onConfirm"
      >
        {{ t('crfEntry.prefill.confirm') }}
      </button>
    </template>
  </Modal>
</template>
