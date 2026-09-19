<script setup lang="ts">
/**
 * P3.3 — a subject's arrivals that nobody has filed yet.
 *
 * <p>Replaces {@code ParkedScansList}, which listed retinal_inference_job rows
 * with status 'parked'. That status existed because the retinal pipeline kept
 * its own queue of scans whose patient was unknown; those scans now land in
 * the one inbox like every other arrival, so this reads from there.
 *
 * <p>It shows what the platform <em>thinks</em> belongs to this subject — the
 * candidate the resolver worked out at upload time — not what anybody has
 * confirmed. Filing is deliberately not done here: the inbox is where that
 * decision is made, with the preview and the visit picker beside it. This is a
 * pointer, so that somebody opening a subject can see there is something
 * waiting rather than discovering it only if they happen to check the inbox.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

import { listIngestInbox, type IngestItem } from '@/api/ingest'

interface Props {
  studySubjectId: number
  subjectLabel?: string
}
const props = withDefaults(defineProps<Props>(), { subjectLabel: '' })

const { t } = useI18n()

const items = ref<IngestItem[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

const hasItems = computed(() => items.value.length > 0)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const res = await listIngestInbox({
      status: 'UNBOUND',
      candidateStudySubjectId: props.studySubjectId,
    })
    items.value = res.items
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.studySubjectId, load)
</script>

<template>
  <section v-if="loading || error || hasItems" class="mt-4" data-testid="unbound-items">
    <h3 class="text-[13px] font-semibold text-slate-700">
      {{ t('subjectUnboundItems.title') }}
    </h3>

    <p v-if="error" class="mt-1 text-[12px] text-rose-700" data-testid="unbound-items-error">
      {{ error }}
    </p>
    <p v-else-if="loading" class="mt-1 text-[12px] text-slate-500">
      {{ t('subjectUnboundItems.loading') }}
    </p>

    <template v-else>
      <p class="mt-1 text-[12px] text-slate-500">
        {{ t('subjectUnboundItems.subtitle', { count: items.length }) }}
      </p>
      <ul class="mt-2 space-y-1.5">
        <li
          v-for="item in items"
          :key="item.id"
          class="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[12px] text-slate-600"
          data-testid="unbound-item-row"
        >
          <span class="font-medium text-slate-700">
            {{ t(`ingestInbox.kind.${item.kind}`) }}
          </span>
          <span v-if="item.laterality">· {{ item.laterality }}</span>
          <span v-if="item.acquisitionDate">· {{ item.acquisitionDate }}</span>
          <span v-if="item.device">· {{ item.device }}</span>
        </li>
      </ul>
      <RouterLink
        :to="{ name: 'ingest-inbox' }"
        class="mt-2 inline-block text-[12px] text-muw-blue hover:underline"
        data-testid="unbound-items-inbox-link"
      >
        {{ t('subjectUnboundItems.openInbox') }}
      </RouterLink>
    </template>
  </section>
</template>
