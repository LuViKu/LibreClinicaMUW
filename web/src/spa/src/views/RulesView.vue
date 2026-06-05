<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import RulesImportDialog from '@/components/RulesImportDialog.vue'
import RuleAuthoringWizard from '@/components/RuleAuthoringWizard.vue'
import RuleEditDialog from '@/components/RuleEditDialog.vue'
import RuleActionEditDialog from '@/components/RuleActionEditDialog.vue'

import { useAuthStore } from '@/stores/auth'
import { useRulesStore, type TestExpressionResult } from '@/stores/rules'
import type { ActionType, AttachedRule, RuleAction, RuleSet } from '@/types/rule'

const RUN_LOG_PAGE_SIZE = 25

/**
 * Phase E RX.1 — read-only rules viewer.
 *
 * Operators today have no way to find out what rules attach to a
 * study without raw SQL or downloading the XML. This view surfaces
 * the rule_set graph as a list + detail pane.
 *
 * No mutations. RX.4 will add disable/restore/delete; RX.5 will add
 * inline create.
 */
const { t } = useI18n()
const rules = useRulesStore()
const auth = useAuthStore()

/**
 * Phase E RX.4 — lifecycle buttons gate to Administrator + Data
 * Manager. Backend re-checks via {@code StudyAdminAuthorization.
 * roleMayEditStudy} (sysadmin OR director/coordinator), so the SPA
 * gate is a UI hint — anyone bypassing it lands on a 403.
 */
const canManage = computed(() => {
  const role = auth.user?.role
  return role === 'Administrator' || role === 'Data Manager'
})

onMounted(() => { if (rules.rows.length === 0) rules.load() })

const selectedId = ref<number | null>(null)
const filter = ref('')

const filtered = computed(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) return rules.rows
  return rules.rows.filter((r) => {
    return (
      r.target.toLowerCase().includes(q) ||
      (r.studyEventDefinitionName ?? '').toLowerCase().includes(q) ||
      (r.crfName ?? '').toLowerCase().includes(q) ||
      r.attachedRules.some((a) =>
        (a.ruleOid ?? '').toLowerCase().includes(q) ||
        (a.ruleName ?? '').toLowerCase().includes(q) ||
        a.ruleExpression.toLowerCase().includes(q),
      )
    )
  })
})

const selectedRuleSet = computed(() =>
  selectedId.value == null
    ? null
    : rules.rows.find((r) => r.id === selectedId.value) ?? null,
)

function select(rs: RuleSet) {
  selectedId.value = rs.id
}

/**
 * Phase E RX.1b — hydrate the run-log when a rule_set is picked. The
 * server returns at most `RUN_LOG_PAGE_SIZE` entries per call; the
 * "Load more" button below fetches subsequent pages.
 *
 * The `runLogPageHasMore` heuristic ("last page came back full →
 * probably more") is the cheapest approach absent a server-side
 * total-count surface. False positives (exactly N entries) cause one
 * extra empty page; acceptable for an audit panel.
 */
const lastPageSize = ref(0)
const runLogPageHasMore = computed(() => lastPageSize.value >= RUN_LOG_PAGE_SIZE)

watch(selectedId, async (id) => {
  if (id == null) return
  const page = await rules.fetchRunLog(id, RUN_LOG_PAGE_SIZE, 0)
  lastPageSize.value = page.length
})

async function loadMoreRunLog() {
  if (selectedId.value == null) return
  const page = await rules.fetchRunLog(
    selectedId.value,
    RUN_LOG_PAGE_SIZE,
    rules.runLog.length,
  )
  lastPageSize.value = page.length
}

function actionTypeBadgeVariant(type: ActionType): 'info' | 'warning' | 'success' | 'neutral' {
  switch (type) {
    case 'FILE_DISCREPANCY_NOTE': return 'warning'
    case 'EMAIL':
    case 'NOTIFICATION': return 'info'
    case 'SHOW':
    case 'HIDE': return 'neutral'
    case 'INSERT':
    case 'EVENT':
    case 'RANDOMIZE': return 'success'
    default: return 'neutral'
  }
}

/** Phase E.5 RX.6b — gate on the backend's inline-edit envelope. */
function actionIsEditable(type: ActionType): boolean {
  return (
    type === 'FILE_DISCREPANCY_NOTE' ||
    type === 'EMAIL' ||
    type === 'SHOW' ||
    type === 'HIDE'
  )
}

function activePhases(gates: { administrativeDataEntry: boolean; initialDataEntry: boolean; doubleDataEntry: boolean; importDataEntry: boolean; batch: boolean }): string[] {
  const out: string[] = []
  if (gates.administrativeDataEntry) out.push(t('rules.phase.admin'))
  if (gates.initialDataEntry) out.push(t('rules.phase.initial'))
  if (gates.doubleDataEntry) out.push(t('rules.phase.double'))
  if (gates.importDataEntry) out.push(t('rules.phase.import'))
  if (gates.batch) out.push(t('rules.phase.batch'))
  return out
}

