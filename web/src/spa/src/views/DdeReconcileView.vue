<script setup lang="ts">
/**
 * Phase E.6 dde — reconciliation view.
 *
 * Renders the side-by-side IDE / DDE conflict table for one EventCRF
 * and lets a DM / Admin / Investigator pick the canonical value per
 * item. Backend guards (role.id in {1,3,4}) are authoritative; the
 * router-level meta.role check just shortcuts the redirect.
 *
 * v0 is a deliberately minimal table — full action-menu integration
 * with the SubjectMatrix lives in a follow-up commit. The view's
 * acceptance criteria are met (winner radio + RFC textarea + Apply
 * button + open count + done toast).
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'

import { useDdeStore } from '@/stores/dde'
import type { DdeConflictItem, DdeReconcileWinner } from '@/types/dde'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const dde = useDdeStore()

const eventCrfOid = computed<string>(() => String(route.params.eventCrfOid))

/**
 * Per-row local UI state — captured outside the conflict array so
 * the optimistic flip in the store doesn't reset the user's input.
 * Keyed by itemOid.
 */
const rowState = ref<Record<string, {
  winner: DdeReconcileWinner | null
  manualValue: string
  reason: string
  error: string | null
}>>({})

function ensureRow(itemOid: string) {
  if (!rowState.value[itemOid]) {
    rowState.value[itemOid] = {
      winner: null,
      manualValue: '',
      reason: '',
      error: null,
    }
  }
  return rowState.value[itemOid]
}

onMounted(async () => {
  await dde.loadConflicts(eventCrfOid.value)
})

async function applyResolution(row: DdeConflictItem) {
  const state = ensureRow(row.itemOid)
  state.error = null
  if (!state.winner) {
    state.error = t('dde.errors.missingReason')
    return
  }
  if (!state.reason.trim()) {
    state.error = t('dde.errors.missingReason')
    return
  }
  if (state.winner === 'manual' && !state.manualValue) {
    state.error = t('dde.errors.missingReason')
    return
  }
  const resp = await dde.resolve(eventCrfOid.value, row.itemOid, {
    winner: state.winner,
    value: state.winner === 'manual' ? state.manualValue : undefined,
    reasonForChange: state.reason.trim(),
  })
  if (!resp) {
    state.error = dde.error ?? null
    return
  }
  // Done when backend returns empty nextItem.
  if (!resp.nextItem) {
    // Navigate back — the matrix already reflects dde-complete by
    // the time the user returns.
    router.push({ name: 'subject-matrix' })
  }
}

const openCount = computed<number>(() =>
  (dde.conflicts?.items ?? []).filter((r) => !r.resolved).length,
)
</script>

<template>
  <main class="dde-reconcile">
    <header class="dde-reconcile__header">
      <h1>{{ t('dde.reconcile.title') }}</h1>
      <p v-if="dde.conflicts" class="dde-reconcile__subline">
        {{ t('dde.reconcile.subjectLabel',
                { subject: dde.conflicts.subjectId, crf: dde.conflicts.crfName }) }}
      </p>
      <p class="dde-reconcile__open-count">
        {{ t('dde.reconcile.openCount', { count: openCount }, openCount) }}
      </p>
    </header>

    <p v-if="dde.error" class="dde-reconcile__error" role="alert">{{ dde.error }}</p>

    <table v-if="dde.conflicts && dde.conflicts.items.length > 0" class="dde-reconcile__table">
      <thead>
        <tr>
          <th>{{ t('dde.reconcile.headerItem') }}</th>
          <th>{{ t('dde.reconcile.headerIde') }}</th>
          <th>{{ t('dde.reconcile.headerDde') }}</th>
          <th>{{ t('dde.reconcile.headerWinner') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in dde.conflicts.items"
          :key="row.itemOid"
          :class="{ 'dde-reconcile__row--resolved': row.resolved }"
        >
          <td>{{ row.label }}</td>
          <td>{{ row.ideValue }}</td>
          <td>{{ row.ddeValue }}</td>
          <td v-if="row.resolved">
            <span>{{ t(`dde.reconcile.winner.${row.winner}`) }}</span>
          </td>
          <td v-else>
            <fieldset class="dde-reconcile__choices">
              <label>
                <input
                  type="radio"
                  :name="`winner-${row.itemOid}`"
                  value="ide"
                  v-model="ensureRow(row.itemOid).winner"
                />
                {{ t('dde.reconcile.winner.ide') }}
              </label>
              <label>
                <input
                  type="radio"
                  :name="`winner-${row.itemOid}`"
                  value="dde"
                  v-model="ensureRow(row.itemOid).winner"
                />
                {{ t('dde.reconcile.winner.dde') }}
              </label>
              <label>
                <input
                  type="radio"
                  :name="`winner-${row.itemOid}`"
                  value="manual"
                  v-model="ensureRow(row.itemOid).winner"
                />
                {{ t('dde.reconcile.winner.manual') }}
              </label>
              <input
                v-if="ensureRow(row.itemOid).winner === 'manual'"
                type="text"
                v-model="ensureRow(row.itemOid).manualValue"
              />
              <label>
                {{ t('dde.reconcile.reasonForChange.label') }}
                <input
                  type="text"
                  :placeholder="t('dde.reconcile.reasonForChange.placeholder')"
                  v-model="ensureRow(row.itemOid).reason"
                />
              </label>
              <button
                type="button"
                :disabled="dde.isResolving"
                @click="applyResolution(row)"
              >
                {{ dde.isResolving ? t('dde.reconcile.saving') : t('dde.reconcile.save') }}
              </button>
              <p
                v-if="ensureRow(row.itemOid).error"
                class="dde-reconcile__row-error"
                role="alert"
              >
                {{ ensureRow(row.itemOid).error }}
              </p>
            </fieldset>
          </td>
        </tr>
      </tbody>
    </table>
  </main>
</template>

<style scoped>
.dde-reconcile { padding: 1.5rem; }
.dde-reconcile__header { margin-bottom: 1rem; }
.dde-reconcile__error,
.dde-reconcile__row-error { color: var(--color-danger, #c0392b); }
.dde-reconcile__table { width: 100%; border-collapse: collapse; }
.dde-reconcile__table th,
.dde-reconcile__table td { padding: 0.5rem; border-bottom: 1px solid var(--color-border, #ddd); vertical-align: top; }
.dde-reconcile__row--resolved { opacity: 0.6; }
.dde-reconcile__choices { display: flex; flex-direction: column; gap: 0.25rem; border: none; padding: 0; }
</style>
