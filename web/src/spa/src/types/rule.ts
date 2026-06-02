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