/* -------------------------------------------------------------- */
/* RX.3 — Test rule pane                                            */
/* -------------------------------------------------------------- */

/**
 * Per-row state for the test-values key/value editor. Local IDs
 * (rather than relying on array index as the v-for key) make
 * "Remove row" non-destructive: removing row 1 of a 3-row form
 * doesn't re-render rows 2/3 with stale focus/blur state.
 */
interface TestValueRow { id: number; key: string; value: string }
let nextRowId = 0
function makeRow(key = '', value = ''): TestValueRow {
  return { id: nextRowId++, key, value }
}

const testExpression = ref('')
const testValueRows = ref<TestValueRow[]>([makeRow(), makeRow()])
const testRunning = ref(false)
const testResult = ref<TestExpressionResult | null>(null)

function addTestValueRow() {
  testValueRows.value.push(makeRow())
}

function removeTestValueRow(id: number) {
  if (testValueRows.value.length <= 1) return
  testValueRows.value = testValueRows.value.filter((r) => r.id !== id)
}

async function runTest() {
  if (testRunning.value) return
  const trimmedExpression = testExpression.value.trim()
  if (trimmedExpression.length === 0) {
    // Surface the same error shape the server would, so the UI
    // doesn't need a second branch — empty-input feedback rides
    // on the same render path as a 400 from the backend. The
    // backend would respond with the same "must not be blank"
    // string, so we copy it here rather than introducing a one-off
    // i18n key for client-side preflight.
    testResult.value = { ok: false, message: 'Expression must not be blank' }
    return
  }

  const values: Record<string, string> = {}
  for (const row of testValueRows.value) {
    const key = row.key.trim()
    if (key.length === 0) continue
    values[key] = row.value
  }

  testRunning.value = true
  try {
    testResult.value = await rules.testExpression(trimmedExpression, values)
  } finally {
    testRunning.value = false
  }
}

/**
 * Result-pill variant — TRUE is a "success" highlight, FALSE is
 * neutral (not an error — false is a legitimate evaluation,
 * different from a parse failure).
 */
const resultPillVariant = computed<'success' | 'neutral' | 'warning'>(() => {
  const r = testResult.value
  if (!r || !r.ok) return 'warning'
  if (r.result === 'true') return 'success'
  return 'neutral'
})

const resultLabel = computed(() => {
  const r = testResult.value
  if (!r || !r.ok) return ''
  if (r.result === 'true') return t('rules.test.resultTrue')
  if (r.result === 'false') return t('rules.test.resultFalse')
  return t('rules.test.resultOther', { value: r.result })
})

// Reset the test pane when the user switches rule_sets — the
// expression and mock values were specific to the previous
// selection, so carrying them over is more surprising than
// helpful.
watch(selectedId, () => {
  testExpression.value = ''
  testValueRows.value = [makeRow(), makeRow()]
  testResult.value = null
})

/* -------------------------------------------------------------- */
/* RX.4 — lifecycle actions                                         */
/* -------------------------------------------------------------- */

/**
 * One-shot busy flag per row so the SPA can dim a single button
 * without freezing the whole grid. Keyed by a stable composite of
 * (rule_set_id, optional rule_set_rule_id).
 */
const busyKey = ref<string | null>(null)

function makeBusyKey(ruleSetId: number, ruleSetRuleId?: number): string {
  return ruleSetRuleId == null ? `rs:${ruleSetId}` : `rsr:${ruleSetId}:${ruleSetRuleId}`
}

async function onDisableRuleSet(rs: RuleSet) {
  const target = rs.target || `#${rs.id}`
  // Native confirm() — the existing CrfLibrary / Sites views use the
  // same pattern; a heavier modal can come later. The message warns
  // about audit-trail retention to match the legacy "are you sure"
  // page (verifyOperationServlet).
  if (!window.confirm(t('rules.confirm.disableRuleSet', { target }))) return
  const key = makeBusyKey(rs.id)
  busyKey.value = key
  try {
    await rules.disableRuleSet(rs.id)
  } finally {
    if (busyKey.value === key) busyKey.value = null
  }
}

async function onRestoreRuleSet(rs: RuleSet) {
  const key = makeBusyKey(rs.id)
  busyKey.value = key
  try {
    await rules.restoreRuleSet(rs.id)
  } finally {
    if (busyKey.value === key) busyKey.value = null
  }
}

