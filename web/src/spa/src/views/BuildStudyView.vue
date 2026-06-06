<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useStudyStore } from '@/stores/study'
import { useAuthStore } from '@/stores/auth'
import type { StudyBuildTaskId, StudyBuildTaskStatus } from '@/types/study'

const { t } = useI18n()
const study = useStudyStore()
const auth = useAuthStore()

onMounted(() => { if (!study.status) study.load(auth.user?.activeStudy?.oid) })

// Phase E A8.1 — Administrator-only create/edit affordances. The
// backend re-checks sysadmin authoritatively.
const canManageStudy = computed(() => auth.user?.role === 'Administrator')
const activeStudyOid = computed(() => auth.user?.activeStudy?.oid ?? null)

// Phase E A8.5 — status transition affordance.
type TargetStatus = 'AVAILABLE' | 'PENDING' | 'LOCKED' | 'FROZEN'
const statusDialog = ref<{ target: TargetStatus; reason: string; error: string | null; busy: boolean } | null>(null)

function openStatusDialog(target: TargetStatus) {
  statusDialog.value = { target, reason: '', error: null, busy: false }
}

async function submitStatusChange() {
  if (!statusDialog.value || !activeStudyOid.value) return
  // Reason required for AVAILABLE→LOCKED/FROZEN — backend re-checks.
  if (
    (statusDialog.value.target === 'LOCKED' || statusDialog.value.target === 'FROZEN')
    && statusDialog.value.reason.trim() === ''
  ) {
    statusDialog.value.error = t('buildStudy.statusDialog.reasonRequired')
    return
  }
  statusDialog.value.busy = true
  try {
    const result = await study.setStatus(
      activeStudyOid.value,
      statusDialog.value.target,
      statusDialog.value.reason.trim(),
    )
    if (result.ok) {
      statusDialog.value = null
      // Reload the tracker so the new status reflects in the header.
      await study.load(activeStudyOid.value)
    } else {
      statusDialog.value.error = result.message ?? t('buildStudy.statusDialog.genericError')
    }
  } finally {
    if (statusDialog.value) statusDialog.value.busy = false
  }
}

/**
 * Phase E A8.2 — client-side deep-link resolution for task tiles.
 *
 * The backend's `task.to` is the canonical source, but until each
 * sub-slice ships its view the backend value is null. This map
 * overrides for tasks whose view we've already shipped.
 */
function deepLinkFor(taskId: StudyBuildTaskId, backendTo: string | null): string | null {
  switch (taskId) {
    case 'create-study': {
      // Study identity edit is sysadmin-only per legacy
      // StudyAdminAuthorization. Non-Admins land at the same view but
      // the SPA route guard would bounce them — gate at the link source
      // so non-Admins see the "view-only" hint instead of a button
      // that 404s into /home.
      if (!canManageStudy.value) return null
      const oid = activeStudyOid.value
      return oid ? `/studies/${encodeURIComponent(oid)}/edit` : null
    }
    case 'events': return '/event-definitions'
    case 'crf':    return '/crf-library'
    case 'sites':  return '/sites'
    case 'groups': return '/group-classes'
    case 'rules':  return '/rules'
    case 'users':  return '/manage-users'
    default:       return backendTo
  }
}

