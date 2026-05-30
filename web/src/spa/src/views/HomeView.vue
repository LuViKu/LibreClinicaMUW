<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
</script>

<template>
  <div class="max-w-6xl mx-auto px-6 py-14">
    <div class="flex items-center gap-2 text-[11px] font-medium uppercase tracking-[0.14em] text-muw-coral-700 mb-4">
      <span class="muw-rule"></span>
      <span>Phase E · SPA v0</span>
    </div>
    <h1 class="muw-display text-5xl font-medium text-muw-blue leading-[1.05] mb-5">
      {{ t('app.name') }}
    </h1>
    <p class="text-slate-600 text-base leading-relaxed max-w-2xl">
      {{ t('app.tagline') }}
    </p>

    <div class="mt-10 rounded-muw border border-slate-200 bg-muw-blue-50/40 p-6">
      <div class="text-[11px] uppercase tracking-[0.14em] text-muw-coral-700 font-medium mb-3">
        Smoke test
      </div>
      <p class="text-slate-700 text-sm leading-relaxed">
        If you can read this page, the Vue 3 SPA scaffolding from Phase E.1 is
        rendering, the Tailwind v4 + MUW token bundle from Phase E.2 is loaded,
        and vue-i18n is switching between
        <code class="font-mono text-xs">de-AT</code> /
        <code class="font-mono text-xs">en</code>.
      </p>
    </div>
  </div>
</template>
