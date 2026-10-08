<script setup lang="ts">
/**
 * nAMD — placeholder-model warning banner.
 *
 * The inference sidecar's placeholder adapter emits fake, deterministic fluid
 * volumes (model_version "placeholder-…"). Those numbers must never be mistaken
 * for a real segmentation, so whenever ANY displayed visit comes from such a
 * model this banner is shown: non-dismissable (no close control, by design),
 * and rendered in the printed report too.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { isPlaceholderModel } from '../composables/useNamdAiRecommendation'
import type { NamdVisit } from '../types'

const props = defineProps<{ visits: NamdVisit[] }>()
const { t } = useI18n()

const labels = computed(() =>
  props.visits.filter((v) => isPlaceholderModel(v.modelVersion)).map((v) => v.label),
)
</script>

<template>
  <div
    v-if="labels.length > 0"
    data-testid="namd-placeholder-banner"
    role="alert"
    class="rounded-md border-2 border-rose-400 bg-rose-50 text-rose-800 px-4 py-3 text-[13px] font-semibold"
  >
    {{ t('studyModules.namd.placeholder.banner', { visits: labels.join(', ') }) }}
  </div>
</template>
