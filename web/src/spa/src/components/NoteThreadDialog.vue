<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import ThreadTimeline from '@/components/discrepancy/ThreadTimeline.vue'
import UserAutocomplete from '@/components/UserAutocomplete.vue'

import { useNotesStore } from '@/stores/notes'
import { useAuthStore } from '@/stores/auth'
import type { DiscrepancyNote, NoteStatus } from '@/types/note'
import { canRespondToNote, canResolveNote, canCloseNote } from '@/types/note'
import type { UserRole } from '@/types/auth'

/**
 * Phase E.6 DN — NoteThreadDialog.
 *
 * Hosts the full thread for one or more parent discrepancy notes
 * attached to a single CRF item. Wired up from {@link ItemNoteIndicator}
 * inside the CRF entry view: the indicator emits the parent note ids,
 * the dialog selects (or auto-selects when only one) the thread,
 * renders {@link ThreadTimeline}, and exposes the role-gated
 * Respond / Propose / Close composers as inline forms below the
 * timeline. Action submission delegates to
 * {@code notes.appendThread(parentId, …)} — same wire contract as the
 * inline composer on {@code NotesDiscrepanciesView}, so the backend
 * sees identical traffic regardless of entry point.
 */
interface Props {
  parentNoteIds: string[]
  subjectId: string
  itemOid: string
  itemLabel?: string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  close: []
  updated: [parentId: string]
}>()

const { t } = useI18n()
const notes = useNotesStore()
const auth = useAuthStore()

type ComposerMode = 'updated' | 'resolution-proposed' | 'closed'

const selectedId = ref<string>(props.parentNoteIds[0] ?? '')
const composerMode = ref<ComposerMode | null>(null)
const composerDescription = ref('')
const composerAssignedTo = ref<string>('')
const composerError = ref<string | null>(null)

const headingId = 'note-thread-dialog-heading'

const selectedParent = computed<DiscrepancyNote | undefined>(() =>
  notes.rows.find((n) => n.id === selectedId.value),
)

const role = computed<UserRole>(() => auth.user?.role ?? 'Investigator')

const status = computed<NoteStatus | null>(() => selectedParent.value?.status ?? null)

const canRespond = computed(() =>
  status.value ? canRespondToNote(role.value, status.value) : false,
)
const canResolve = computed(() =>
  status.value ? canResolveNote(role.value, status.value) : false,
)
const canClose = computed(() =>
  status.value ? canCloseNote(role.value, status.value) : false,
)

function ensureThreadLoaded(id: string) {
  if (!id) return
  if (notes.threadCache[id]) return
  if (notes.loadingThreadId === id) return
  notes.loadThread(id)
}

watch(
  selectedId,
  (next) => {
    composerMode.value = null
    composerDescription.value = ''
    composerAssignedTo.value = ''
    composerError.value = null
    ensureThreadLoaded(next)
  },
  { immediate: true },
)

function selectThread(id: string) {
  if (selectedId.value !== id) selectedId.value = id
}

function openComposer(mode: ComposerMode) {
  composerMode.value = mode
  composerDescription.value = ''
  composerAssignedTo.value = ''
  composerError.value = null
}

function cancelComposer() {
  composerMode.value = null
  composerDescription.value = ''
  composerAssignedTo.value = ''
  composerError.value = null
}

function previewFor(parentId: string): string {
  const row = notes.rows.find((n) => n.id === parentId)
  if (!row) return parentId
  const txt = row.description ?? ''
  if (txt.length <= 80) return txt
  return `${txt.slice(0, 77)}…`
}

function badgeFor(parentId: string): NoteStatus | null {
  return notes.rows.find((n) => n.id === parentId)?.status ?? null
}

async function submitComposer() {
  if (!composerMode.value || !selectedId.value) return
  const mode = composerMode.value
  const description = composerDescription.value.trim()

  if (mode !== 'closed' && description === '') {
    composerError.value = t('notes.composer.descriptionRequired')
    return
  }
  composerError.value = null

  const result = await notes.appendThread(selectedId.value, {
    newStatus: mode,
    description: description || undefined,
    assignedTo:
      mode === 'updated' && composerAssignedTo.value
        ? composerAssignedTo.value
        : null,
  })
  if (result) {
    emit('updated', selectedId.value)
    cancelComposer()
  } else {
    composerError.value = notes.error ?? t('notes.composer.unknownError')
  }
}

function statusBadgeClass(s: NoteStatus | null): string {
  switch (s) {
    case 'new':                 return 'bg-rose-100 text-rose-700'
    case 'updated':             return 'bg-amber-100 text-amber-700'
    case 'resolution-proposed': return 'bg-emerald-100 text-emerald-700'
    case 'closed':              return 'bg-slate-100 text-slate-600'
    case 'not-applicable':      return 'bg-slate-100 text-slate-500'
    default:                    return 'bg-slate-100 text-slate-500'
  }
}

function statusLabel(s: NoteStatus | null): string {
  return s ? t(`notes.status.${s}`) : ''
}
</script>

