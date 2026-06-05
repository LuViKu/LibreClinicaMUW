<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { FLAG_SECTIONS, type InclusionFlagKey, type InclusionFlags } from '@/types/export'

/**
 * Phase E.6 — Data Export Phase 2 — Inclusion-flags step.
 *
 * Renders the 18 wizard-visible dataset booleans as 5 collapsible
 * sections (Subject metadata, Event metadata, CRF / status,
 * Interviewer, Notes). Each section is collapsible so the user can
 * focus on one slice at a time. The "expand all" / "collapse all"
 * shortcuts at the top are mouse-friendly for fast review.
 *
 * v-model'd against the full {@link InclusionFlags} so the wizard's
 * draft stays the single source of truth.
 */

interface Props {
  modelValue: InclusionFlags
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: InclusionFlags]
}>()

const { t } = useI18n()

const collapsed = ref<Set<string>>(new Set())

const isCollapsed = (id: string) => collapsed.value.has(id)
const toggle = (id: string) => {
  const next = new Set(collapsed.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  collapsed.value = next
}

const expandAll = () => {
  collapsed.value = new Set()
}
const collapseAll = () => {
  collapsed.value = new Set(FLAG_SECTIONS.map((s) => s.id))
}

function setFlag(key: InclusionFlagKey, value: boolean) {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}

const checkedCounts = computed(() =>
  Object.fromEntries(
    FLAG_SECTIONS.map((s) => [s.id, s.keys.filter((k) => props.modelValue[k]).length] as const),
  ),
)
</script>

<template>
  <div class="space-y-4">
    <header class="flex items-center justify-between gap-3">
      <p class="text-sm text-slate-600">
        {{ t('createDataset.flagsBlurb') }}
      </p>
      <div class="flex items-center gap-3 text-xs">
        <button type="button" class="text-muw-blue hover:underline" @click="expandAll">
          {{ t('createDataset.expandAll') }}
        </button>
        <span class="text-slate-300" aria-hidden="true">|</span>
        <button type="button" class="text-muw-blue hover:underline" @click="collapseAll">
          {{ t('createDataset.collapseAll') }}
        </button>
      </div>
    </header>

    <section
      v-for="section in FLAG_SECTIONS"
      :key="section.id"
      class="border border-slate-200 rounded-md"
    >
      <button
        type="button"
        class="w-full px-3 py-2 flex items-center justify-between gap-3 text-left bg-slate-50 hover:bg-slate-100 rounded-t-md"
        :aria-expanded="!isCollapsed(section.id)"
        @click="toggle(section.id)"
      >
        <span class="flex items-center gap-2">
          <span class="text-slate-400" aria-hidden="true">{{ isCollapsed(section.id) ? '▸' : '▾' }}</span>
          <span class="text-sm font-semibold text-slate-800">
            {{ t(`createDataset.flagSection.${section.id}.title`) }}
          </span>
        </span>
        <span class="text-xs text-slate-500">
          {{ checkedCounts[section.id] }} / {{ section.keys.length }} {{ t('createDataset.included') }}
        </span>
      </button>

      <div v-if="!isCollapsed(section.id)" class="px-3 py-3 grid grid-cols-1 sm:grid-cols-2 gap-x-4 gap-y-2 bg-white rounded-b-md">
        <label
          v-for="key in section.keys"
          :key="key"
          class="flex items-start gap-2 text-sm cursor-pointer"
        >
          <input
            type="checkbox"
            class="mt-0.5 h-4 w-4 rounded border-slate-300 text-muw-blue focus:ring-muw-blue"
            :checked="modelValue[key]"
            :aria-label="t(`createDataset.flag.${key}.label`)"
            @change="setFlag(key, ($event.target as HTMLInputElement).checked)"
          />
          <span class="leading-5">
            <span class="block text-slate-800">{{ t(`createDataset.flag.${key}.label`) }}</span>
            <span class="block text-xs text-slate-500">{{ t(`createDataset.flag.${key}.hint`) }}</span>
          </span>
        </label>
      </div>
    </section>
  </div>
</template>
