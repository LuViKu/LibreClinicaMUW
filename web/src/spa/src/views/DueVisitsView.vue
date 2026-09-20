<script setup lang="ts">
/**
 * P2-6 — who is expected, and who was missed.
 *
 * A visit nobody schedules a patient for simply does not happen, and in a
 * treat-and-extend study a missed visit is a missed injection. Without a list
 * there is no moment at which anyone notices.
 *
 * The window therefore reaches into the past by default: overdue rows are the
 * point of the view, not a side effect, and they sort to the top.
 *
 * The backend scopes the list to the studies the session can see, so a monitor
 * with site-only grants does not learn another site's schedule. Nothing is
 * filtered here.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { listDueVisits, type DueVisit } from '@/api/events'

const { t } = useI18n()

function isoDaysFromToday(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

const from = ref(isoDaysFromToday(-14))
const to = ref(isoDaysFromToday(14))
const rows = ref<DueVisit[]>([])
const loading = ref(true)
const error = ref<string | null>(null)


/** Overdue first, then by date. What was missed matters more than what is coming. */
const sorted = computed(() =>
  [...rows.value].sort((a, b) => {
    if (a.overdue !== b.overdue) return a.overdue ? -1 : 1
    return (a.date ?? '').localeCompare(b.date ?? '')
  }),
)

const overdueCount = computed(() => rows.value.filter((r) => r.overdue).length)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const res = await listDueVisits({ from: from.value, to: to.value })
    rows.value = res.visits
  } catch (e) {
    rows.value = []
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="p-4 sm:p-6 max-w-5xl mx-auto">
    <h1 class="text-[20px] font-semibold text-slate-800">{{ t('dueVisits.title') }}</h1>
    <p class="mt-1 text-[13px] text-slate-500">{{ t('dueVisits.intro') }}</p>

    <form class="mt-4 flex flex-wrap items-end gap-3" @submit.prevent="load">
      <div>
        <label class="block text-[13px] font-medium text-slate-600 mb-1" for="dv-from">
          {{ t('dueVisits.from') }}
        </label>
        <input
          id="dv-from"
          v-model="from"
          type="date"
          class="px-3 py-2 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
        />
      </div>
      <div>
        <label class="block text-[13px] font-medium text-slate-600 mb-1" for="dv-to">
          {{ t('dueVisits.to') }}
        </label>
        <input
          id="dv-to"
          v-model="to"
          type="date"
          class="px-3 py-2 border border-slate-300 rounded-lg focus:outline-none focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100"
        />
      </div>
      <button
        type="submit"
        class="px-4 py-2 rounded-lg bg-muw-blue text-white text-[14px] font-medium hover:bg-muw-blue-700 disabled:bg-slate-300"
        :disabled="loading"
        data-testid="due-reload"
      >
        {{ loading ? t('dueVisits.loading') : t('dueVisits.reload') }}
      </button>
      <span
        v-if="!loading && overdueCount > 0"
        class="ml-auto inline-flex items-center gap-1.5 rounded-full bg-amber-50 text-amber-800 text-[12px] font-medium px-2.5 py-1"
        data-testid="due-overdue-count"
      >
        {{ t('dueVisits.overdueCount', { count: overdueCount }) }}
      </span>
    </form>

    <p v-if="error" class="mt-4 text-[13px] text-rose-700" data-testid="due-error">{{ error }}</p>

    <p v-else-if="!loading && rows.length === 0" class="mt-6 text-[13px] text-slate-500" data-testid="due-empty">
      {{ t('dueVisits.empty') }}
    </p>

    <div v-else-if="!loading" class="mt-5 overflow-x-auto rounded-xl ring-1 ring-slate-200 bg-white">
      <table class="w-full text-left text-[13px]">
        <thead class="bg-slate-50 text-slate-600">
          <tr>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('dueVisits.colDate') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('dueVisits.colSubject') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('dueVisits.colVisit') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('dueVisits.colStudy') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('dueVisits.colStatus') }}</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-for="r in sorted" :key="r.studyEventId" data-testid="due-row">
            <td class="px-3 py-2 whitespace-nowrap">
              <span :class="r.overdue ? 'text-amber-800 font-medium' : 'text-slate-700'">
                {{ r.date }}<template v-if="r.time"> · {{ r.time }}</template>
              </span>
              <span
                v-if="r.overdue"
                class="ml-2 inline-block rounded-full bg-amber-100 text-amber-800 text-[11px] px-2 py-0.5"
                data-testid="due-overdue-badge"
              >
                {{ t('dueVisits.overdue') }}
              </span>
            </td>
            <td class="px-3 py-2">
              <RouterLink
                class="text-muw-blue hover:underline"
                :to="`/subjects/${encodeURIComponent(r.subjectLabel)}`"
              >
                {{ r.subjectLabel }}
              </RouterLink>
            </td>
            <td class="px-3 py-2 text-slate-700">{{ r.eventLabel }}</td>
            <td class="px-3 py-2 text-slate-500">{{ r.studyName }}</td>
            <td class="px-3 py-2 text-slate-500">{{ r.status }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
