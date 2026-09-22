<script setup lang="ts">
/**
 * System section rail — the instance-level pages, navigable as a section.
 *
 * Five pages are about the running instance rather than any study: the
 * system status, the system-wide audit trail, the password policy, the
 * application configuration and the scheduled jobs. Until 2026-09-22 none of
 * them was linked from anywhere — the top bar carried one Administrator-only
 * entry, to the audit trail, and the other four were reachable by typing
 * their address. A status page nobody can reach by clicking does not report
 * an outage.
 *
 * This is the second section rail after Studienaufbau, built to the DR-030
 * rule: a section with several pages gets one entry in the top bar and its
 * own rail on every page of the section. No role filtering, because every
 * route here admits only the Administrator; the rail mounts on nothing else.
 */
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

import SideRail from '@/components/SideRail.vue'

interface RailItem {
  id: string
  to: string
  labelKey: string
}

/** Overview first, then the trail, then the two settings pages, then the jobs. */
const ITEMS: readonly RailItem[] = [
  { id: 'status', to: '/admin/system-status', labelKey: 'adminSystemStatus.title' },
  { id: 'audit', to: '/system/audit-log', labelKey: 'system.rail.audit' },
  { id: 'password-policy', to: '/admin/password-policy', labelKey: 'adminPasswordPolicy.title' },
  { id: 'config', to: '/admin/config', labelKey: 'adminConfig.title' },
  { id: 'jobs', to: '/admin/jobs', labelKey: 'adminJobs.title' },
]

const { t } = useI18n()
const route = useRoute()

function isCurrent(item: RailItem): boolean {
  return route.path === item.to || route.path.startsWith(item.to + '/')
}
</script>

<template>
  <SideRail>
    <div class="px-2.5 pb-1.5 text-[10px] uppercase tracking-wider text-slate-400 font-semibold">
      {{ t('system.rail.heading') }}
    </div>
    <RouterLink
      v-for="item in ITEMS"
      :key="item.id"
      :to="item.to"
      class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md min-h-8"
      :class="isCurrent(item) ? 'bg-muw-blue-50 text-muw-blue font-medium' : 'text-slate-700 hover:bg-white'"
      :aria-current="isCurrent(item) ? 'page' : undefined"
      :data-testid="`rail-${item.id}`"
    >
      {{ t(item.labelKey) }}
    </RouterLink>
  </SideRail>
</template>
