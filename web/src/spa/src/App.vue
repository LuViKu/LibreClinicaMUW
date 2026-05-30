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
</script>

<template>
  <div class="min-h-screen bg-white text-slate-900 text-sm">
    <TopBar
      :breadcrumb="breadcrumb"
      user-name="user_demo"
      user-role="Investigator"
    />
    <RouterView />
  </div>
</template>