async function onDisableAttachedRule(rs: RuleSet, ar: AttachedRule) {
  const ruleOid = ar.ruleOid ?? ar.ruleName ?? `#${ar.ruleSetRuleId}`
  if (!window.confirm(t('rules.confirm.disableRule', { ruleOid }))) return
  const key = makeBusyKey(rs.id, ar.ruleSetRuleId)
  busyKey.value = key
  try {
    await rules.disableAttachedRule(rs.id, ar.ruleSetRuleId)
  } finally {
    if (busyKey.value === key) busyKey.value = null
  }
}

async function onRestoreAttachedRule(rs: RuleSet, ar: AttachedRule) {
  const key = makeBusyKey(rs.id, ar.ruleSetRuleId)
  busyKey.value = key
  try {
    await rules.restoreAttachedRule(rs.id, ar.ruleSetRuleId)
  } finally {
    if (busyKey.value === key) busyKey.value = null
  }
}

/* -------------------------------------------------------------- */
/* RX.2 — Import rules dialog                                       */
/* -------------------------------------------------------------- */

const importDialogOpen = ref(false)
function openImportDialog() { importDialogOpen.value = true }
function onImportCommitted() {
  // Reload the list so the freshly committed rule_sets appear.
  rules.load()
}

/* -------------------------------------------------------------- */
/* RX.5b — Rule authoring wizard                                    */
/* -------------------------------------------------------------- */

/**
 * RX.5b — 3-step inline authoring (rule body, target+scope, action).
 * The wizard's `createRuleSet` action appends to `rules.rows` as
 * soon as step 2 lands, so the new row is visible without waiting
 * for the post-wizard reload. The reload below reconciles the
 * cascade-persisted action ids on top of that.
 */
const createWizardOpen = ref(false)
function openCreateWizard() { createWizardOpen.value = true }

/* -------------------------------------------------------------- */
/* RX.6b — Inline edit dialogs (rule + action)                      */
/* -------------------------------------------------------------- */

const ruleEditOpen = ref(false)
const ruleEditTarget = ref<AttachedRule | null>(null)
function openRuleEdit(ar: AttachedRule) {
  ruleEditTarget.value = ar
  ruleEditOpen.value = true
}
function onRuleEditSaved() {
  // Store call already triggers fetchOne(selected.id) on success;
  // no extra refresh needed here.
}

const actionEditOpen = ref(false)
const actionEditTarget = ref<RuleAction | null>(null)
function openActionEdit(action: RuleAction) {
  actionEditTarget.value = action
  actionEditOpen.value = true
}
function onActionEditSaved() {
  // Store call already triggers fetchOne(selected.id) on success.
}
function onCreateWizardDone() {
  rules.load()
}

/* -------------------------------------------------------------- */
/* RX.7 — Schedule edit                                             */
/* -------------------------------------------------------------- */

/**
 * One-shot inline form per detail pane. Open / closed state lives
 * here rather than in the store because nothing else needs to read
 * it. When the user switches rule_sets the form closes (watch below)
 * so a half-edited schedule doesn't leak onto a different target.
 */
const scheduleFormOpen = ref(false)
const scheduleFormRunSchedule = ref(false)
const scheduleFormRunTime = ref('')
const scheduleSaveError = ref<string | null>(null)
const scheduleSaving = ref(false)

function openScheduleForm(rs: RuleSet) {
  scheduleFormRunSchedule.value = rs.runSchedule
  // Default to a sensible time when scheduling is freshly being
  // turned on but the bean had nothing stored — "08:00" is the time
  // the legacy operator runbook nudges towards, matching the morning
  // batch window most studies pick.
  scheduleFormRunTime.value = rs.runTime ?? '08:00'
  scheduleSaveError.value = null
  scheduleFormOpen.value = true
}

function cancelScheduleForm() {
  scheduleFormOpen.value = false
  scheduleSaveError.value = null
}

async function saveSchedule(rs: RuleSet) {
  if (scheduleSaving.value) return
  scheduleSaveError.value = null
  // Client-side preflight matches the backend's "runTime required
  // when scheduling is on" gate, so the user gets immediate feedback
  // without a round-trip.
  if (scheduleFormRunSchedule.value && scheduleFormRunTime.value.trim().length === 0) {
    scheduleSaveError.value = t('rules.schedule.saveError.timeRequired')
    return
  }
  scheduleSaving.value = true
  try {
    const result = await rules.setSchedule(
      rs.id,
      scheduleFormRunSchedule.value,
      scheduleFormRunSchedule.value ? scheduleFormRunTime.value.trim() : null,
    )
    if (result.ok) {
      scheduleFormOpen.value = false
    } else {
      scheduleSaveError.value = result.message
    }
  } finally {
    scheduleSaving.value = false
  }
}

// Close the schedule form when the user switches rule_sets — same
// rationale as the test pane reset below: form state belonged to the
// previous selection.
watch(selectedId, () => {
  scheduleFormOpen.value = false
  scheduleSaveError.value = null
})

