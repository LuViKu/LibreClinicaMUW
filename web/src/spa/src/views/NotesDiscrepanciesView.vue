<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import ThreadTimeline from '@/components/discrepancy/ThreadTimeline.vue'

import { useNotesStore } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import type { DiscrepancyNote, NoteStatus, NoteType } from '@/types/note'
import { canRespondToNote, canResolveNote, canCloseNote } from '@/types/note'

const { t } = useI18n()
const notes = useNotesStore()
const auth = useAuthStore()

onMounted(() => { if (notes.rows.length === 0) notes.load() })

/* ---------------------------------------------------------------- */
/* Phase E A1 — thread-entry composer.                              */
/*                                                                  */
/* Inline (not a modal) so the table flow stays put. When the user  */
/* clicks Respond / Mark resolved / Close, we open a row-level      */
/* composer that collects the description (Close may submit empty)  */
/* and calls notes.appendThread(parentId, { newStatus, … }).        */
/* ---------------------------------------------------------------- */

interface ComposerState {
  noteId: string
  intendedStatus: 'updated' | 'resolution-proposed' | 'closed'
  description: string
  /* Inline error from the last failed appendThread attempt — distinct
     from notes.error (which is the store-wide banner). */
  error: string | null
}

const composer = ref<ComposerState | null>(null)

function openComposer(n: DiscrepancyNote, intendedStatus: ComposerState['intendedStatus']) {
  composer.value = { noteId: n.id, intendedStatus, description: '', error: null }
}

function cancelComposer() {
  composer.value = null
}

async function submitComposer() {
  if (!composer.value) return
  const c = composer.value
  // Close may be wordless; every other transition needs a comment.
  if (c.intendedStatus !== 'closed' && c.description.trim() === '') {
    c.error = t('notes.composer.descriptionRequired')
    return
  }
  c.error = null
  const result = await notes.appendThread(c.noteId, {
    newStatus: c.intendedStatus,
    description: c.description.trim() || undefined,
  })
  if (result) {
    composer.value = null
  } else {
    // Surface the store-wide error in the composer so it's visible
    // next to the input the user is typing in.
    c.error = notes.error ?? t('notes.composer.unknownError')
  }
}

/** Bridge for the role enum — auth.user is null while bootstrap is in flight. */
function currentRole(): import('@/types/auth').UserRole | null {
  return auth.user?.role ?? null
}

function typeVariant(t: NoteType): 'danger' | 'warning' | 'neutral' | 'data-manager' {
  switch (t) {
    case 'query':             return 'danger'
    case 'failed-validation': return 'warning'
    case 'annotation':        return 'neutral'
    case 'reason-for-change': return 'data-manager'
  }
}

function statusVariant(s: NoteStatus): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (s) {
    case 'new':                 return 'danger'
    case 'updated':             return 'warning'
    case 'resolution-proposed': return 'success'
    case 'closed':              return 'neutral'
    case 'not-applicable':      return 'neutral'
  }
}

const typeOptions: { v: 'all' | NoteType; l: () => string }[] = [
  { v: 'all',                l: () => t('notes.type.all') },
  { v: 'query',              l: () => t('notes.type.query') },
  { v: 'failed-validation',  l: () => t('notes.type.failed-validation') },
  { v: 'annotation',         l: () => t('notes.type.annotation') },
  { v: 'reason-for-change',  l: () => t('notes.type.reason-for-change') },
]

const statusOptions: { v: 'all' | 'open' | NoteStatus; l: () => string }[] = [
  { v: 'open',                  l: () => t('notes.status.openOnly') },
  { v: 'all',                   l: () => t('notes.status.all') },
  { v: 'new',                   l: () => t('notes.status.new') },
  { v: 'updated',               l: () => t('notes.status.updated') },
  { v: 'resolution-proposed',   l: () => t('notes.status.resolution-proposed') },
  { v: 'closed',                l: () => t('notes.status.closed') },
  { v: 'not-applicable',        l: () => t('notes.status.not-applicable') },
]

const summaryCards: { type: NoteType; tone: 'danger' | 'warning' | 'neutral' | 'data-manager' }[] = [
  { type: 'query',             tone: 'danger' },
  { type: 'failed-validation', tone: 'warning' },
  { type: 'annotation',        tone: 'neutral' },
  { type: 'reason-for-change', tone: 'data-manager' },
]

function cardToneClass(tone: 'danger' | 'warning' | 'neutral' | 'data-manager') {
  switch (tone) {
    case 'danger':       return 'border-rose-200 bg-rose-50 text-rose-700'
    case 'warning':      return 'border-amber-200 bg-amber-50 text-amber-700'
    case 'data-manager': return 'border-muw-coral-200 bg-muw-coral-50 text-muw-coral-700'
    case 'neutral':      return 'border-slate-200 bg-slate-50 text-slate-700'
  }
}

function cardDotClass(tone: 'danger' | 'warning' | 'neutral' | 'data-manager') {
  switch (tone) {
    case 'danger':       return 'bg-rose-500'
    case 'warning':      return 'bg-amber-500'
    case 'data-manager': return 'bg-muw-coral-500'
    case 'neutral':      return 'bg-slate-400'
  }
}

