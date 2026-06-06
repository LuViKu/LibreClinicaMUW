<script setup lang="ts">
/**
 * Phase E.6 discrepancy-full — thread timeline.
 *
 * Renders the parent + every child note in insertion order as a
 * compact vertical timeline. Drops into the expanded row of the
 * NotesDiscrepanciesView dense table.
 *
 * Props:
 * - entries: ThreadEntry[] — pre-sorted by createdAt (server-side).
 * - isLoading: boolean — show a placeholder while fetching.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { NoteStatus, ThreadEntry } from '@/types/note'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    entries: ThreadEntry[]
    isLoading?: boolean
  }>(),
  { isLoading: false },
)

const sorted = computed<ThreadEntry[]>(() => props.entries.slice())

function statusDotClass(s: NoteStatus): string {
  switch (s) {
    case 'new':                 return 'bg-rose-500'
    case 'updated':             return 'bg-amber-500'
    case 'resolution-proposed': return 'bg-emerald-500'
    case 'closed':              return 'bg-slate-400'
    case 'not-applicable':      return 'bg-slate-400'
  }
}

function formatDate(iso: string): string {
  if (!iso) return ''
  // Avoid Intl in tests — keep deterministic output.
  return iso.replace('T', ' ').replace(/\..*$/, '').replace('Z', ' UTC')
}
</script>

<template>
  <div class="border-l border-slate-200 pl-4 my-2 text-xs">
    <div v-if="isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</div>
    <div v-else-if="sorted.length === 0" class="text-slate-500">
      {{ t('notes.thread.empty') }}
    </div>
    <ol v-else class="space-y-3">
      <li
        v-for="entry in sorted"
        :key="entry.id"
        class="flex gap-3"
      >
        <span
          class="mt-1 w-2 h-2 rounded-full shrink-0"
          :class="statusDotClass(entry.status)"
          aria-hidden="true"
        />
        <div class="flex-1">
          <div class="flex items-center gap-2 text-[11px] text-slate-500">
            <span class="font-medium text-slate-700">
              {{ entry.author || t('notes.thread.unknownAuthor') }}
            </span>
            <span>·</span>
            <span>{{ t(`notes.status.${entry.status}`) }}</span>
            <span>·</span>
            <time :datetime="entry.createdAt">{{ formatDate(entry.createdAt) }}</time>
          </div>
          <p v-if="entry.description" class="mt-0.5 text-slate-700 whitespace-pre-line">
            {{ entry.description }}
          </p>
          <p v-else class="mt-0.5 text-slate-400 italic">
            {{ t('notes.thread.noDescription') }}
          </p>
        </div>
      </li>
    </ol>
  </div>
</template>
