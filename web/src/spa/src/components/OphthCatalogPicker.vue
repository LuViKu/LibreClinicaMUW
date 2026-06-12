<script setup lang="ts">
/**
 * Phase E.6 ophth-field-catalog (2026-06-11) — wizard catalog picker.
 *
 * Modal that lists the active ophthalmology field catalog entries and
 * lets the operator pick one to drop into the current section. The
 * picker emits an {@code add} event carrying the entry's metadata;
 * the wizard's section step routes that into
 * {@code crfAuthoring.addCatalogItem}.
 *
 * <p>Why an explicit picker — without it the wizard's "+ Item
 * hinzufügen" button drops a free-form item the operator has to label
 * + OID + unit by hand, and we end up with the {@code SPECTRALIS_DONE}
 * vs {@code SPECTRALIS_OCT_DONE} mismatch that broke baselines on the
 * GA-001 instance. Picking from the catalog guarantees the OID + label
 * + unit + modality binding are aligned with the institution-wide
 * registry from the first authoring keystroke.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import { useOphthFieldCatalogStore } from '@/stores/ophthFieldCatalog'
import type { OphthFieldCatalogEntry } from '@/types/ophthFieldCatalog'

interface Props {
  open: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  /** Operator picked an entry — wizard appends item(s) to the section. */
  add: [entry: OphthFieldCatalogEntry]
  'update:open': [value: boolean]
}>()

const { t } = useI18n()
const store = useOphthFieldCatalogStore()
const query = ref('')

watch(
  () => props.open,
  (isOpen) => {
    if (!isOpen) return
    // Lazy-load on first open; the store is idempotent so re-opening
    // doesn't re-hit the network.
    void store.load()
    query.value = ''
  },
)

const filtered = computed<OphthFieldCatalogEntry[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (q === '') return store.entries
  return store.entries.filter((e) =>
    e.code.toLowerCase().includes(q)
    || e.labelDe.toLowerCase().includes(q)
    || e.labelEn.toLowerCase().includes(q),
  )
})

function onPick(entry: OphthFieldCatalogEntry) {
  emit('add', entry)
  emit('update:open', false)
}

function cancel() {
  emit('update:open', false)
}

function widgetLabel(widget: OphthFieldCatalogEntry['widget']): string {
  return t(`crfLibrary.ophthCatalog.widget.${widget}`)
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="ophth-catalog-picker-title"
    panel-class="max-w-3xl"
    @update:open="(v) => emit('update:open', v)"
    @close="cancel"
  >
    <template #header>
      <div>
        <h2 id="ophth-catalog-picker-title" class="text-lg font-semibold tracking-tight">
          {{ t('crfLibrary.ophthCatalog.title') }}
        </h2>
        <p class="text-xs text-slate-500 mt-1">
          {{ t('crfLibrary.ophthCatalog.subtitle') }}
        </p>
      </div>
    </template>

    <div class="space-y-3">
      <input
        v-model="query"
        type="search"
        :placeholder="t('crfLibrary.ophthCatalog.searchPlaceholder')"
        class="w-full px-3 py-2 border border-slate-300 rounded-md text-sm muw-focus"
        data-testid="ophth-catalog-search"
      />

      <div
        v-if="store.isLoading"
        class="text-xs text-slate-500 px-3 py-4 text-center"
      >
        {{ t('crfLibrary.ophthCatalog.loading') }}
      </div>
      <div
        v-else-if="store.error"
        class="rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800"
        role="alert"
      >
        {{ store.error }}
      </div>
      <div
        v-else-if="filtered.length === 0"
        class="text-xs text-slate-500 px-3 py-4 text-center"
      >
        {{ t('crfLibrary.ophthCatalog.empty') }}
      </div>
      <ul
        v-else
        class="divide-y divide-slate-200 border border-slate-200 rounded-md overflow-hidden max-h-[420px] overflow-y-auto"
        data-testid="ophth-catalog-list"
      >
        <li
          v-for="entry in filtered"
          :key="entry.code"
          class="flex items-center gap-3 px-3 py-2.5 hover:bg-slate-50 cursor-pointer"
          :data-testid="`ophth-catalog-entry-${entry.code}`"
          @click="onPick(entry)"
        >
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 text-sm">
              <span class="font-medium text-slate-900">{{ entry.labelDe }}</span>
              <span
                class="inline-flex items-center gap-1 text-[10px] uppercase tracking-wider font-semibold text-muw-blue bg-muw-blue-50 px-1.5 py-0.5 rounded"
              >
                {{ widgetLabel(entry.widget) }}
              </span>
              <span
                v-if="entry.bilateral"
                class="inline-flex items-center gap-1 text-[10px] uppercase tracking-wider font-semibold text-muw-teal-700 bg-muw-teal-50 px-1.5 py-0.5 rounded"
              >
                OD · OS
              </span>
              <span
                v-if="entry.modalityCode"
                class="inline-flex items-center gap-1 text-[10px] uppercase tracking-wider font-semibold text-muw-coral-700 bg-muw-coral-50 px-1.5 py-0.5 rounded"
                :title="t('crfLibrary.ophthCatalog.modalityHint', { code: entry.modalityCode })"
              >
                {{ entry.modalityCode }}
              </span>
            </div>
            <div class="text-[11px] text-slate-500 font-mono mt-0.5">{{ entry.code }}</div>
            <div v-if="entry.hintDe" class="text-[11px] text-slate-500 mt-0.5">{{ entry.hintDe }}</div>
          </div>
          <button
            type="button"
            class="text-xs text-muw-blue font-medium hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-muw-blue"
            :data-testid="`ophth-catalog-pick-${entry.code}`"
          >
            {{ t('crfLibrary.ophthCatalog.pick') }}
          </button>
        </li>
      </ul>
    </div>

    <template #footer>
      <div />
      <button
        type="button"
        class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
        data-testid="ophth-catalog-cancel"
        @click="cancel"
      >
        {{ t('common.cancel') }}
      </button>
    </template>
  </Modal>
</template>
