<script setup lang="ts">
/**
 * Phase E.7 Wave 4 — Artifact download list.
 *
 * Two-section table:
 *   - "Per-scan" companions ({@code bscan.dcm}, {@code fundus.png},
 *     {@code geometry.json}) that the preprocess sidecar emitted once
 *     for the underlying e2e volume.
 *   - "Segmentation" artifacts produced by the task-specific runner
 *     ({@code *.npz}, {@code *.csv} surfaces, etc.).
 *
 * <p>The viewer renders this section at the bottom of the metrics
 * page; the operator can grab raw files for downstream analysis
 * without having to find the host or the docker volume.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { artifactUrl } from '@/api/retinal'

const { t } = useI18n()

interface Props {
  jobId: number
  artifactNames: string[]
  companionNames: string[]
}

const props = defineProps<Props>()

interface ArtifactRow {
  name: string
  mediaTypeLabel: string
  href: string
}

/**
 * Pretty media-type pill — mirrors the controller's
 * {@code mediaTypeForName} switch (kept in sync manually because the
 * controller-side is a one-way export of MIME types).
 */
function mediaTypeLabel(name: string): string {
  const lower = name.toLowerCase()
  if (lower.endsWith('.csv')) return 'CSV'
  if (lower.endsWith('.npy')) return 'NPY'
  if (lower.endsWith('.npz')) return 'NPZ'
  if (lower.endsWith('.dcm')) return 'DICOM'
  if (lower.endsWith('.png')) return 'PNG'
  if (lower.endsWith('.json')) return 'JSON'
  return 'BIN'
}

function toRows(names: string[]): ArtifactRow[] {
  return names.map((name) => ({
    name,
    mediaTypeLabel: mediaTypeLabel(name),
    href: artifactUrl(props.jobId, name),
  }))
}

const companions = computed<ArtifactRow[]>(() => toRows(props.companionNames))
const artifacts = computed<ArtifactRow[]>(() => toRows(props.artifactNames))
</script>

<template>
  <section
    class="bg-white border border-slate-200 rounded-muw overflow-clip"
    data-testid="retinal-artifact-list"
  >
    <div class="px-5 py-3 border-b border-slate-200">
      <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500">
        {{ t('retinal.downloads.header') }}
      </h2>
    </div>

    <div v-if="companions.length === 0 && artifacts.length === 0" class="px-5 py-6 text-xs text-slate-500 italic">
      {{ t('retinal.downloads.empty') }}
    </div>

    <table v-else class="w-full text-[13px]">
      <tbody class="divide-y divide-slate-100">
        <tr v-if="companions.length" class="bg-slate-50">
          <td colspan="3" class="px-5 py-2 text-[10px] uppercase tracking-wider font-semibold text-slate-500">
            {{ t('retinal.downloads.sectionPerScan') }}
          </td>
        </tr>
        <tr v-for="row in companions" :key="`companion-${row.name}`" data-testid="artifact-row">
          <td class="px-5 py-2 font-medium text-slate-700 font-mono text-xs">{{ row.name }}</td>
          <td class="px-5 py-2 w-24">
            <span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] bg-slate-100 text-slate-600 font-medium">
              {{ row.mediaTypeLabel }}
            </span>
          </td>
          <td class="px-5 py-2 w-28 text-right">
            <a
              :href="row.href"
              class="text-muw-blue hover:underline text-xs"
              download
              data-testid="artifact-download"
            >{{ t('retinal.downloads.action') }}</a>
          </td>
        </tr>

        <tr v-if="artifacts.length" class="bg-slate-50">
          <td colspan="3" class="px-5 py-2 text-[10px] uppercase tracking-wider font-semibold text-slate-500">
            {{ t('retinal.downloads.sectionSegmentation') }}
          </td>
        </tr>
        <tr v-for="row in artifacts" :key="`artifact-${row.name}`" data-testid="artifact-row">
          <td class="px-5 py-2 font-medium text-slate-700 font-mono text-xs">{{ row.name }}</td>
          <td class="px-5 py-2 w-24">
            <span class="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] bg-slate-100 text-slate-600 font-medium">
              {{ row.mediaTypeLabel }}
            </span>
          </td>
          <td class="px-5 py-2 w-28 text-right">
            <a
              :href="row.href"
              class="text-muw-blue hover:underline text-xs"
              download
              data-testid="artifact-download"
            >{{ t('retinal.downloads.action') }}</a>
          </td>
        </tr>
      </tbody>
    </table>
  </section>
</template>
