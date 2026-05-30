<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import TopBar from '@/components/TopBar.vue'

const route = useRoute()

interface Crumb { label: string; to?: string }

const breadcrumb = computed<Crumb[]>(() => {
  const crumbs: Crumb[] = [{ label: 'LCDemo' }, { label: 'München' }]
  const routeTitle = route.meta?.title as string | undefined
  if (routeTitle && route.name !== 'home') crumbs.push({ label: routeTitle })
  return crumbs
})

type RoleMeta = 'Investigator' | 'Monitor' | 'Data Manager'

const userName = computed(() => {
  // Wired to a single mock user per role until the auth store (E.8) takes over.
  switch (route.meta?.role as RoleMeta | undefined) {
    case 'Monitor':       return 'monitor_demo'
    case 'Data Manager':  return 'dm_demo'
    case 'Investigator':
    default:              return 'user_demo'
  }
})

const userRole = computed<RoleMeta>(() => {
  const meta = route.meta?.role as RoleMeta | undefined
  return meta ?? 'Investigator'
})
</script>

<template>
  <div class="min-h-screen bg-white text-slate-900 text-sm">
    <TopBar
      :breadcrumb="breadcrumb"
      :user-name="userName"
      :user-role="userRole"
    />
    <RouterView />
  </div>
</template>