/* -------------------------------------------------------------- */
/* Phase E.6 restore-quickwins — Dry-run + Download XML            */
/* -------------------------------------------------------------- */

/**
 * Dry-run preview state. The {@code results} array holds whichever
 * action rows the backend produced; {@code null} means "no dry-run
 * has been run for this selection yet". Empty array surfaces the
 * {@code rules.dryRun.empty} i18n key.
 */
import type { DryRunActionResult } from '@/stores/rules'
const dryRunBusy = ref(false)
const dryRunError = ref<string | null>(null)
const dryRunResults = ref<DryRunActionResult[] | null>(null)
const exportBusy = ref(false)

async function onDryRun() {
  if (!selectedRuleSet.value || dryRunBusy.value) return
  dryRunBusy.value = true
  dryRunError.value = null
  try {
    const result = await rules.dryRunRuleSet(selectedRuleSet.value.id)
    if (result.ok) {
      dryRunResults.value = result.response.results
    } else {
      dryRunError.value = result.message
      dryRunResults.value = null
    }
  } finally {
    dryRunBusy.value = false
  }
}

/**
 * Hit the XML export endpoint for the current selection. The backend
 * accepts a comma-list of {@code rule_set_rule} ids; we feed it every
 * attached rule on the selected rule_set so the operator gets the
 * full set in one click. The store wraps the URL in an anchor click
 * so the SPA page stays put while the browser handles the byte stream.
 */
function onExportXml() {
  if (!selectedRuleSet.value || exportBusy.value) return
  const ids = selectedRuleSet.value.attachedRules.map((ar) => ar.ruleSetRuleId)
  if (ids.length === 0) return
  exportBusy.value = true
  try {
    rules.exportRulesXml(ids)
  } finally {
    exportBusy.value = false
  }
}

