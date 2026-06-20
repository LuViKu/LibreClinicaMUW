<script setup lang="ts">
/**
 * nAMD workspace — sticky header.
 *
 * Port of {@code Header()} from namd-app.jsx. Holds the LibreClinica MUW
 * brand mark, a small breadcrumb showing the study label + the workspace
 * name, and a user-affordance chip on the right. Sticky to the top, hides
 * on print via {@code no-print}.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { I } from '../icons'

interface Props {
  /** Study label rendered as the breadcrumb prefix. */
  studyLabel: string
  /** Authenticated user's display name (avatar initial derived). */
  userLabel?: string
}

const props = withDefaults(defineProps<Props>(), { userLabel: '' })
const { t } = useI18n()

const initial = computed(() => (props.userLabel?.trim()?.[0] ?? '?').toLowerCase())
</script>

<template>
  <header
    data-testid="namd-header"
    class="no-print h-14 border-b border-slate-200 bg-white flex items-center px-5 sticky top-0 z-40"
  >
    <a href="/LibreClinica/" class="flex items-center gap-2.5 mr-5 text-muw-blue">
      <span class="text-muw-blue" v-html="I.brand" />
      <span class="font-serif font-semibold text-muw-blue tracking-tight text-[17px]">
        LibreClinica<em
          class="not-italic font-medium text-muw-coral-700 text-[0.66em] uppercase tracking-[0.08em] ml-1.5 align-middle"
        >MUW</em>
      </span>
    </a>
    <nav class="flex items-center gap-2 text-sm" aria-label="breadcrumb">
      <span class="font-medium text-slate-500">{{ props.studyLabel }}</span>
      <span class="text-slate-300" v-html="I.chevron" />
      <span class="font-medium text-slate-900">
        {{ t('studyModules.namd.workspaceBreadcrumb') }}
      </span>
    </nav>
    <div v-if="props.userLabel" class="ml-auto flex items-center gap-2.5">
      <span
        class="w-7 h-7 rounded-full bg-muw-coral-100 text-muw-coral-700 inline-flex items-center justify-center text-[12px] font-semibold"
      >{{ initial }}</span>
      <span class="text-slate-700 font-medium text-sm">{{ props.userLabel }}</span>
      <span class="w-2 h-2 rounded-full bg-muw-teal" />
    </div>
  </header>
</template>