<template>
  <Modal
    :open="true"
    :labelled-by="headingId"
    panel-class="max-w-2xl"
    @close="emit('close')"
    @update:open="(v) => { if (!v) emit('close') }"
  >
    <template #header>
      <h2 :id="headingId" class="text-base font-semibold text-slate-900">
        {{ t('crfEntry.threadDialog.heading', { itemLabel: props.itemLabel ?? props.itemOid }) }}
      </h2>
      <p class="text-xs text-slate-500 mt-0.5">
        {{ t('crfEntry.threadDialog.subjectLine', { subject: props.subjectId, item: props.itemOid }) }}
      </p>
    </template>

    <div class="flex flex-col gap-4">
      <ul
        v-if="props.parentNoteIds.length > 1"
        class="flex flex-col gap-1 border border-slate-200 rounded-md p-1"
        data-testid="note-thread-selector"
      >
        <li v-for="pid in props.parentNoteIds" :key="pid">
          <button
            type="button"
            class="w-full text-left flex items-center gap-2 px-2 py-1.5 rounded text-xs"
            :class="selectedId === pid ? 'bg-muw-blue-50 text-muw-blue' : 'hover:bg-slate-50 text-slate-700'"
            :aria-pressed="selectedId === pid"
            :data-testid="`note-thread-pick-${pid}`"
            @click="selectThread(pid)"
          >
            <span
              v-if="badgeFor(pid)"
              class="px-1.5 py-0.5 rounded text-[10px] font-medium uppercase tracking-wide"
              :class="statusBadgeClass(badgeFor(pid))"
            >{{ statusLabel(badgeFor(pid)) }}</span>
            <span class="flex-1 truncate">{{ previewFor(pid) }}</span>
          </button>
        </li>
      </ul>

      <section data-testid="note-thread-body">
        <ThreadTimeline
          :entries="notes.threadCache[selectedId] ?? []"
          :is-loading="notes.loadingThreadId === selectedId"
        />
      </section>

      <p
        v-if="composerMode === null && notes.error"
        class="text-xs text-rose-700"
        role="alert"
      >{{ notes.error }}</p>

      <section
        v-if="composerMode"
        class="border-t border-slate-200 pt-3 flex flex-col gap-2"
        data-testid="note-thread-composer"
      >
        <label class="text-xs font-medium text-slate-600">
          {{ t(`notes.composer.label.${composerMode}`) }}
        </label>
        <textarea
          v-model="composerDescription"
          class="w-full text-sm border border-slate-300 rounded-md p-2"
          rows="3"
          :placeholder="composerMode === 'closed'
            ? t('notes.composer.placeholderClose')
            : t('notes.composer.placeholder')"
          data-testid="note-thread-composer-text"
        />
        <div v-if="composerMode === 'updated'" class="flex flex-col gap-1">
          <label class="text-xs text-slate-600">
            {{ t('crfEntry.threadDialog.reassignLabel') }}
          </label>
          <UserAutocomplete
            id="note-thread-assignee"
            v-model="composerAssignedTo"
            :placeholder="t('crfEntry.threadDialog.reassignPlaceholder')"
          />
        </div>
        <p v-if="composerError" class="text-xs text-rose-700" role="alert">{{ composerError }}</p>
        <div class="flex justify-end gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-300 rounded-md hover:bg-slate-100"
            :disabled="notes.isSubmitting"
            data-testid="note-thread-composer-cancel"
            @click="cancelComposer"
          >{{ t('common.cancel') }}</button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 disabled:opacity-50"
            :disabled="notes.isSubmitting"
            data-testid="note-thread-composer-submit"
            @click="submitComposer"
          >{{ notes.isSubmitting ? t('common.saving') : t('notes.composer.submit') }}</button>
        </div>
      </section>
    </div>

    <template #footer>
      <div class="flex items-center gap-2 text-xs text-slate-500">
        <span v-if="status">{{ t('notes.column.status') }}: <strong>{{ statusLabel(status) }}</strong></span>
      </div>
      <div class="flex items-center gap-2">
        <button
          v-if="canRespond"
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-300 rounded-md hover:bg-slate-50"
          :disabled="notes.isSubmitting"
          data-testid="note-thread-action-respond"
          @click="openComposer('updated')"
        >{{ t('crfEntry.threadDialog.respond') }}</button>
        <button
          v-if="canResolve"
          type="button"
          class="px-3 py-1.5 text-xs border border-emerald-300 text-emerald-700 rounded-md hover:bg-emerald-50"
          :disabled="notes.isSubmitting"
          data-testid="note-thread-action-propose"
          @click="openComposer('resolution-proposed')"
        >{{ t('crfEntry.threadDialog.propose') }}</button>
        <button
          v-if="canClose"
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-300 rounded-md hover:bg-slate-50"
          :disabled="notes.isSubmitting"
          data-testid="note-thread-action-close"
          @click="openComposer('closed')"
        >{{ t('crfEntry.threadDialog.close') }}</button>
      </div>
    </template>
  </Modal>
</template>
