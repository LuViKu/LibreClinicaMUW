<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

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
    <p class="text-slate-600 text-base leading-relaxed max-w-2xl mb-10">
      {{ t('app.tagline') }}
    </p>

    <section class="grid grid-cols-2 gap-4 max-w-3xl">
      <RouterLink
        to="/subjects"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-teal-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Investigator</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('subjectMatrix.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Site-scoped subject list with per-event status. Search, filter, schedule new event, sign subject.
        </p>
      </RouterLink>

      <div
        class="rounded-muw border border-slate-200 bg-slate-50 p-5 text-slate-400"
        aria-disabled="true"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-slate-300" aria-hidden="true"></span>
          <span class="font-medium">More workflows…</span>
        </div>
        <p class="text-xs leading-relaxed">
          SDV, CRF entry, Sign Subject, Build Study, etc. land per the Phase E execution playbook.
        </p>
      </div>
    </section>
  </div>
</template>
