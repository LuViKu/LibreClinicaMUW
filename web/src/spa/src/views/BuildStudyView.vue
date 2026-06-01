<script setup lang="ts">
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useStudyStore } from '@/stores/study'
import type { StudyBuildTaskId, StudyBuildTaskStatus } from '@/types/study'

const { t } = useI18n()
const study = useStudyStore()

onMounted(() => { if (!study.status) study.load() })

function variantFor(s: StudyBuildTaskStatus): 'success' | 'warning' | 'neutral' {
  switch (s) {
    case 'complete':    return 'success'
    case 'in-progress': return 'warning'
    case 'not-started': return 'neutral'
  }
}

function iconFor(id: StudyBuildTaskId): string {
  const map: Record<StudyBuildTaskId, string> = {
    'create-study': 'M3 7h18M3 12h18M3 17h12',
    'crf':          'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z',
    'events':       'M3 4h18v18H3zM3 10h18',
    'groups':       'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    'rules':        'M9 11l3 3 8-8M21 12a9 9 0 1 1-9-9',
    'sites':        'M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z',
    'users':        'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  }
  return map[id]
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M3 7h18M3 12h18M3 17h12" />
        </svg>
        {{ t('nav.buildStudy') }}
      </RouterLink>
      <RouterLink to="/manage-users" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z" />
        </svg>
        {{ t('nav.manageUsers') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-4xl px-8 py-8">
      <p v-if="study.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="study.error" class="text-rose-700">{{ study.error }}</p>

      <template v-else-if="study.status">
        <div class="mb-6">
          <div class="text-xs text-slate-500 mb-1">{{ t('buildStudy.subTrail', { study: study.status.studyName, version: study.status.studyVersion }) }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('buildStudy.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('buildStudy.intro') }}</p>
        </div>

        <!-- Progress card -->
        <section class="bg-white border border-slate-200 rounded-muw p-5 mb-6">
          <div class="flex items-end justify-between mb-3">
            <div>
              <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">{{ t('buildStudy.progress') }}</div>
              <div class="text-2xl font-semibold text-muw-blue mt-1">
                {{ study.completedTasks }} / {{ study.totalTasks }}
              </div>
            </div>
            <div class="text-right">
              <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">{{ t('buildStudy.completion') }}</div>
              <div class="text-2xl font-semibold text-muw-blue mt-1">{{ study.percentComplete }}%</div>
            </div>
          </div>
          <div class="w-full h-2 bg-slate-100 rounded-full overflow-hidden" :aria-label="t('buildStudy.progressAriaLabel', { percent: study.percentComplete })">
            <div class="h-full bg-muw-blue transition-all" :style="{ width: study.percentComplete + '%' }"></div>
          </div>
          <dl class="grid grid-cols-2 gap-4 mt-4 text-xs text-slate-600">
            <div class="flex justify-between"><dt>{{ t('buildStudy.sites') }}</dt><dd class="text-slate-900 font-medium">{{ study.status.sites }}</dd></div>
            <div class="flex justify-between"><dt>{{ t('buildStudy.enrolledSubjects') }}</dt><dd class="text-slate-900 font-medium">{{ study.status.enrolledSubjects }}</dd></div>
          </dl>
        </section>

        <!-- Task tracker -->
        <ol class="space-y-3" role="list" :aria-label="t('buildStudy.taskListAriaLabel')">
          <li
            v-for="(task, idx) in study.status.tasks"
            :key="task.id"
            class="bg-white border border-slate-200 rounded-muw p-4 flex items-center gap-4"
          >
            <span
              class="w-10 h-10 rounded-full inline-flex items-center justify-center shrink-0"
              :class="task.status === 'complete'
                ? 'bg-muw-teal-100 text-muw-teal-700'
                : task.status === 'in-progress'
                  ? 'bg-amber-100 text-amber-700'
                  : 'bg-slate-100 text-slate-500'"
            >
              <svg
                v-if="task.status === 'complete'"
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.75"
                aria-hidden="true"
              >
                <polyline points="20 6 9 17 4 12" />
              </svg>
              <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <path :d="iconFor(task.id)" />
              </svg>
            </span>

            <div class="flex-1 min-w-0">
              <div class="flex items-baseline gap-2 flex-wrap">
                <span class="text-[11px] text-slate-400 font-mono">{{ String(idx + 1).padStart(2, '0') }}</span>
                <h3 class="font-medium text-slate-900">{{ t(`buildStudy.task.${task.id}.title`) }}</h3>
                <StatusPill :variant="variantFor(task.status)">{{ t(`buildStudy.status.${task.status}`) }}</StatusPill>
                <span v-if="task.count != null" class="text-xs text-slate-500">{{ t(`buildStudy.task.${task.id}.summary`, { count: task.count }) }}</span>
              </div>
              <p class="text-xs text-slate-500 mt-1 leading-relaxed">{{ t(`buildStudy.task.${task.id}.description`) }}</p>
            </div>

            <RouterLink
              v-if="task.to"
              :to="task.to"
              class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
            >
              {{ t('common.next') }} →
            </RouterLink>
            <span v-else class="text-xs text-slate-400">{{ t('buildStudy.noDeepLinkYet') }}</span>
          </li>
        </ol>
      </template>
    </main>
  </div>
</template>
