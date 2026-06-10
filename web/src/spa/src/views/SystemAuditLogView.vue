<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SelectInput from '@/components/SelectInput.vue'
import Timeline from '@/components/Timeline.vue'
import TimelineMarker from '@/components/TimelineMarker.vue'
import TimelineEvent from '@/components/TimelineEvent.vue'
import DiffCard from '@/components/DiffCard.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useSystemAuditLogStore } from '@/stores/systemAuditLog'
import type { AuditEvent, AuditEventVariant } from '@/types/audit'

/**
 * Phase E hardening B (sysadmin audit UI).
 *
 * Mirrors {@link StudyAuditLogView} in shape (same Timeline + DiffCard
 * + filter components) but reads the system-wide endpoint so
 * OPERATION_FAILED + JOB_FAILED §11.10(e) rows are surfaced.
 * Differences from the per-study view:
 *   - sysadmin-only (route guard gates on meta.role: 'Administrator'),
 *   - no study scope — the system endpoint is institution-wide,
 *   - no SideRail (this view lives outside the per-study workspace),
 *   - no XLSX export — deferred per the hardening plan.
 */

const { t } = useI18n()
const store = useSystemAuditLogStore()

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

const expanded = ref<Set<string>>(new Set())
function toggle(id: string): void {
  if (expanded.value.has(id)) expanded.value.delete(id)
  else expanded.value.add(id)
  expanded.value = new Set(expanded.value)
}
function isExpanded(id: string): boolean { return expanded.value.has(id) }
function hasExpandable(ev: AuditEvent): boolean {
  return (ev.before != null && ev.after != null)
    || (ev.details != null && ev.details !== '')
    || (ev.reason != null && ev.reason !== '')
}
</script>

<template>
  <main class="flex-1 px-8 py-6">
    <div class="mb-5">
      <div class="text-xs text-slate-500 mb-1">{{ t('auditLog.systemTrail.subtitle') }}</div>
      <h1 class="text-xl font-semibold tracking-tight">{{ t('auditLog.systemTrail.title') }}</h1>
    </div>

    <!-- Filter row -->
    <div class="flex flex-wrap items-end gap-3 mb-6 text-xs">
      <div class="w-44">
        <label class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('auditLog.filter.actor') }}</label>
        <SelectInput id="sal-actor" :model-value="store.actorFilter" @update:model-value="(v) => store.actorFilter = v as string">
          <option value="">{{ t('auditLog.filter.allActors') }}</option>
          <option v-for="a in store.actors" :key="a" :value="a">{{ a }}</option>
        </SelectInput>
      </div>

      <div class="w-44">
        <label class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('auditLog.filter.variant') }}</label>
        <SelectInput id="sal-variant" :model-value="store.variantFilter" @update:model-value="(v) => store.variantFilter = v as 'all' | AuditEventVariant">
          <option v-for="o in variantOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
        </SelectInput>
      </div>

      <div class="w-44">
        <label class="block text-[10px] uppercase tracking-wider text-slate-500 mb-1 font-semibold">{{ t('auditLog.filter.subject') }}</label>
        <SelectInput id="sal-subject" :model-value="store.subjectFilter" @update:model-value="(v) => store.subjectFilter = v as string">
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

      <div class="ml-auto flex items-center gap-3 text-slate-500">
        <span>{{ t('auditLog.showingCount', { visible: store.visibleCount, total: store.totalCount }) }}</span>
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
          <component
            :is="hasExpandable(ev) ? 'button' : 'div'"
            :type="hasExpandable(ev) ? 'button' : undefined"
            class="w-full flex items-center justify-between text-xs flex-wrap gap-y-1 text-left"
            :class="hasExpandable(ev) ? 'cursor-pointer focus:outline-none focus:ring-2 focus:ring-muw-blue/30 rounded -mx-1 px-1' : ''"
            :aria-expanded="hasExpandable(ev) ? isExpanded(ev.id) : undefined"
            :aria-controls="hasExpandable(ev) ? `sysaudit-${ev.id}-body` : undefined"
            @click="hasExpandable(ev) && toggle(ev.id)"
          >
            <div class="flex items-center gap-2 flex-wrap">
              <svg
                v-if="hasExpandable(ev)"
                width="12" height="12" viewBox="0 0 24 24"
                fill="none" stroke="currentColor" stroke-width="2"
                class="text-slate-400 transition-transform"
                :class="isExpanded(ev.id) ? 'rotate-90' : ''"
                aria-hidden="true"
              >
                <polyline points="9 6 15 12 9 18" />
              </svg>
              <span class="font-medium text-slate-900">{{ ev.title }}</span>
              <StatusPill v-if="ev.subjectId" compact variant="neutral">{{ ev.subjectId }}</StatusPill>
              <span v-if="ev.scope" class="font-mono text-slate-600">{{ ev.scope }}</span>
              <span v-if="ev.details" class="font-mono text-slate-600">{{ ev.details }}</span>
              <span class="text-slate-500">{{ t('auditLog.by') }}</span>
              <span class="font-mono text-slate-700">{{ ev.actor }}</span>
              <StatusPill v-if="ev.actorRole" compact :variant="roleVariant(ev.actorRole)">
                {{ ev.actorRole }}
              </StatusPill>
              <span class="sr-only">· {{ variantLabel(ev.variant) }}</span>
            </div>
            <span class="text-slate-500 font-mono">{{ formatTime(ev.occurredAt) }}</span>
          </component>

          <div
            v-if="hasExpandable(ev) && isExpanded(ev.id)"
            :id="`sysaudit-${ev.id}-body`"
            class="mt-2 space-y-2"
          >
            <DiffCard v-if="ev.before != null && ev.after != null">
              <template #before>{{ ev.before }}</template>
              <template #after>{{ ev.after }}</template>
            </DiffCard>
            <p v-if="ev.reason" class="text-xs text-slate-600 italic">&ldquo;{{ ev.reason }}&rdquo;</p>
          </div>
        </TimelineEvent>
      </template>
    </Timeline>
  </main>
</template>
