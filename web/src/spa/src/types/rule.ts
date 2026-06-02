/**
 * Phase E RX.1 — read-only rules types.
 *
 * Wire shape returned by `GET /api/v1/rule-sets[/{id}]`. Mirrors
 * the backend `RuleSetDto` records.
 */

export type ActionType =
  | 'FILE_DISCREPANCY_NOTE'
  | 'EMAIL'
  | 'SHOW'
  | 'INSERT'
  | 'HIDE'
  | 'EVENT'
  | 'NOTIFICATION'
  | 'RANDOMIZE'
  | '' // safety for unmapped legacy rows

export interface PhaseGates {
  administrativeDataEntry: boolean
  initialDataEntry: boolean
  doubleDataEntry: boolean
  importDataEntry: boolean
  batch: boolean
}

export interface RuleAction {
  id: number
  actionType: ActionType
  expressionEvaluatesTo: boolean
  message: string | null
  /** Polymorphic per-type fields. See backend RuleActionDto javadoc. */
  typeSpecific: Record<string, unknown>
  phaseGates: PhaseGates
}

export interface AttachedRule {
  ruleSetRuleId: number
  ruleOid: string | null
  ruleName: string | null
  ruleDescription: string | null
  ruleExpression: string
  status: string
  actions: RuleAction[]
}

export interface RuleSet {
  id: number
  target: string
  studyEventDefinitionOid: string | null
  studyEventDefinitionName: string | null
  crfOid: string | null
  crfName: string | null
  crfVersionOid: string | null
  crfVersionName: string | null
  runSchedule: boolean
  runTime: string | null
  status: string
  attachedRules: AttachedRule[]
}

/**
 * Phase E RX.1b — one row of `rule_action_run_log`.
 *
 * The table has no timestamp column (see Liquibase changeset
 * `migration/amethyst/2010-01-13-4575.xml` -8), so `firedAt` is
 * always `null` today. The field stays in the wire contract so a
 * future schema migration can populate it without bumping the
 * contract.
 *
 * `itemDataId` is nullable because not every action type writes a
 * per-item-data row (e.g. NOTIFICATION).
 */
export interface RuleActionRunLogEntry {
  id: number
  actionType: ActionType
  ruleOid: string | null
  itemDataId: number | null
  value: string | null
  firedAt: string | null
}
