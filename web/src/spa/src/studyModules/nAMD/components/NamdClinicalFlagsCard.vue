<script setup lang="ts">
/**
 * 2026-07-06 — nAMD clinical-flags entry card.
 *
 * <p>Surfaces two per-eye booleans as toggles for the currently-viewed
 * visit + eye:
 *
 * <ul>
 *   <li>{@code NAMD_<eye>_NEW_HEMORRHAGE} — new retinal hemorrhage
 *     observed on the current OCT. SHORTEN trigger for the rec engine.</li>
 *   <li>{@code NAMD_<eye>_BCVA_LOSS_NAMD_ATTRIBUTED} — visual-acuity
 *     loss (≥5 letters vs prev visit) clinically attributable to nAMD.
 *     Cross-checks the SPA's BCVA-loss detection.</li>
 * </ul>
 *
 * <p>The read timeline endpoint
 * ({@code GET /study-subjects/{id}/namd-clinical-flags}) already ships;
 * this card writes via the existing per-item CRF POST
 * ({@code POST /pages/api/v1/eventCrfs/{eventCrfId}/items}) — no new
 * backend endpoint. On success emits {@code saved} so the workspace
 * can refetch the timeline + let the rec engine recompute.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Card from './primitives/Card.vue'
import { apiPost } from '@/api/client'

interface Props {
  /** event_crf_id for the NAMD_VISIT CRF at this visit. Save disabled if null. */
  eventCrfId: number | null
  /** Currently-viewed eye — determines which per-eye item names to write. */
  eye: 'OD' | 'OS'
  /** Current in-memory flag values (from useNamdVisitData → props.data.current). */
  hemorrhage: boolean
  bcvaAttributableToNamd: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (e: 'saved', payload: { hemorrhage: boolean; bcvaAttributableToNamd: boolean }): void
}>()

const { t } = useI18n()

const localHemorrhage = ref<boolean>(props.hemorrhage)
const localBcvaAttr = ref<boolean>(props.bcvaAttributableToNamd)
const saving = ref<boolean>(false)
const savedRecently = ref<boolean>(false)
const saveError = ref<string | null>(null)

// Re-seed local state whenever the parent's snapshot changes (visit
// switch, refresh after save). Without this the toggles would freeze
// on the first-mount value.
watch(
  () => [props.hemorrhage, props.bcvaAttributableToNamd, props.eye, props.eventCrfId] as const,
  ([nextHem, nextBcva]) => {
    localHemorrhage.value = nextHem
    localBcvaAttr.value = nextBcva
    savedRecently.value = false
    saveError.value = null
  },
)

const dirty = computed(
  () => localHemorrhage.value !== props.hemorrhage
     || localBcvaAttr.value !== props.bcvaAttributableToNamd,
)

// 2026-07-06 — treat 0 as "no CRF" the same as null; the backend
// composable resolves a missing event_crf row to 0 (Integer default),
// and POST /eventCrfs/0/items 500s with "No event_crf with id 0".
const hasCrfRow = computed(
  () => props.eventCrfId != null && props.eventCrfId > 0,
)
const canSave = computed(
  () => hasCrfRow.value && dirty.value && !saving.value,
)

/**
 * Fires the item-data upsert against the legacy CRF write endpoint.
 * Same payload shape {@link NamdDecisionPanel} uses — `{ values: {
 * I_<OID>: "true"|"false" } }`. Booleans encode as the strings
 * `"true"` / `"false"` (the CRF-render layer stores them as-is;
 * the read endpoint accepts either literal, cf. its truthy check).
 */
async function save() {
  if (!canSave.value || props.eventCrfId == null) return
  saving.value = true
  saveError.value = null
  const hemKey = `I_NAMD_${props.eye}_NEW_HEMORRHAGE`
  const bcvaKey = `I_NAMD_${props.eye}_BCVA_LOSS_NAMD_ATTRIBUTED`
  const values: Record<string, string> = {
    [hemKey]: String(localHemorrhage.value),
    [bcvaKey]: String(localBcvaAttr.value),
  }
  try {
    // apiPost auto-prefixes the `/LibreClinica` context path. Raw
    // `fetch('/pages/...')` misses the WAR prefix and 404s under both
    // vite (which proxies /LibreClinica/*) and prod Tomcat.
    await apiPost<void>(`/pages/api/v1/eventCrfs/${props.eventCrfId}/items`, { values })
    savedRecently.value = true
    emit('saved', {
      hemorrhage: localHemorrhage.value,
      bcvaAttributableToNamd: localBcvaAttr.value,
    })
  } catch (e) {
    saveError.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Card :title="t('studyModules.namd.clinicalFlags.title')" data-testid="namd-clinical-flags-card">
    <template #right>
      <span class="text-[11px] font-mono px-1.5 py-0.5 rounded bg-muw-blue-50 text-muw-blue border border-muw-blue-100">
        {{ eye }}
      </span>
    </template>

    <div v-if="!hasCrfRow" class="text-[12px] text-slate-500 italic">
      {{ t('studyModules.namd.clinicalFlags.unavailable') }}
    </div>

    <div v-else class="space-y-3">
      <label class="flex items-start gap-2.5 cursor-pointer">
        <input
          v-model="localHemorrhage"
          type="checkbox"
          class="mt-0.5 h-4 w-4 rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
          data-testid="namd-clinical-flag-hemorrhage"
        />
        <span class="text-[13px] text-slate-700">
          <span class="font-medium">{{ t('studyModules.namd.clinicalFlags.hemorrhage') }}</span>
          <span class="block text-[11px] text-slate-500 mt-0.5">
            {{ t('studyModules.namd.clinicalFlags.hemorrhageHint') }}
          </span>
        </span>
      </label>

      <label class="flex items-start gap-2.5 cursor-pointer">
        <input
          v-model="localBcvaAttr"
          type="checkbox"
          class="mt-0.5 h-4 w-4 rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
          data-testid="namd-clinical-flag-bcva-attr"
        />
        <span class="text-[13px] text-slate-700">
          <span class="font-medium">{{ t('studyModules.namd.clinicalFlags.bcvaAttr') }}</span>
          <span class="block text-[11px] text-slate-500 mt-0.5">
            {{ t('studyModules.namd.clinicalFlags.bcvaAttrHint') }}
          </span>
        </span>
      </label>

      <div class="flex items-center justify-between gap-3 pt-2 border-t border-slate-100">
        <span
          v-if="saveError"
          class="text-[11px] text-rose-700"
          data-testid="namd-clinical-flags-error"
        >
          {{ saveError }}
        </span>
        <span
          v-else-if="savedRecently && !dirty"
          class="text-[11px] text-emerald-700"
          data-testid="namd-clinical-flags-saved"
        >
          {{ t('studyModules.namd.clinicalFlags.saved') }}
        </span>
        <span v-else class="text-[11px] text-slate-400">
          <template v-if="dirty">{{ t('studyModules.namd.clinicalFlags.dirty') }}</template>
        </span>

        <button
          type="button"
          class="rounded-md bg-muw-blue text-white text-[12px] font-medium px-3 py-1.5 hover:bg-muw-blue/90 disabled:opacity-40 disabled:cursor-not-allowed"
          :disabled="!canSave"
          data-testid="namd-clinical-flags-save"
          @click="save"
        >
          {{ saving ? t('common.saving') : t('common.save') }}
        </button>
      </div>
    </div>
  </Card>
</template>
