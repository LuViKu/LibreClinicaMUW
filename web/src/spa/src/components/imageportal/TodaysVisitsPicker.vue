<script setup lang="ts">
/**
 * DR-025 P1-4 — pick the visit an uploaded image belongs to.
 *
 * The operator is standing at a camera with a phone. Typing a subject label
 * correctly on a phone keyboard, in a clinic, is where mistakes come from — so
 * the page offers the same thing the camera's own worklist screen offers: the
 * visits scheduled for today, filtered by typing.
 *
 * Choosing one means the image is filed against that visit on arrival instead
 * of queueing in the reconciliation inbox for someone to sort out later.
 *
 * The list is served only when the institution has enabled it; when it is off
 * the backend answers 404 and this component renders nothing, leaving the
 * label field as the way to identify the patient.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { listTodaysVisits, type PortalVisit } from '@/api/imagePortal'

const props = defineProps<{
  /** The acquisition date, ISO. Empty means today. */
  date?: string
  /** The currently chosen visit, if any. */
  modelValue: number | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: number | null): void
  (e: 'visit-chosen', visit: PortalVisit | null): void
}>()

const { t } = useI18n()

/** null = the feature is off; [] = on, but nothing scheduled. */
const visits = ref<PortalVisit[] | null>(null)
const loading = ref(false)
const filter = ref('')

async function load(): Promise<void> {
  loading.value = true
  try {
    visits.value = await listTodaysVisits(props.date || undefined)
  } catch {
    // The list is a convenience; the label field still works without it.
    visits.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
defineExpose({ reload: load })

const available = computed(() => visits.value !== null)

const filtered = computed(() => {
  const all = visits.value ?? []
  const q = filter.value.trim().toLowerCase()
  if (!q) return all
  return all.filter(
    (v) =>
      v.subjectLabel.toLowerCase().includes(q) ||
      v.eventLabel.toLowerCase().includes(q) ||
      v.studyName.toLowerCase().includes(q),
  )
})

const chosen = computed(() => (visits.value ?? []).find((v) => v.studyEventId === props.modelValue) ?? null)

function choose(v: PortalVisit): void {
  const next = props.modelValue === v.studyEventId ? null : v
  emit('update:modelValue', next ? next.studyEventId : null)
  emit('visit-chosen', next)
}

function clear(): void {
  emit('update:modelValue', null)
  emit('visit-chosen', null)
}
</script>

<template>
  <div v-if="available" data-testid="todays-visits">
    <div class="flex items-baseline justify-between mb-1">
      <label class="block text-[13px] font-medium text-slate-600" for="ip-visit-filter">
        {{ t('imagePortal.visitPickerLabel') }}
      </label>
      <button
        v-if="chosen"
        type="button"
        class="text-[12px] text-slate-500 hover:text-slate-700 underline"
        data-testid="visit-clear"
        @click="clear"
      >
        {{ t('imagePortal.visitClear') }}
      </button>
    </div>

    <div
      v-if="chosen"
      class="rounded-lg ring-1 ring-muw-teal-200 bg-muw-teal-50 px-3 py-2.5"
      data-testid="visit-chosen"
    >
      <div class="text-[14px] font-medium text-slate-800">{{ chosen.subjectLabel }}</div>
      <div class="text-[12px] text-muw-teal-700">{{ chosen.eventLabel }} · {{ chosen.studyName }}</div>
    </div>

    <template v-else>
      <input
        id="ip-visit-filter"
        v-model="filter"
        type="text"
        inputmode="text"
        autocomplete="off"
        :placeholder="t('imagePortal.visitFilterPlaceholder')"
        class="w-full px-3 py-2.5 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
      />

      <p v-if="loading" class="mt-2 text-[12px] text-slate-500">{{ t('imagePortal.visitLoading') }}</p>
      <p
        v-else-if="filtered.length === 0"
        class="mt-2 text-[12px] text-slate-500"
        data-testid="visit-empty"
      >
        {{ t('imagePortal.visitNone') }}
      </p>

      <ul v-else class="mt-2 max-h-56 overflow-y-auto rounded-lg ring-1 ring-slate-200 divide-y divide-slate-100">
        <li v-for="v in filtered" :key="v.studyEventId">
          <button
            type="button"
            class="w-full text-left px-3 py-2.5 hover:bg-slate-50 focus:bg-slate-50 focus:outline-none"
            data-testid="visit-row"
            @click="choose(v)"
          >
            <span class="block text-[14px] font-medium text-slate-800">{{ v.subjectLabel }}</span>
            <span class="block text-[12px] text-slate-500">
              {{ v.eventLabel }} · {{ v.studyName }}<template v-if="v.time"> · {{ v.time }}</template>
            </span>
          </button>
        </li>
      </ul>
    </template>
  </div>
</template>