/* ---------------------------------------------------------------- */
/* Phase E.6 discrepancy-full — row expand + CSV export.            */
/* ---------------------------------------------------------------- */

const expandedRowId = ref<string | null>(null)

async function toggleExpand(n: DiscrepancyNote): Promise<void> {
  if (expandedRowId.value === n.id) {
    expandedRowId.value = null
    return
  }
  expandedRowId.value = n.id
  // Lazy-load the thread on first expand; cached on subsequent expands.
  if (!notes.threadCache[n.id]) {
    await notes.loadThread(n.id)
  }
}

function exportHref(): string {
  return notes.buildExportUrl()
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/sdv" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
          <polyline points="22 4 12 14.01 9 11.01" />
        </svg>
        {{ t('nav.sdv') }}
      </RouterLink>
      <RouterLink to="/notes" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M4 15a4 4 0 0 0 4 4h12V5a2 2 0 0 0-2-2H8a4 4 0 0 0-4 4z" />
        </svg>
        {{ t('nav.notes') }}
        <StatusPill compact variant="warning" class="ml-auto">{{ notes.openCount }}</StatusPill>
      </RouterLink>
      <RouterLink to="/audit-log" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        </svg>
        {{ t('nav.auditLog') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 px-8 py-6">
      <div class="mb-5">
        <div class="text-xs text-slate-500 mb-1">{{ t('notes.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('notes.title') }}</h1>
      </div>

      <!-- Summary cards -->
      <section class="mb-5 grid grid-cols-5 gap-2">
        <div class="rounded-muw border border-slate-200 p-3 bg-white">
          <div class="text-[10px] uppercase tracking-wider text-slate-400 font-semibold">{{ t('notes.summary.openTotal') }}</div>
          <div class="mt-1 text-2xl font-semibold text-slate-900">{{ notes.openCount }}</div>
          <div class="text-xs text-slate-500">{{ t('notes.summary.openTotalSub', { total: notes.totalCount }) }}</div>
        </div>
        <div
          v-for="card in summaryCards"
          :key="card.type"
          class="rounded-muw border p-3"
          :class="cardToneClass(card.tone)"
        >
          <div class="text-[10px] uppercase tracking-wider font-semibold flex items-center gap-1">
            <span class="w-1.5 h-1.5 rounded-full" :class="cardDotClass(card.tone)" aria-hidden="true"></span>
            {{ t(`notes.type.${card.type}`) }}
          </div>
          <div class="mt-1 text-2xl font-semibold">{{ notes.openTypeTotals[card.type] }}</div>
          <div class="text-xs">{{ t('notes.summary.openSub') }}</div>
        </div>
      </section>

      <!-- Filter row -->
      <div class="flex flex-wrap items-center gap-3 mb-4 text-xs">
        <div class="w-72">
          <TextInput
            id="notes-search"
            v-model="notes.query"
            type="search"
            inputmode="search"
            :placeholder="t('notes.searchPlaceholder')"
          >
            <template #prefix-icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.3-4.3" />
              </svg>
            </template>
          </TextInput>
        </div>

        <div class="w-44">
          <SelectInput id="notes-status-filter" :model-value="notes.statusFilter" @update:model-value="(v) => notes.statusFilter = v as 'all' | 'open' | NoteStatus">
            <option v-for="o in statusOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
          </SelectInput>
        </div>

        <div class="w-44">
          <SelectInput id="notes-type-filter" :model-value="notes.typeFilter" @update:model-value="(v) => notes.typeFilter = v as 'all' | NoteType">
            <option v-for="o in typeOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
          </SelectInput>
        </div>

        <label class="inline-flex items-center gap-1.5 text-slate-600 cursor-pointer">
          <input v-model="notes.onlyAssignedToMe" type="checkbox" class="rounded text-muw-blue" />
          {{ t('notes.filter.assignedToMe') }}
        </label>

        <button
          v-if="notes.query || notes.statusFilter !== 'open' || notes.typeFilter !== 'all' || notes.onlyAssignedToMe"
          type="button"
          class="text-slate-500 hover:text-slate-900"
          @click="notes.clearFilters()"
        >
          {{ t('common.clear') }}
        </button>

        <div class="ml-auto flex items-center gap-3 text-slate-500">
          <a
            :href="exportHref()"
            class="px-3 py-1.5 text-xs border border-slate-300 rounded-md hover:bg-slate-100 text-slate-700 inline-flex items-center gap-1.5"
            :download="true"
            data-testid="notes-export-csv"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            {{ t('notes.actions.export') }}
          </a>
          <span>
            {{ t('notes.showingCount', { visible: notes.visibleCount, total: notes.totalCount }) }}
          </span>
        </div>
      </div>

      <DenseTable>
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('notes.column.type') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('notes.column.status') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('notes.column.subject') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-36">{{ t('notes.column.item') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('notes.column.description') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-24">{{ t('notes.column.assigned') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('notes.column.daysOpen') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-44">{{ t('notes.column.actions') }}</th>
          </tr>
        </template>

        <tr v-if="notes.isLoading">
          <td :colspan="8" class="px-3 py-6 text-center text-slate-500 italic">{{ t('common.loading') }}</td>
        </tr>
        <tr v-else-if="notes.visibleCount === 0">
          <td :colspan="8" class="px-3 py-6 text-center text-slate-500">{{ t('notes.empty') }}</td>
        </tr>

        <template v-for="n in notes.filtered" :key="n.id">
          <tr>
            <td class="px-3 py-2"><StatusPill :variant="typeVariant(n.type)">{{ t(`notes.type.${n.type}`) }}</StatusPill></td>
            <td class="px-3 py-2"><StatusPill :variant="statusVariant(n.status)">{{ t(`notes.status.${n.status}`) }}</StatusPill></td>
            <td class="px-3 py-2 font-medium">{{ n.subjectId }}</td>
            <td class="px-3 py-2 font-mono text-xs text-slate-600">{{ n.itemOid }}</td>
            <td class="px-3 py-2 text-slate-700">{{ n.description }}</td>
            <td class="px-3 py-2 text-slate-600 text-xs">{{ n.assignedTo ?? '—' }}</td>
            <td class="px-3 py-2 text-slate-700 text-right">{{ n.daysOpen || '—' }}</td>
            <td class="px-3 py-2 text-xs">
              <button
                type="button"
                class="text-slate-500 hover:text-slate-900 inline-flex items-center"
                :aria-expanded="expandedRowId === n.id"
                :aria-label="expandedRowId === n.id
                  ? t('notes.thread.collapse')
                  : t('notes.thread.expand')"
                data-testid="notes-thread-toggle"
                @click="toggleExpand(n)"
              >
                <svg
                  width="14" height="14" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" stroke-width="1.75" aria-hidden="true"
                  :class="expandedRowId === n.id ? 'rotate-90 transition-transform' : 'transition-transform'"
                >
                  <polyline points="9 6 15 12 9 18" />
                </svg>
              </button>
              <span v-if="currentRole() && canRespondToNote(currentRole()!, n.status)" class="ml-2">
                <button
                  type="button"
                  class="text-muw-blue underline hover:text-muw-blue-700"
                  :disabled="notes.isSubmitting"
                  @click="openComposer(n, 'updated')"
                >{{ t('notes.actions.respond') }}</button>
              </span>
              <span v-if="currentRole() && canResolveNote(currentRole()!, n.status)" class="ml-2">
                <button
                  type="button"
                  class="text-muw-teal-700 underline hover:text-muw-teal-900"
                  :disabled="notes.isSubmitting"
                  @click="openComposer(n, 'resolution-proposed')"
                >{{ t('notes.actions.resolve') }}</button>
              </span>
              <span v-if="currentRole() && canCloseNote(currentRole()!, n.status)" class="ml-2">
                <button
                  type="button"
                  class="text-slate-700 underline hover:text-slate-900"
                  :disabled="notes.isSubmitting"
                  @click="openComposer(n, 'closed')"
                >{{ t('notes.actions.close') }}</button>
              </span>
            </td>
          </tr>
          <tr v-if="expandedRowId === n.id" class="bg-slate-25">
            <td :colspan="8" class="px-3 py-1">
              <ThreadTimeline
                :entries="notes.threadCache[n.id] ?? []"
                :is-loading="notes.loadingThreadId === n.id"
              />
            </td>
          </tr>
          <tr v-if="composer && composer.noteId === n.id" class="bg-slate-50">
            <td :colspan="8" class="px-3 py-3">
              <div class="flex flex-col gap-2">
                <label class="text-xs text-slate-600">
                  {{ t(`notes.composer.label.${composer.intendedStatus}`) }}
                </label>
                <textarea
                  v-model="composer.description"
                  class="w-full text-sm border border-slate-300 rounded-md p-2"
                  :placeholder="composer.intendedStatus === 'closed'
                    ? t('notes.composer.placeholderClose')
                    : t('notes.composer.placeholder')"
                  rows="3"
                />
                <p v-if="composer.error" class="text-xs text-red-600">{{ composer.error }}</p>
                <div class="flex justify-end gap-2">
                  <button
                    type="button"
                    class="px-3 py-1.5 text-xs border border-slate-300 rounded-md hover:bg-slate-100"
                    :disabled="notes.isSubmitting"
                    @click="cancelComposer"
                  >{{ t('common.cancel') }}</button>
                  <button
                    type="button"
                    class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:opacity-50"
                    :disabled="notes.isSubmitting"
                    @click="submitComposer"
                  >{{ notes.isSubmitting ? t('common.saving') : t('notes.composer.submit') }}</button>
                </div>
              </div>
            </td>
          </tr>
        </template>

        <template #statusBar>
          <span>{{ t('notes.showingCount', { visible: notes.visibleCount, total: notes.totalCount }) }}</span>
        </template>
      </DenseTable>
    </main>
  </div>
</template>
