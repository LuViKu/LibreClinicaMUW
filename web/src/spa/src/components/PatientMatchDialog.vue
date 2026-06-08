<script setup lang="ts">
/**
 * Phase E.6 retrospective-backfill — patient match-preflight dialog.
 *
 * <p>Surfaces when {@link useSubjectsStore.preflightMatch} returns one
 * or more existing subjects matching the (firstName, lastName, DoB)
 * triplet the operator typed on the AddSubject form. The dialog gives
 * two safe paths:
 *
 * <ol>
 *   <li><strong>Link to existing:</strong> navigate to the existing
 *       subject's detail view (study switch happens lazily via the
 *       host route). Used when this is the same human re-enrolling
 *       into another study.</li>
 *   <li><strong>Create new anyway:</strong> the operator confirms it
 *       is a different human despite the triplet match. Echoes the
 *       seen subject_id(s) back to the create POST as
 *       {@code acknowledgeMatchSubjectId} so the backend can audit
 *       the override.</li>
 * </ol>
 *
 * <p>The dialog defaults to NO action when dismissed via Escape or
 * backdrop click — the host form stays gated on an explicit operator
 * choice so an accidental dismiss doesn't slip an unacknowledged
 * duplicate through.
 */
import { onBeforeUnmount, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import type { SubjectMatchCandidate } from '@/stores/subjects'

interface Props {
  open: boolean
  candidates: SubjectMatchCandidate[]
}

const props = defineProps<Props>()

const emit = defineEmits<{
  /** Operator wants to navigate to the existing subject. */
  link: [candidate: SubjectMatchCandidate]
  /** Operator confirms create-new despite match. */
  createNew: []
  /** Operator dismissed without a decision. */
  cancel: []
}>()

const { t } = useI18n()

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) {
    e.preventDefault()
    emit('cancel')
  }
}

watch(
  () => props.open,
  (isOpen, wasOpen) => {
    if (typeof document === 'undefined') return
    if (isOpen && !wasOpen) {
      document.addEventListener('keydown', onKeydown)
    } else if (!isOpen && wasOpen) {
      document.removeEventListener('keydown', onKeydown)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (typeof document === 'undefined') return
  document.removeEventListener('keydown', onKeydown)
})

function studiesLabel(c: SubjectMatchCandidate): string {
  const visible = c.studyOids.length
  const hidden = c.otherStudyCount
  if (visible === 0 && hidden === 0) return t('addSubject.matchDialog.noStudies')
  if (visible > 0 && hidden === 0)
    return t('addSubject.matchDialog.studiesVisible', { list: c.studyOids.join(', ') })
  if (visible === 0 && hidden > 0)
    return t('addSubject.matchDialog.studiesHiddenOnly', { n: hidden })
  return t('addSubject.matchDialog.studiesMixed', {
    list: c.studyOids.join(', '),
    n: hidden,
  })
}
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-30 flex items-center justify-center bg-slate-900/30"
    role="dialog"
    aria-modal="true"
    aria-labelledby="patient-match-dialog-title"
    data-testid="patient-match-dialog"
    @click.self="emit('cancel')"
  >
    <div
      class="bg-white rounded-muw shadow-xl border border-slate-200 max-w-lg w-full p-5"
      @click.stop
    >
      <h2
        id="patient-match-dialog-title"
        class="text-sm font-semibold mb-2"
        data-testid="patient-match-dialog-title"
      >
        {{ t('addSubject.matchDialog.title', { n: candidates.length }) }}
      </h2>
      <p class="text-xs text-slate-600 mb-3">
        {{ t('addSubject.matchDialog.description') }}
      </p>

      <ul class="space-y-2 mb-4 max-h-64 overflow-auto">
        <li
          v-for="c in candidates"
          :key="c.subjectId"
          class="border border-slate-200 rounded-md p-3 text-xs flex items-start justify-between gap-3"
          :data-testid="`patient-match-candidate-${c.subjectId}`"
        >
          <div class="flex-1 min-w-0">
            <div class="font-medium text-slate-800">
              {{ c.uniqueIdentifier ?? t('addSubject.matchDialog.unnamedSubject') }}
              <span class="text-slate-500 ml-1 font-normal">
                · {{ c.dateOfBirth ?? '—' }}
                <span v-if="c.gender">· {{ c.gender }}</span>
              </span>
            </div>
            <div class="text-slate-600 mt-0.5">
              {{ studiesLabel(c) }}
            </div>
          </div>
          <button
            type="button"
            class="shrink-0 px-3 py-1 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
            :data-testid="`patient-match-link-${c.subjectId}`"
            @click="emit('link', c)"
          >
            {{ t('addSubject.matchDialog.actionLink') }}
          </button>
        </li>
      </ul>

      <div class="flex items-center justify-end gap-2 pt-1 border-t border-slate-200 pt-3">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
          data-testid="patient-match-cancel"
          @click="emit('cancel')"
        >
          {{ t('addSubject.matchDialog.actionCancel') }}
        </button>
        <button
          type="button"
          class="px-4 py-1.5 text-xs bg-muw-coral-700 text-white rounded-md hover:bg-muw-coral font-medium"
          data-testid="patient-match-create-new"
          @click="emit('createNew')"
        >
          {{ t('addSubject.matchDialog.actionCreateNew') }}
        </button>
      </div>
    </div>
  </div>
</template>