function variantFor(s: StudyBuildTaskStatus): 'success' | 'warning' | 'neutral' {
  switch (s) {
    case 'complete':    return 'success'
    case 'in-progress': return 'warning'
    case 'not-started': return 'neutral'
    case 'optional':    return 'neutral'
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
        <div class="mb-6 flex items-start justify-between gap-4">
          <div>
            <div class="text-xs text-slate-500 mb-1">{{ t('buildStudy.subTrail', { study: study.status.studyName, version: study.status.studyVersion }) }}</div>
            <h1 class="text-xl font-semibold tracking-tight">{{ t('buildStudy.title') }}</h1>
            <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('buildStudy.intro') }}</p>
          </div>
          <div v-if="canManageStudy" class="flex items-center gap-2">
            <!-- Phase E A8.5 — status dropdown. The legal options
                 are computed against the current status; the modal
                 captures the reason for GCP-sensitive transitions. -->
            <select
              class="px-2 py-1.5 text-xs border border-slate-200 rounded-md bg-white text-slate-700"
              :value="''"
              @change="(e) => openStatusDialog((e.target as HTMLSelectElement).value as TargetStatus)"
            >
              <option value="" disabled>{{ t('buildStudy.statusAction') }}</option>
              <option value="AVAILABLE">{{ t('buildStudy.statusTarget.AVAILABLE') }}</option>
              <option value="PENDING">{{ t('buildStudy.statusTarget.PENDING') }}</option>
              <option value="LOCKED">{{ t('buildStudy.statusTarget.LOCKED') }}</option>
              <option value="FROZEN">{{ t('buildStudy.statusTarget.FROZEN') }}</option>
            </select>
            <RouterLink
              v-if="activeStudyOid"
              :to="`/studies/${activeStudyOid}/edit`"
              class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            >
              {{ t('buildStudy.editAction') }}
            </RouterLink>
            <!-- Phase E.6 study-params — parameters affordance, same auth
                 gate as edit (Administrator). -->
            <RouterLink
              v-if="activeStudyOid"
              :to="`/studies/${activeStudyOid}/parameters`"
              class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            >
              {{ t('buildStudy.parametersAction') }}
            </RouterLink>
            <RouterLink
              to="/studies/new"
              class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
            >
              {{ t('buildStudy.createAction') }}
            </RouterLink>
          </div>
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
              v-if="deepLinkFor(task.id, task.to)"
              :to="deepLinkFor(task.id, task.to)!"
              class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
            >
              {{ t('common.next') }} →
            </RouterLink>
            <span
              v-else-if="task.id === 'create-study' && !canManageStudy"
              class="text-xs text-slate-400 italic"
              :title="t('buildStudy.adminOnly')"
            >{{ t('buildStudy.adminOnly') }}</span>
            <span v-else class="text-xs text-slate-400">{{ t('buildStudy.noDeepLinkYet') }}</span>
          </li>
        </ol>
      </template>

      <!-- Phase E A8.5 — status-change confirmation modal -->
      <div
        v-if="statusDialog"
        class="fixed inset-0 z-30 flex items-center justify-center bg-slate-900/30"
        role="dialog"
        aria-modal="true"
      >
        <div class="bg-white rounded-muw shadow-xl border border-slate-200 max-w-md w-full p-5">
          <h2 class="text-sm font-semibold mb-2">
            {{ t('buildStudy.statusDialog.title', { target: t(`buildStudy.statusTarget.${statusDialog.target}`) }) }}
          </h2>
          <p class="text-xs text-slate-500 mb-3">
            {{ t('buildStudy.statusDialog.intro') }}
          </p>
          <label class="block text-xs font-medium text-slate-700 mb-1">{{ t('buildStudy.statusDialog.reasonLabel') }}</label>
          <textarea
            v-model="statusDialog.reason"
            rows="3"
            class="w-full text-xs px-2 py-1.5 border border-slate-200 rounded-md"
            :placeholder="t('buildStudy.statusDialog.reasonPlaceholder')"
          />
          <p v-if="statusDialog.error" class="text-xs text-rose-600 mt-2">{{ statusDialog.error }}</p>
          <div class="mt-3 flex items-center justify-end gap-2">
            <button
              class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
              @click="statusDialog = null"
              :disabled="statusDialog.busy"
            >{{ t('common.cancel') }}</button>
            <button
              class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
              :disabled="statusDialog.busy"
              @click="submitStatusChange"
            >{{ statusDialog.busy ? t('common.saving') : t('buildStudy.statusDialog.submit') }}</button>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>
