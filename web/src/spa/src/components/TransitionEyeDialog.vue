<script setup lang="ts">
/**
 * Phase E.6 per-eye cohort transition workflow — STUB.
 *
 * The real component lives in the sibling `wt-eye-transition-dialog`
 * worktree; this file exists ONLY so this worktree's `pnpm build`
 * resolves the import from SubjectDetailView. Harmonization
 * overwrites it with the real implementation. Do NOT add tests
 * against this stub.
 */
defineProps<{
  subjectLabel: string
  eye: 'OD' | 'OS'
  currentStudyOid: string
  availableStudies: { oid: string; name: string }[]
  open: boolean
}>()
defineEmits<{
  submit: [payload: { targetStudyOid: string; targetLabel?: string; reason: string }]
  cancel: []
}>()
</script>

<template>
  <div v-if="open" data-testid="transition-eye-dialog-stub" />
</template>
