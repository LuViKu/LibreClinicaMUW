<script setup lang="ts">
/**
 * Studienaufbau section rail — the one rail with a section to navigate.
 *
 * Nine pages make up building a study (the tracker, the study's identity and
 * parameters, CRFs, visits, groups, rules, sites, modalities, users). Each of
 * them used to mount a rail holding a single link back to the tracker — a
 * 224 px column for one word that the top bar already carried. This is the
 * rail the manual promised instead: every page of the section, the current
 * one highlighted, so moving between the steps is one click and the tracker
 * is not a hub the operator has to bounce through.
 *
 * Entries are filtered by the roles the routes require, so a data manager
 * does not see the administrator-only pages listed and greyed out.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

import SideRail from '@/components/SideRail.vue'
import { useAuthStore } from '@/stores/auth'
import type { UserRole } from '@/types/auth'

interface RailItem {
  id: string
  to: string
  labelKey: string
  /** Roles the route admits; empty = any authenticated role. */
  roles: UserRole[]
  /** Highlight on the exact path only (the tracker is a prefix of nothing, but "/" would be). */
  exact?: boolean
}

const DM_ADMIN: UserRole[] = ['Data Manager', 'Administrator']

const { t } = useI18n()
const route = useRoute()
const auth = useAuthStore()

const roles = computed<UserRole[]>(() => {
  const active = auth.user?.activeStudy
  if (active?.roles && active.roles.length > 0) return [...active.roles]
  if (active?.role) return [active.role]
  return auth.user?.role ? [auth.user.role] : []
})

const activeStudyOid = computed(() => auth.user?.activeStudy?.oid ?? null)

const items = computed<RailItem[]>(() => {
  const oid = activeStudyOid.value
  const list: RailItem[] = [
    { id: 'tracker', to: '/build-study', labelKey: 'nav.buildStudy', roles: DM_ADMIN, exact: true },
  ]
  if (oid) {
    const enc = encodeURIComponent(oid)
    list.push({ id: 'study', to: `/studies/${enc}/edit`, labelKey: 'buildStudy.rail.study', roles: [] })
    list.push({ id: 'parameters', to: `/studies/${enc}/parameters`, labelKey: 'studyParameters.title', roles: [] })
  }
  list.push(
    { id: 'crf-library', to: '/crf-library', labelKey: 'crfLibrary.title', roles: DM_ADMIN },
    { id: 'event-definitions', to: '/event-definitions', labelKey: 'eventDefinitions.title', roles: DM_ADMIN },
    { id: 'group-classes', to: '/group-classes', labelKey: 'groupClasses.title', roles: DM_ADMIN },
    { id: 'rules', to: '/rules', labelKey: 'rules.title', roles: DM_ADMIN },
    { id: 'sites', to: '/sites', labelKey: 'sites.title', roles: DM_ADMIN },
    { id: 'modalities', to: '/modalities', labelKey: 'modalities.title', roles: [] },
    { id: 'manage-users', to: '/manage-users', labelKey: 'nav.manageUsers', roles: [] },
  )
  return list.filter((item) => item.roles.length === 0 || item.roles.some((r) => roles.value.includes(r)))
})

function isCurrent(item: RailItem): boolean {
  if (item.exact) return route.path === item.to
  return route.path === item.to || route.path.startsWith(item.to + '/')
}
</script>

<template>
  <SideRail>
    <div class="px-2.5 pb-1.5 text-[10px] uppercase tracking-wider text-slate-400 font-semibold">
      {{ t('buildStudy.rail.heading') }}
    </div>
    <RouterLink
      v-for="item in items"
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
