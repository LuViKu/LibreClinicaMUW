<script setup lang="ts">
import ConfirmationWithPreflight from './ConfirmationWithPreflight.vue'
import type { PreflightRow } from './ConfirmationWithPreflight.vue'

const signSubjectRows: PreflightRow[] = [
  { id: 'events',  status: 'pass', title: 'All scheduled events have at least one CRF completed', detail: '3 of 3 events have data — V1 Inclusion, V2 Day 30, V3 Day 90.' },
  { id: 'crfs',    status: 'pass', title: 'All required CRFs are marked complete', detail: '11 of 11 required CRFs in status Data entry complete.' },
  { id: 'queries', status: 'warn', title: '2 open discrepancies attached to this subject', detail: 'Open queries do not block signing, but you should reconcile them first when possible.' },
  { id: 'history', status: 'info', title: 'No previous signature on this subject' },
]

const studyLockRows: PreflightRow[] = [
  { id: 'subjects', status: 'pass',    title: 'All 42 subjects signed' },
  { id: 'sdv',      status: 'pass',    title: 'All required SDV verifications complete' },
  { id: 'open',     status: 'blocker', title: '2 open queries remain', detail: 'Resolve or formally close before locking the study.' },
]
</script>

<template>
  <Story title="Primitives/ConfirmationWithPreflight" :layout="{ type: 'single' }">
    <Variant title="Sign Subject M-001 — preflight checklist">
      <div class="p-6 max-w-2xl bg-slate-50">
        <ConfirmationWithPreflight heading="Pre-flight checks" :rows="signSubjectRows" />
      </div>
    </Variant>

    <Variant title="Study lock — preflight with a blocker">
      <div class="p-6 max-w-2xl bg-slate-50">
        <ConfirmationWithPreflight heading="Lock LCDemo" :rows="studyLockRows" />
      </div>
    </Variant>
  </Story>
</template>
