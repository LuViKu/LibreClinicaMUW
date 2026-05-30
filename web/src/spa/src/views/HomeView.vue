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

      <RouterLink
        to="/event-crfs/EC_M001_V1_DEMO"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-teal-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Investigator</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('crfEntry.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          M-001 · V1 Inclusion · Demographics — section-based entry with inline validation + save / mark-complete.
        </p>
      </RouterLink>

      <RouterLink
        to="/subjects/new"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-teal-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Investigator</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('nav.addSubject') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Enrol a study subject. PHI-aware validation, gender selector, three save modes.
        </p>
      </RouterLink>

      <RouterLink
        to="/sdv"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-sky-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Monitor</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('sdv.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Completed CRFs across all subjects with SDV requirement, bulk-verify, inline Add Query modal.
        </p>
      </RouterLink>

      <RouterLink
        to="/notes"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-sky-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Monitor</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('notes.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Cross-subject query + annotation list with status / type / assigned-to-me filters.
        </p>
      </RouterLink>

      <RouterLink
        to="/audit-log"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-sky-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Monitor</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('auditLog.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Lückenlose Zeitleiste mit Diff-Karten bei Änderungsgründen. Filterung nach Akteur / Ereignistyp / Teilnehmer.
        </p>
      </RouterLink>

      <RouterLink
        to="/build-study"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-coral-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Data Manager</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('buildStudy.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          7-task setup tracker with progress bar and per-task status.
        </p>
      </RouterLink>

      <RouterLink
        to="/manage-users"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-coral-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Data Manager</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('manageUsers.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Per-user role + site + auth method; pending invites badge.
        </p>
      </RouterLink>

      <RouterLink
        to="/import-crf-data"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-coral-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Data Manager</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('importCrf.title') }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Multi-step wizard: Upload → Map → Preview & resolve → Commit. Per-row before/after diffs.
        </p>
      </RouterLink>

      <RouterLink
        to="/subjects/M-001/sign"
        class="rounded-muw border border-slate-200 bg-white p-5 hover:border-muw-blue-200 hover:shadow-muw-card transition group"
      >
        <div class="flex items-center gap-2 mb-2">
          <span class="w-1.5 h-1.5 rounded-full bg-muw-teal-500" aria-hidden="true"></span>
          <span class="font-medium text-muw-blue">Investigator</span>
        </div>
        <div class="font-semibold text-slate-900 group-hover:underline mb-1">
          {{ t('signSubject.title', { id: 'M-001' }) }}
        </div>
        <p class="text-slate-500 text-xs leading-relaxed">
          Pre-flight checklist + casebook snapshot + e-signature attestation. 21 CFR Part 11 §11.50.
        </p>
      </RouterLink>
    </section>
  </div>
</template>
