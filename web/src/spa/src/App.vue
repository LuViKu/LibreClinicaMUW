<script setup lang="ts">
import { RouterView } from 'vue-router'
import TopBar from '@/components/TopBar.vue'
</script>

<template>
  <div class="min-h-screen bg-white text-slate-900 text-sm">
    <TopBar />
    <main>
      <RouterView />
    </main>
  </div>
</template>
