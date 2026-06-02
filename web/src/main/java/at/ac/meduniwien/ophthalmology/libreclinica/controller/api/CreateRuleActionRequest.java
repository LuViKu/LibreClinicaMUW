/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Phase E RX.5 — request body for
 * {@code POST /api/v1/rule-sets/{id}/actions}.
 *
 * <p>Attaches one action to a {@code rule_set_rule} (an attached
 * rule) within the rule_set. The {@code ruleSetRuleId} must be one
 * of the rule_set_rule rows belonging to the rule_set in the path —
 * the controller cross-checks scope before persisting.
 *
 * <p>RX.5 scope cut: only four action types are creatable inline —
 * {@code FILE_DISCREPANCY_NOTE}, {@code EMAIL}, {@code SHOW},
 * {@code HIDE}. The other four ({@code INSERT}, {@code EVENT},
 * {@code NOTIFICATION}, {@code RANDOMIZE}) require the XML import
 * path; the controller returns 400 if an unsupported type is
 * submitted.
 *
 * <h2>Per-type field mapping</h2>
 * <ul>
 *   <li>{@code FILE_DISCREPANCY_NOTE} — only {@code message}</li>
 *   <li>{@code EMAIL} — {@code message} + {@code to}</li>
 *   <li>{@code SHOW} / {@code HIDE} — {@code message} + optional
 *       {@code properties[]} list of destination items</li>
 * </ul>
 *
 * <p>{@code expressionEvaluatesTo} defaults to {@code false} when
 * absent — i.e. the action fires when the rule expression evaluates
 * to FALSE. This matches the most common clinical-rule pattern (e.g.
 * "fire a discrepancy note when the validation expression doesn't
 * hold").
 *
 * <p>{@code phaseGates} is required — operator must explicitly pick
 * which data-entry phases the action fires in (no implicit default
 * mirrors the legacy XML import requirement at
 * {@code OCRERR_0050} which rejects an action with no enabled phase).
 */
@Schema(name = "CreateRuleActionRequest")
public record CreateRuleActionRequest(
        Integer ruleSetRuleId,
        String actionType,
        Boolean expressionEvaluatesTo,
        String message,
        String to,
        List<PropertyInput> properties,
        PhaseGatesInput phaseGates
) {
    /**
     * One destination property for SHOW / HIDE actions. Exactly one
     * of {@code value} / {@code valueExpression} must be present
     * (mirrors the legacy {@code PropertyBean} validation pattern).
     */
    @Schema(name = "CreateRuleActionRequest.PropertyInput")
    public record PropertyInput(
            String oid,
            String value,
            String valueExpression
    ) {}

    /**
     * The 5 phase-gate booleans that map to
     * {@code rule_action_run}'s columns. All required;
     * {@code null} treated as {@code false}.
     */
    @Schema(name = "CreateRuleActionRequest.PhaseGatesInput")
    public record PhaseGatesInput(
            Boolean administrativeDataEntry,
            Boolean initialDataEntry,
            Boolean doubleDataEntry,
            Boolean importDataEntry,
            Boolean batch
    ) {}
}
