<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'

import { useRulesStore } from '@/stores/rules'
import type { ActionType, RuleSet } from '@/types/rule'

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

function activePhases(gates: { administrativeDataEntry: boolean; initialDataEntry: boolean; doubleDataEntry: boolean; importDataEntry: boolean; batch: boolean }): string[] {
  const out: string[] = []
  if (gates.administrativeDataEntry) out.push(t('rules.phase.admin'))
  if (gates.initialDataEntry) out.push(t('rules.phase.initial'))
  if (gates.doubleDataEntry) out.push(t('rules.phase.double'))
  if (gates.importDataEntry) out.push(t('rules.phase.import'))
  if (gates.batch) out.push(t('rules.phase.batch'))
  return out
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.buildStudy') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 px-8 py-6">
      <div class="mb-4">
        <div class="text-xs text-slate-500 mb-1">{{ t('rules.subTrail') }}</div>
        <h1 class="text-xl font-semibold tracking-tight">{{ t('rules.title') }}</h1>
        <p class="text-xs text-slate-500 mt-1 max-w-2xl leading-relaxed">{{ t('rules.intro') }}</p>
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
              <div class="mt-1 text-[10px] text-slate-500">
                {{ t('rules.attachedCount', { count: rs.attachedRules.length }) }}
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
                <dd class="text-slate-800">{{ selectedRuleSet.runSchedule ? (selectedRuleSet.runTime ?? t('rules.detail.scheduledNoTime')) : t('rules.detail.notScheduled') }}</dd>
              </dl>
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
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
