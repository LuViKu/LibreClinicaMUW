<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import SelectInput from '@/components/SelectInput.vue'
import Timeline from '@/components/Timeline.vue'
import TimelineMarker from '@/components/TimelineMarker.vue'
import TimelineEvent from '@/components/TimelineEvent.vue'
import DiffCard from '@/components/DiffCard.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useAuditLogStore } from '@/stores/auditLog'
import type { AuditEvent, AuditEventVariant } from '@/types/audit'

const { t } = useI18n()
const store = useAuditLogStore()

onMounted(() => {
  if (store.events.length === 0) store.load()
})

function variantLabel(v: AuditEventVariant): string {
  return t(`auditLog.variant.${v}`)
}

const MONTH_ABBR = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
function formatDateHeading(iso: string): string {
  const [y, m, d] = iso.split('-').map((s) => Number.parseInt(s, 10))
  return `${String(d ?? 1).padStart(2, '0')}-${MONTH_ABBR[(m ?? 1) - 1] ?? '???'}-${y}`
}
function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

function roleVariant(role: AuditEvent['actorRole']): 'investigator' | 'monitor' | 'data-manager' | 'neutral' {
  if (role === 'Investigator') return 'investigator'
  if (role === 'Monitor') return 'monitor'
  if (role === 'Data Manager') return 'data-manager'
  return 'neutral'
}

const variantOptions: { v: 'all' | AuditEventVariant; l: () => string }[] = [
  { v: 'all', l: () => t('auditLog.variant.all') },
  { v: 'signed', l: () => t('auditLog.variant.signed') },
  { v: 'reason-for-change', l: () => t('auditLog.variant.reason-for-change') },
  { v: 'sdv', l: () => t('auditLog.variant.sdv') },
  { v: 'admin', l: () => t('auditLog.variant.admin') },
  { v: 'data', l: () => t('auditLog.variant.data') },
  { v: 'query', l: () => t('auditLog.variant.query') },
  { v: 'subject-group-change', l: () => t('auditLog.variant.subject-group-change') },
]

const today = computed(() => new Date().toISOString().slice(0, 10))
const yesterday = computed(() => {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return d.toISOString().slice(0, 10)
})

function dateHeading(iso: string): string {
  if (iso === today.value) return t('auditLog.heading.today')
  if (iso === yesterday.value) return t('auditLog.heading.yesterday')
  return formatDateHeading(iso)
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
      <RouterLink to="/notes" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M4 15a4 4 0 0 0 4 4h12V5a2 2 0 0 0-2-2H8a4 4 0 0 0-4 4z" />
        </svg>
        {{ t('nav.notes') }}
      </RouterLink>
      <RouterLink to="/audit-log" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        </svg>
        {{ t('nav.auditLog') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 px-8 py-6">
      <div class="mb-5">
        <div class="text-xs text-slate-500 mb-1">{{ t('auditLog.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('auditLog.title') }}</h1>
        <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('auditLog.intro') }}</p>
      </div>

      <!-- Filter row -->
      <div class="flex flex-wrap items-end gap-3 mb-6 text-xs">
        <div class="w-44">
          <label class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('auditLog.filter.actor') }}</label>
          <SelectInput id="al-actor" :model-value="store.actorFilter" @update:model-value="(v) => store.actorFilter = v as string">
            <option value="">{{ t('auditLog.filter.allActors') }}</option>
            <option v-for="a in store.actors" :key="a" :value="a">{{ a }}</option>
          </SelectInput>
        </div>

        <div class="w-44">
          <label class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('auditLog.filter.variant') }}</label>
          <SelectInput id="al-variant" :model-value="store.variantFilter" @update:model-value="(v) => store.variantFilter = v as 'all' | AuditEventVariant">
            <option v-for="o in variantOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
          </SelectInput>
        </div>

        <div class="w-44">
          <label class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('auditLog.filter.subject') }}</label>
          <SelectInput id="al-subject" :model-value="store.subjectFilter" @update:model-value="(v) => store.subjectFilter = v as string">
            <option value="">{{ t('auditLog.filter.allSubjects') }}</option>
            <option v-for="s in store.subjects" :key="s" :value="s">{{ s }}</option>
          </SelectInput>
        </div>

        <button
          v-if="store.actorFilter || store.variantFilter !== 'all' || store.subjectFilter"
          type="button"
          class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
          @click="store.clearFilters()"
        >
          {{ t('common.clear') }}
        </button>

        <div class="ml-auto text-slate-500">
          {{ t('auditLog.showingCount', { visible: store.visibleCount, total: store.totalCount }) }}
        </div>
      </div>

      <p v-if="store.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="store.error" class="text-rose-700">{{ store.error }}</p>
      <p v-else-if="store.visibleCount === 0" class="text-slate-500">{{ t('auditLog.empty') }}</p>

      <Timeline v-else>
        <template v-for="group in store.groupedByDate" :key="group.date">
          <TimelineMarker>{{ dateHeading(group.date) }}</TimelineMarker>

          <TimelineEvent
            v-for="ev in group.events"
            :key="ev.id"
            :variant="ev.variant"
          >
            <div class="flex items-center justify-between text-xs flex-wrap gap-y-1">
              <div class="flex items-center gap-2 flex-wrap">
                <span class="font-medium text-slate-900">{{ ev.title }}</span>
                <StatusPill v-if="ev.subjectId" compact variant="neutral">{{ ev.subjectId }}</StatusPill>
                <span v-if="ev.scope" class="font-mono text-slate-600">{{ ev.scope }}</span>
                <span class="text-slate-500">{{ t('auditLog.by') }}</span>
                <span class="font-mono text-slate-700">{{ ev.actor }}</span>
                <StatusPill v-if="ev.actorRole" compact :variant="roleVariant(ev.actorRole)">
                  {{ ev.actorRole }}
                </StatusPill>
                <span class="sr-only">· {{ variantLabel(ev.variant) }}</span>
              </div>
              <span class="text-slate-500 font-mono">{{ formatTime(ev.occurredAt) }}</span>
            </div>

            <div v-if="(ev.variant === 'reason-for-change' || ev.variant === 'subject-group-change') && ev.before != null && ev.after != null" class="mt-2">
              <DiffCard>
                <template #before>{{ ev.before }}</template>
                <template #after>{{ ev.after }}</template>
              </DiffCard>
            </div>

            <p v-if="ev.details" class="text-xs text-slate-600 mt-1.5">{{ ev.details }}</p>
            <p v-if="ev.reason" class="text-xs text-slate-600 italic mt-1.5">&ldquo;{{ ev.reason }}&rdquo;</p>
          </TimelineEvent>
        </template>
      </Timeline>
    </main>
  </div>
</template>
