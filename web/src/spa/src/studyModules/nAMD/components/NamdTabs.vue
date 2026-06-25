<script setup lang="ts">
/**
 * nAMD workspace — tab strip.
 *
 * Port of {@code Tabs({ tab, setTab })} from namd-app.jsx. Four tabs —
 * Übersicht / OCT-Viewer / Vergleich / Bericht — sticky under the header,
 * hidden on print.
 */
import { useI18n } from 'vue-i18n'
import { I } from '../icons'

export type NamdTabId = 'overview' | 'viewer' | 'compare' | 'report'

interface Props {
  modelValue: NamdTabId
}

defineProps<Props>()
const emit = defineEmits<{ 'update:modelValue': [tab: NamdTabId] }>()

const { t } = useI18n()

const TABS: { id: NamdTabId; labelKey: string; icon: string }[] = [
  { id: 'overview', labelKey: 'studyModules.namd.tab.overview', icon: I.chart },
  { id: 'viewer', labelKey: 'studyModules.namd.tab.viewer', icon: I.layers },
  { id: 'compare', labelKey: 'studyModules.namd.tab.compare', icon: I.compare },
  { id: 'report', labelKey: 'studyModules.namd.tab.report', icon: I.report },
]

function select(id: NamdTabId) {
  emit('update:modelValue', id)
}
</script>

<template>
  <div
    data-testid="namd-tabs"
    class="no-print bg-white border-b border-slate-200 sticky top-14 z-30"
  >
    <div class="max-w-[1240px] mx-auto px-6 flex items-center gap-1">
      <button
        v-for="tabDef in TABS"
        :key="tabDef.id"
        type="button"
        :data-testid="`namd-tab-${tabDef.id}`"
        :aria-current="modelValue === tabDef.id ? 'page' : undefined"
        class="relative flex items-center gap-2 px-4 py-3 text-[13.5px] font-medium transition"
        :class="
          modelValue === tabDef.id
            ? 'text-muw-blue'
            : 'text-slate-500 hover:text-slate-800'
        "
        @click="select(tabDef.id)"
      >
        <span
          :class="modelValue === tabDef.id ? 'text-muw-blue' : 'text-slate-400'"
          v-html="tabDef.icon"
        />
        {{ t(tabDef.labelKey) }}
        <span
          v-if="modelValue === tabDef.id"
          class="absolute left-3 right-3 -bottom-px h-0.5 bg-muw-blue rounded-full"
        />
      </button>
    </div>
  </div>
</template>
