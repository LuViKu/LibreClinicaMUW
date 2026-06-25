<script setup lang="ts">
/**
 * Wave 2B (retinal followups) — PatientSearchModal.
 *
 * Replaces the v1 no-op {@code search-patient} emit on the
 * {@code nopatient} row state. The operator types a label prefix, the
 * modal debounces (300 ms) and calls Wave 1B's
 * {@code GET /pages/api/v1/study-subjects/search?q=&limit=10}; results
 * render with study + site context so the operator can disambiguate
 * the same subject across studies / sites.
 *
 * Picking a row emits {@code subject-picked} with the full hit; the
 * parent re-runs the row's resolve flow via the store's
 * {@code assignFromSearch} action.
 *
 * Strings come from {@code octPortal.modals.patientSearch.*} in the
 * Wave 1C i18n registry — no hard-coded German.
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import { ApiError, ApiNetworkError } from '@/api/client'
import { searchStudySubjects, type StudySubjectSearchHit } from '@/api/retinal'

interface Props {
  open: boolean
  /** Pre-fills the search field — typically the unresolved PatientId
   *  from the row's parsed scan. */
  initialQuery?: string
}

const props = withDefaults(defineProps<Props>(), {
  initialQuery: '',
})

const emit = defineEmits<{
  (e: 'subject-picked', subject: StudySubjectSearchHit): void
  (e: 'close'): void
}>()

const { t } = useI18n()

const query = ref<string>(props.initialQuery)
const results = ref<StudySubjectSearchHit[]>([])
const isSearching = ref(false)
const errorMessage = ref<string | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)

/** Reactive shape so the template can hide / show empty / too-short. */
const queryTrimmed = computed(() => query.value.trim())
const tooShort = computed(() => queryTrimmed.value.length < 2)

let debounceTimer: ReturnType<typeof setTimeout> | null = null
const DEBOUNCE_MS = 300
let activeRequestId = 0

/** Trigger a fresh search, debounced. Cancels any in-flight previous
 *  call's apply-to-state via {@link activeRequestId}. */
function scheduleSearch(): void {
  if (debounceTimer) clearTimeout(debounceTimer)
  errorMessage.value = null
  if (tooShort.value) {
    results.value = []
    isSearching.value = false
    return
  }
  isSearching.value = true
  debounceTimer = setTimeout(() => {
    void runSearch()
  }, DEBOUNCE_MS)
}

async function runSearch(): Promise<void> {
  const requestId = ++activeRequestId
  const q = queryTrimmed.value
  if (q.length < 2) {
    isSearching.value = false
    return
  }
  try {
    const hits = await searchStudySubjects(q, 10)
    if (requestId !== activeRequestId) return
    results.value = hits
  } catch (e) {
    if (requestId !== activeRequestId) return
    results.value = []
    if (e instanceof ApiError) {
      const body = e.body as { message?: string } | null
      errorMessage.value = body?.message ?? t('octPortal.modals.patientSearch.loadError')
    } else if (e instanceof ApiNetworkError) {
      errorMessage.value = t('octPortal.modals.patientSearch.loadError')
    } else {
      errorMessage.value = e instanceof Error ? e.message : t('octPortal.modals.patientSearch.loadError')
    }
  } finally {
    if (requestId === activeRequestId) {
      isSearching.value = false
    }
  }
}

function onPick(subject: StudySubjectSearchHit): void {
  emit('subject-picked', subject)
}

function onClose(): void {
  emit('close')
}

/** Reset + focus + seed when the modal opens; clear when it closes. */
watch(
  () => props.open,
  async (isOpen) => {
    if (isOpen) {
      query.value = props.initialQuery ?? ''
      results.value = []
      errorMessage.value = null
      isSearching.value = false
      activeRequestId++
      await nextTick()
      inputEl.value?.focus()
      // If the seeded query is already long enough, kick off a search
      // immediately so the operator sees results without a keystroke.
      if (queryTrimmed.value.length >= 2) {
        scheduleSearch()
      }
    } else {
      if (debounceTimer) {
        clearTimeout(debounceTimer)
        debounceTimer = null
      }
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

const labelledById = 'oct-portal-patient-search-heading'
</script>

<template>
  <Modal
    :open="props.open"
    :labelled-by="labelledById"
    panel-class="max-w-xl"
    @close="onClose"
  >
    <template #header>
      <h2 :id="labelledById" class="text-[15px] font-semibold text-slate-900">
        {{ t('octPortal.modals.patientSearch.title') }}
      </h2>
    </template>

    <div class="flex flex-col gap-3">
      <input
        ref="inputEl"
        v-model="query"
        type="search"
        autocomplete="off"
        data-testid="patient-search-input"
        :placeholder="t('octPortal.modals.patientSearch.placeholder')"
        class="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-muw-blue"
        @input="scheduleSearch"
      />

      <div v-if="errorMessage" data-testid="patient-search-error" class="text-[13px] text-rose-700">
        {{ errorMessage }}
      </div>

      <div v-else-if="tooShort" data-testid="patient-search-too-short" class="text-[13px] text-slate-500">
        {{ t('octPortal.modals.patientSearch.tooShort') }}
      </div>

      <div v-else-if="isSearching" data-testid="patient-search-loading" class="text-[13px] text-slate-500">
        {{ t('octPortal.modals.patientSearch.searching') }}
      </div>

      <div v-else-if="results.length === 0" data-testid="patient-search-empty" class="text-[13px] text-slate-500">
        {{ t('octPortal.modals.patientSearch.empty') }}
      </div>

      <ul v-else data-testid="patient-search-results" class="flex flex-col gap-1.5 max-h-96 overflow-y-auto">
        <li v-for="hit in results" :key="hit.studySubjectId">
          <button
            type="button"
            :data-testid="`patient-search-result-${hit.studySubjectId}`"
            class="w-full text-left rounded-md border border-slate-200 bg-white px-3 py-2 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-muw-blue"
            @click="onPick(hit)"
          >
            <div class="text-[13px] font-semibold text-slate-900">{{ hit.label }}</div>
            <div class="text-[11.5px] text-slate-500 mt-0.5">
              <span class="font-medium">{{ t('octPortal.modals.patientSearch.studyLabel') }}:</span>
              {{ hit.studyName }}
              <span v-if="hit.siteName" class="ml-2">
                <span class="font-medium">{{ t('octPortal.modals.patientSearch.siteLabel') }}:</span>
                {{ hit.siteName }}
              </span>
            </div>
          </button>
        </li>
      </ul>
    </div>

    <template #footer>
      <button
        type="button"
        data-testid="patient-search-cancel"
        class="px-3 py-1.5 text-[12.5px] font-medium border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
        @click="onClose"
      >
        {{ t('octPortal.modals.patientSearch.cancel') }}
      </button>
    </template>
  </Modal>
</template>