// Clear dry-run state on selection change so a previous selection's
// preview doesn't bleed into the new pane.
watch(selectedId, () => {
  dryRunResults.value = null
  dryRunError.value = null
})
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.buildStudy') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 px-8 py-6">
      <div class="mb-4 flex items-start justify-between gap-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('rules.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('rules.title') }}</h1>
          <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('rules.intro') }}</p>
        </div>
        <div v-if="canManage" class="shrink-0 flex items-center gap-2">
          <!--
            RX.5b — "New rule" entry point for the 3-step authoring
            wizard. Same Administrator + Data Manager gate as the
            other lifecycle buttons; backend re-checks via
            StudyAdminAuthorization on each step's endpoint, so the
            SPA gate is a UI hint.
          -->
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium bg-muw-blue text-white rounded-md hover:opacity-90"
            @click="openCreateWizard"
          >
            {{ t('rules.create.button.new') }}
          </button>
          <!--
            RX.2 — "Import rules" button. Gated to Administrator + Data
            Manager via the same canManage gate as the disable/restore
            actions. Backend re-checks via StudyAdminAuthorization, so
            the SPA gate is a UI hint.
          -->
          <button
            type="button"
            class="px-3 py-1.5 text-xs font-medium border border-muw-blue text-muw-blue bg-white rounded-md hover:bg-muw-blue-50"
            @click="openImportDialog"
          >
            {{ t('rules.import.button') }}
          </button>
        </div>
      </div>

      <p v-if="rules.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="rules.error" class="text-rose-700">{{ rules.error }}</p>
      <p v-else-if="rules.rows.length === 0" class="text-slate-500 italic">{{ t('rules.empty') }}</p>

      <div v-else class="grid grid-cols-[minmax(0,2fr)_minmax(0,3fr)] gap-4">
        <!-- Left: list -->
        <section class="space-y-2">
          <input
            v-model="filter"
            type="search"
            class="w-full px-3 py-1.5 text-xs border border-slate-200 rounded-md"
            :placeholder="t('rules.searchPlaceholder')"
          />
          <p class="text-xs text-slate-500">{{ t('rules.showingCount', { visible: filtered.length, total: rules.rows.length }) }}</p>
          <ul class="space-y-1.5 max-h-[70vh] overflow-y-auto">
            <li
              v-for="rs in filtered"
              :key="rs.id"
              :class="[
                'rounded-md border bg-white p-3 cursor-pointer transition-colors',
                rs.id === selectedId
                  ? 'border-muw-blue bg-muw-blue-50'
                  : 'border-slate-200 hover:bg-slate-50',
              ]"
              @click="select(rs)"
            >
              <div class="flex items-baseline gap-2">
                <code class="text-xs font-mono text-slate-700 truncate flex-1">{{ rs.target || '—' }}</code>
                <StatusPill v-if="rs.status === 'removed'" variant="neutral">{{ t('rules.statusRemoved') }}</StatusPill>
              </div>
              <div class="mt-1 flex items-center gap-1.5 flex-wrap text-[10px] text-slate-500">
                <span v-if="rs.studyEventDefinitionName">{{ rs.studyEventDefinitionName }}</span>
                <span v-if="rs.crfName">· {{ rs.crfName }}</span>
                <span v-if="rs.crfVersionName">· {{ rs.crfVersionName }}</span>
              </div>
              <div class="mt-1 flex items-center gap-2">
                <span class="text-[10px] text-slate-500 flex-1">
                  {{ t('rules.attachedCount', { count: rs.attachedRules.length }) }}
                </span>
                <button
                  v-if="canManage && rs.status === 'removed'"
                  type="button"
                  class="text-[10px] px-2 py-0.5 border border-slate-300 rounded text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                  :disabled="busyKey === makeBusyKey(rs.id)"
                  @click.stop="onRestoreRuleSet(rs)"
                >
                  {{ t('rules.action.restoreRuleSet') }}
                </button>
                <button
                  v-else-if="canManage"
                  type="button"
                  class="text-[10px] px-2 py-0.5 border border-rose-200 rounded text-rose-700 hover:bg-rose-50 disabled:opacity-50"
                  :disabled="busyKey === makeBusyKey(rs.id)"
                  @click.stop="onDisableRuleSet(rs)"
                >
                  {{ t('rules.action.disableRuleSet') }}
                </button>
              </div>
            </li>
          </ul>
        </section>

        <!-- Right: detail -->
        <section>
          <div v-if="!selectedRuleSet" class="rounded-md border border-dashed border-slate-200 p-6 text-xs text-slate-500 italic">
            {{ t('rules.pickPrompt') }}
          </div>

          <div v-else class="rounded-md border border-slate-200 bg-white p-4 space-y-4">
            <div>
              <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">{{ t('rules.detail.targetHeading') }}</div>
              <code class="text-sm font-mono text-slate-800 block mt-1 break-all">{{ selectedRuleSet.target || '—' }}</code>
              <dl class="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs">
                <dt class="text-slate-500">{{ t('rules.detail.sed') }}</dt>
                <dd class="text-slate-800">{{ selectedRuleSet.studyEventDefinitionName ?? '—' }}</dd>
                <dt class="text-slate-500">{{ t('rules.detail.crf') }}</dt>
                <dd class="text-slate-800">{{ selectedRuleSet.crfName ?? '—' }}</dd>
                <dt class="text-slate-500">{{ t('rules.detail.crfVersion') }}</dt>
                <dd class="text-slate-800">{{ selectedRuleSet.crfVersionName ?? '—' }}</dd>
                <dt class="text-slate-500">{{ t('rules.detail.runSchedule') }}</dt>
                <dd class="text-slate-800 flex items-center gap-2 flex-wrap">
                  <span>{{ selectedRuleSet.runSchedule ? (selectedRuleSet.runTime ?? t('rules.detail.scheduledNoTime')) : t('rules.detail.notScheduled') }}</span>
                  <!--
                    RX.7 — inline schedule edit entry. Gated to
                    Administrator + Data Manager via canManage;
                    backend re-checks via StudyAdminAuthorization.
                  -->
                  <button
                    v-if="canManage && !scheduleFormOpen"
                    type="button"
                    class="text-[10px] px-2 py-0.5 border border-slate-300 rounded text-muw-blue hover:bg-muw-blue-50"
                    @click="openScheduleForm(selectedRuleSet)"
                  >
                    {{ t('rules.schedule.editButton') }}
                  </button>
                </dd>
              </dl>

              <!-- RX.7 — inline schedule edit form -->
              <div
                v-if="scheduleFormOpen"
                class="mt-3 rounded-md border border-slate-200 bg-slate-50 p-3 space-y-2"
              >
                <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                  {{ t('rules.schedule.heading') }}
                </div>
                <label class="flex items-center gap-2 text-xs text-slate-700">
                  <input
                    v-model="scheduleFormRunSchedule"
                    type="checkbox"
                    class="rounded border-slate-300"
                  />
                  {{ t('rules.schedule.runScheduleLabel') }}
                </label>
                <div>
                  <label
                    class="block text-[11px] text-slate-600 mb-1"
                    :for="`schedule-run-time-${selectedRuleSet.id}`"
                  >
                    {{ t('rules.schedule.runTimeLabel') }}
                  </label>
                  <input
                    :id="`schedule-run-time-${selectedRuleSet.id}`"
                    v-model="scheduleFormRunTime"
                    type="time"
                    :disabled="!scheduleFormRunSchedule"
                    class="px-2 py-1 text-xs border border-slate-200 rounded-md disabled:bg-slate-100 disabled:text-slate-400"
                  />
                  <p class="mt-1 text-[10px] text-slate-500">{{ t('rules.schedule.timeFormatHint') }}</p>
                </div>
                <p v-if="scheduleSaveError" class="text-xs text-rose-700">{{ scheduleSaveError }}</p>
                <div class="flex items-center gap-2">
                  <button
                    type="button"
                    class="px-3 py-1 text-xs font-medium border border-muw-blue text-muw-blue bg-white rounded-md hover:bg-muw-blue-50 disabled:opacity-50"
                    :disabled="scheduleSaving"
                    @click="saveSchedule(selectedRuleSet)"
                  >
                    {{ t('rules.schedule.saveButton') }}
                  </button>
                  <button
                    type="button"
                    class="px-3 py-1 text-xs border border-slate-300 text-slate-700 bg-white rounded-md hover:bg-slate-50"
                    :disabled="scheduleSaving"
                    @click="cancelScheduleForm"
                  >
                    {{ t('rules.schedule.cancelButton') }}
                  </button>
                </div>
              </div>
            </div>

            <div>
              <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                {{ t('rules.detail.attachedRulesHeading', { count: selectedRuleSet.attachedRules.length }) }}
              </div>
              <ul class="mt-2 space-y-2">
                <li
                  v-for="ar in selectedRuleSet.attachedRules"
                  :key="ar.ruleSetRuleId"
                  class="rounded-md border border-slate-200 p-3"
                >
                  <div class="flex items-baseline gap-2 flex-wrap">
                    <span class="font-medium text-slate-800">{{ ar.ruleName ?? '—' }}</span>
                    <span class="font-mono text-[10px] text-slate-400">{{ ar.ruleOid }}</span>
                    <StatusPill v-if="ar.status === 'removed'" variant="neutral">{{ t('rules.statusRemoved') }}</StatusPill>
                    <span class="flex-1"></span>
                    <button
                      v-if="canManage && ar.status === 'available'"
                      type="button"
                      class="text-[10px] px-2 py-0.5 border border-slate-300 rounded text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                      @click="openRuleEdit(ar)"
                    >
                      {{ t('rules.action.editRule') }}
                    </button>
                    <button
                      v-if="canManage && ar.status === 'removed'"
                      type="button"
                      class="text-[10px] px-2 py-0.5 border border-slate-300 rounded text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                      :disabled="busyKey === makeBusyKey(selectedRuleSet.id, ar.ruleSetRuleId)"
                      @click="onRestoreAttachedRule(selectedRuleSet, ar)"
                    >
                      {{ t('rules.action.restoreRule') }}
                    </button>
                    <button
                      v-else-if="canManage"
                      type="button"
                      class="text-[10px] px-2 py-0.5 border border-rose-200 rounded text-rose-700 hover:bg-rose-50 disabled:opacity-50"
                      :disabled="busyKey === makeBusyKey(selectedRuleSet.id, ar.ruleSetRuleId)"
                      @click="onDisableAttachedRule(selectedRuleSet, ar)"
                    >
                      {{ t('rules.action.disableRule') }}
                    </button>
                  </div>
                  <p v-if="ar.ruleDescription" class="text-xs text-slate-500 mt-1">{{ ar.ruleDescription }}</p>
                  <code class="block mt-2 text-xs font-mono bg-slate-50 border border-slate-200 rounded p-2 text-slate-800 break-all">{{ ar.ruleExpression || '—' }}</code>
                  <ul v-if="ar.actions.length > 0" class="mt-2 space-y-1.5">
                    <li
                      v-for="action in ar.actions"
                      :key="action.id"
                      class="text-xs border-l-2 border-slate-200 pl-2"
                    >
                      <div class="flex items-center gap-2 flex-wrap">
                        <StatusPill :variant="actionTypeBadgeVariant(action.actionType)">{{ t(`rules.actionType.${action.actionType}`) }}</StatusPill>
                        <span class="text-slate-500">{{ t(action.expressionEvaluatesTo ? 'rules.detail.firesWhenTrue' : 'rules.detail.firesWhenFalse') }}</span>
                        <span class="flex-1"></span>
                        <button
                          v-if="canManage && actionIsEditable(action.actionType)"
                          type="button"
                          class="text-[10px] px-2 py-0.5 border border-slate-300 rounded text-slate-700 hover:bg-slate-50"
                          @click="openActionEdit(action)"
                        >
                          {{ t('rules.action.editAction') }}
                        </button>
                      </div>
                      <p v-if="action.message" class="mt-1 text-slate-700">{{ action.message }}</p>
                      <div v-if="activePhases(action.phaseGates).length > 0" class="mt-1 text-[10px] text-slate-500">
                        {{ t('rules.detail.activeIn') }}: {{ activePhases(action.phaseGates).join(', ') }}
                      </div>
                      <details v-if="Object.keys(action.typeSpecific).length > 0" class="mt-1">
                        <summary class="text-[10px] text-muw-blue cursor-pointer">{{ t('rules.detail.showRaw') }}</summary>
                        <pre class="text-[10px] text-slate-600 mt-1 whitespace-pre-wrap">{{ JSON.stringify(action.typeSpecific, null, 2) }}</pre>
                      </details>
                    </li>
                  </ul>
                </li>
              </ul>
            </div>

            <!-- Phase E.6 restore-quickwins — Dry-run + Download XML -->
            <div v-if="canManage" class="border-t border-slate-100 pt-3">
              <div class="flex items-center gap-2 flex-wrap">
                <button
                  type="button"
                  class="px-3 py-1.5 text-xs font-medium border border-muw-blue text-muw-blue bg-white rounded-md hover:bg-muw-blue-50 disabled:opacity-50"
                  data-testid="rules-dryrun-button"
                  :disabled="dryRunBusy || selectedRuleSet.attachedRules.length === 0"
                  @click="onDryRun"
                >
                  {{ dryRunBusy ? t('common.loading') : t('rules.action.dryRun') }}
                </button>
                <button
                  type="button"
                  class="px-3 py-1.5 text-xs font-medium border border-slate-300 text-slate-700 bg-white rounded-md hover:bg-slate-50 disabled:opacity-50"
                  data-testid="rules-export-xml-button"
                  :disabled="exportBusy || selectedRuleSet.attachedRules.length === 0"
                  @click="onExportXml"
                >
                  {{ t('rules.action.exportXml') }}
                </button>
              </div>
              <p v-if="dryRunError" class="mt-2 text-xs text-rose-700">{{ dryRunError }}</p>
              <div v-if="dryRunResults !== null" class="mt-3 rounded-md bg-slate-50 border border-slate-200 p-3">
                <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                  {{ t('rules.dryRun.heading') }}
                </div>
                <p
                  v-if="dryRunResults.length === 0"
                  class="mt-2 text-xs text-slate-500 italic"
                  data-testid="rules-dryrun-empty"
                >
                  {{ t('rules.dryRun.empty') }}
                </p>
                <ul v-else class="mt-2 space-y-2">
                  <li
                    v-for="(r, idx) in dryRunResults"
                    :key="`${r.ruleOid}-${idx}`"
                    class="rounded-md border border-slate-200 bg-white p-2 text-xs"
                  >
                    <div class="flex items-baseline gap-2 flex-wrap">
                      <code class="font-mono text-slate-800 break-all">{{ r.ruleOid }}</code>
                      <span class="text-slate-500">{{ r.ruleName }}</span>
                      <StatusPill variant="neutral">{{ r.actionType }}</StatusPill>
                    </div>
                    <div class="mt-1 text-[11px] text-slate-700">{{ r.actionSummary }}</div>
                    <div v-if="r.subjects.length > 0" class="mt-1 text-[10px] text-slate-500">
                      {{ t('rules.dryRun.subjects', { count: r.subjects.length }) }}:
                      <span class="font-mono">{{ r.subjects.slice(0, 8).join(', ') }}<span v-if="r.subjects.length > 8">, …</span></span>
                    </div>
                  </li>
                </ul>
              </div>
            </div>

            <!-- RX.1b — fire history (rule_action_run_log) -->
            <div>
              <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                {{ t('rules.detail.runLogHeading', { count: rules.runLog.length }) }}
              </div>
              <p v-if="rules.isLoadingRunLog && rules.runLog.length === 0" class="mt-2 text-xs text-slate-500 italic">{{ t('common.loading') }}</p>
              <p v-else-if="rules.runLogError" class="mt-2 text-xs text-rose-700">{{ rules.runLogError }}</p>
              <p v-else-if="rules.runLog.length === 0" class="mt-2 text-xs text-slate-500 italic">{{ t('rules.detail.runLogEmpty') }}</p>
              <div v-else class="mt-2 overflow-x-auto">
                <table class="w-full text-xs">
                  <thead>
                    <tr class="text-left text-[10px] uppercase tracking-wider text-slate-500">
                      <th class="px-2 py-1 font-semibold">{{ t('rules.detail.runLogColumn.action') }}</th>
                      <th class="px-2 py-1 font-semibold">{{ t('rules.detail.runLogColumn.rule') }}</th>
                      <th class="px-2 py-1 font-semibold">{{ t('rules.detail.runLogColumn.value') }}</th>
                      <th class="px-2 py-1 font-semibold">{{ t('rules.detail.runLogColumn.when') }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="entry in rules.runLog" :key="entry.id" class="border-t border-slate-100">
                      <td class="px-2 py-1.5">
                        <StatusPill :variant="actionTypeBadgeVariant(entry.actionType)">{{ t(`rules.actionType.${entry.actionType}`) }}</StatusPill>
                      </td>
                      <td class="px-2 py-1.5 font-mono text-[10px] text-slate-500 break-all">{{ entry.ruleOid ?? '—' }}</td>
                      <td class="px-2 py-1.5 text-slate-700 break-all">{{ entry.value ?? '—' }}</td>
                      <td class="px-2 py-1.5 text-slate-500">{{ entry.firedAt ?? '—' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <button
                v-if="runLogPageHasMore"
                type="button"
                class="mt-2 px-3 py-1 text-xs border border-slate-200 rounded-md hover:bg-slate-50 disabled:opacity-50"
                :disabled="rules.isLoadingRunLog"
                @click="loadMoreRunLog"
              >
                {{ t('rules.detail.runLogLoadMore') }}
              </button>
            </div>

            <!-- RX.3 — Test rule pane -->
            <div>
              <div class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">
                {{ t('rules.test.heading') }}
              </div>
              <p class="mt-1 text-[11px] text-slate-500 leading-relaxed">
                {{ t('rules.test.intro') }}
              </p>

              <div class="mt-3 space-y-3">
                <div>
                  <label class="block text-[11px] text-slate-600 mb-1" :for="`test-expr-${selectedRuleSet.id}`">
                    {{ t('rules.test.expressionLabel') }}
                  </label>
                  <textarea
                    :id="`test-expr-${selectedRuleSet.id}`"
                    v-model="testExpression"
                    rows="3"
                    class="w-full px-2 py-1.5 text-xs font-mono border border-slate-200 rounded-md"
                    :placeholder="t('rules.test.expressionPlaceholder')"
                  />
                </div>

                <div>
                  <div class="text-[11px] text-slate-600 mb-1">{{ t('rules.test.testValuesLabel') }}</div>
                  <div class="space-y-1.5">
                    <div
                      v-for="row in testValueRows"
                      :key="row.id"
                      class="flex items-center gap-1.5"
                    >
                      <input
                        v-model="row.key"
                        type="text"
                        class="flex-1 px-2 py-1 text-xs font-mono border border-slate-200 rounded-md"
                        :placeholder="t('rules.test.varNamePlaceholder')"
                        :aria-label="t('rules.test.varName')"
                      />
                      <input
                        v-model="row.value"
                        type="text"
                        class="flex-1 px-2 py-1 text-xs border border-slate-200 rounded-md"
                        :placeholder="t('rules.test.valuePlaceholder')"
                        :aria-label="t('rules.test.value')"
                      />
                      <button
                        v-if="testValueRows.length > 1"
                        type="button"
                        class="px-2 py-1 text-[10px] text-slate-500 hover:text-rose-700"
                        @click="removeTestValueRow(row.id)"
                      >
                        {{ t('rules.test.removeRow') }}
                      </button>
                    </div>
                  </div>
                  <button
                    type="button"
                    class="mt-1.5 px-2 py-1 text-[11px] border border-slate-200 rounded-md hover:bg-slate-50"
                    @click="addTestValueRow"
                  >
                    {{ t('rules.test.addRow') }}
                  </button>
                </div>

                <div>
                  <button
                    type="button"
                    class="px-3 py-1.5 text-xs font-medium border border-muw-blue text-muw-blue bg-white rounded-md hover:bg-muw-blue-50 disabled:opacity-50"
                    :disabled="testRunning"
                    @click="runTest"
                  >
                    {{ testRunning ? t('rules.test.running') : t('rules.test.runButton') }}
                  </button>
                </div>

                <div v-if="testResult" class="rounded-md border p-2.5"
                     :class="testResult.ok ? 'border-slate-200 bg-slate-50' : 'border-rose-200 bg-rose-50'">
                  <div v-if="testResult.ok" class="flex items-center gap-2">
                    <span class="text-[10px] uppercase tracking-wider text-slate-500 font-semibold">{{ t('rules.test.resultHeading') }}</span>
                    <StatusPill :variant="resultPillVariant">{{ resultLabel }}</StatusPill>
                  </div>
                  <div v-else>
                    <div class="text-[10px] uppercase tracking-wider text-rose-700 font-semibold">
                      {{ t('rules.test.errorHeading') }}
                    </div>
                    <p class="mt-1 text-xs font-mono text-rose-800 whitespace-pre-wrap">{{ testResult.message }}</p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>
    </main>

    <RulesImportDialog
      v-model:open="importDialogOpen"
      @committed="onImportCommitted"
    />

    <RuleAuthoringWizard
      v-model:open="createWizardOpen"
      @done="onCreateWizardDone"
    />

    <RuleEditDialog
      v-model:open="ruleEditOpen"
      :rule="ruleEditTarget"
      @saved="onRuleEditSaved"
    />

    <RuleActionEditDialog
      v-model:open="actionEditOpen"
      :rule-set="selectedRuleSet"
      :action="actionEditTarget"
      @saved="onActionEditSaved"
    />
  </div>
</template>
