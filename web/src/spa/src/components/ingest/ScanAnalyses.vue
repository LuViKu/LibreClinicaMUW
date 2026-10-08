<script setup lang="ts">
/**
 * 2026-10-09 — a filed OCT scan's retinal analyses, on its card on the visit
 * page, and "Auswertung starten" for the tasks it does not have yet.
 *
 * A bind starts what the visit's imaging plan asks for; a scan the plan did
 * not cover, or a task wanted in addition, had no way to be started. The
 * menu offers only the tasks the scan lacks. The server answers a task that
 * appeared in the meantime with 409 and the existing job, and the operator is
 * taken there, as rerun-as does.
 *
 * Shown only for a file the server marks analysable, and the menu only when
 * the analyses could be listed (`analyses` is an array) and the caller may
 * start one (`canStart`, the same rule as rerun-as).
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRouter } from 'vue-router'

import { startScanAnalysis, type IngestItem } from '@/api/ingest'
import { jobRoute, STARTABLE_TASKS, type StartableTask } from '@/lib/retinalJobs'
import { useErrorsStore } from '@/stores/errors'

interface Props {
  item: IngestItem
  /** The visit's subject, for the jobs' canonical addresses. */
  subjectLabel: string | null
  /** Whether the caller may start an analysis here (role and visit state). */
  canStart: boolean
  /** Whether the caller may open a job's metrics page; otherwise the analyses are listed without links. */
  canOpen: boolean
}

const props = defineProps<Props>()
const { t } = useI18n()
const router = useRouter()
const errors = useErrorsStore()

const analyses = computed(() => props.item.analyses ?? [])
const missing = computed<StartableTask[]>(() => {
  if (!Array.isArray(props.item.analyses)) return []
  const have = new Set(analyses.value.map((a) => a.task))
  return STARTABLE_TASKS.filter((task) => !have.has(task))
})
const showMenu = computed(() => props.canStart && missing.value.length > 0)

const menuOpen = ref(false)
const starting = ref(false)

function label(kind: 'task' | 'status', value: string): string {
  const key = `retinal.${kind}.${value}`
  const translated = t(key)
  return translated === key ? value : translated
}

function linkOf(jobId: number, subjectSeq: number | null): string {
  return jobRoute({ jobId, subjectLabel: props.subjectLabel, subjectSeq })
}

async function start(task: StartableTask): Promise<void> {
  menuOpen.value = false
  starting.value = true
  try {
    const res = await startScanAnalysis(props.item.id, task)
    await router.push(jobRoute(res))
  } catch (e) {
    const err = e as { status?: number; body?: unknown }
    const body = err.body && typeof err.body === 'object'
      ? (err.body as { existingJobId?: number; subjectLabel?: string; subjectSeq?: number; message?: string })
      : null
    if (typeof body?.existingJobId === 'number' && body.existingJobId > 0) {
      await router.push(jobRoute({
        jobId: body.existingJobId,
        subjectLabel: body.subjectLabel ?? props.subjectLabel,
        subjectSeq: body.subjectSeq,
      }))
      return
    }
    const parts = [t('eventDetail.images.analyses.error')]
    if (err.status) parts.push(`HTTP ${err.status}`)
    const serverMsg = body?.message ?? (typeof err.body === 'string' ? err.body : '')
    if (serverMsg) parts.push(serverMsg)
    const msg = parts.join(' — ')
    errors.push(e instanceof Error ? Object.assign(new Error(msg), { cause: e }) : new Error(msg),
      'ingest.startAnalysis')
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <div v-if="item.analysable" class="mt-1.5" :data-testid="`scan-analyses-${item.id}`">
    <ul v-if="analyses.length" class="space-y-0.5" data-testid="scan-analyses-list">
      <li v-for="a in analyses" :key="a.jobId" class="flex items-baseline gap-1">
        <RouterLink
          v-if="canOpen"
          :to="linkOf(a.jobId, a.subjectSeq)"
          class="text-muw-blue hover:underline"
          :data-testid="`scan-analysis-link-${a.jobId}`"
        >{{ label('task', a.task) }}</RouterLink>
        <span v-else>{{ label('task', a.task) }}</span>
        <span class="text-slate-400">· {{ label('status', a.status) }}</span>
      </li>
    </ul>
    <p v-else-if="Array.isArray(item.analyses)" class="text-slate-400 italic" data-testid="scan-analyses-none">
      {{ t('eventDetail.images.analyses.none') }}
    </p>

    <div v-if="showMenu" class="relative mt-1" data-testid="scan-analyses-start">
      <button
        type="button"
        class="text-[11px] text-slate-600 hover:text-slate-900 underline disabled:opacity-50"
        :disabled="starting"
        :aria-expanded="menuOpen"
        aria-haspopup="menu"
        @click="menuOpen = !menuOpen"
      >{{ starting ? t('eventDetail.images.analyses.starting') : t('eventDetail.images.analyses.start') }}</button>
      <div
        v-if="menuOpen"
        class="absolute left-0 mt-1 w-56 bg-white rounded-lg border border-slate-200 shadow-lg z-20 py-1.5"
        role="menu"
        data-testid="scan-analyses-menu"
      >
        <div class="px-3 py-1 text-[10px] uppercase tracking-[0.08em] font-semibold text-slate-400">
          {{ t('eventDetail.images.analyses.menuHeader') }}
        </div>
        <button
          v-for="task in missing"
          :key="task"
          type="button"
          role="menuitem"
          class="w-full text-left px-3 py-1.5 text-[12px] text-slate-700 hover:bg-slate-50"
          :data-testid="`scan-analyses-task-${task}`"
          @click="start(task)"
        >{{ label('task', task) }}</button>
      </div>
    </div>
  </div>
</template>
