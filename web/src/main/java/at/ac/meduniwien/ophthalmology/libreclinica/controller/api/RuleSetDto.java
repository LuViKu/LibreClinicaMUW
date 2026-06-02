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
import java.util.Map;

/**
 * Phase E RX.1 — rule-set wire shape.
 *
 * <p>One row per {@code rule_set} entry. The {@code target} field
 * carries the rule-set's target expression (the OID path the rule
 * attaches to — typically the item whose value triggers evaluation).
 *
 * <p>{@code attachedRules} is the inlined set of {@code rule_set_rule}
 * rows; each carries its rule's identity + expression + the per-action
 * list. CRF-version-specific rule_sets carry their {@code crfVersion}
 * field; other rule_sets leave it null.
 *
 * <p>The {@code id} is the numeric {@code rule_set} primary key —
 * legacy {@code rule_set} table has no OID column (mirrors A8.6's
 * group-class decision: numeric id when the schema doesn't carry an
 * OID).
 */
@Schema(name = "RuleSetDto")
public record RuleSetDto(
        int id,
        String target,
        String studyEventDefinitionOid,
        String studyEventDefinitionName,
        String crfOid,
        String crfName,
        String crfVersionOid,
        String crfVersionName,
        boolean runSchedule,
        String runTime,
        String status,
        List<AttachedRuleDto> attachedRules
) {

    /**
     * One row per {@code rule_set_rule} entry. Inlines the
     * {@code rule} the row points at + the per-action list.
     *
     * <p>{@code ruleId} is the {@code rule.id} surrogate key — the
     * SPA needs it to call {@code PUT /api/v1/rules/{id}} for inline
     * edit (Phase E.5 RX.6b). Zero when the row's rule_bean is
     * detached (only happens in malformed legacy data).
     */
    @Schema(name = "AttachedRuleDto")
    public record AttachedRuleDto(
            int ruleSetRuleId,
            int ruleId,
            String ruleOid,
            String ruleName,
            String ruleDescription,
            String ruleExpression,
            String status,
            List<RuleActionDto> actions
    ) {}

    /**
     * One row per {@code rule_action}. Type-specific fields surface
     * as a polymorphic {@code typeSpecific} map — the SPA's per-type
     * rendering reads the {@code actionType} discriminator and picks
     * the right view.
     *
     * <p>Mapping from {@code actionType}:
     * <ul>
     *   <li>{@code FILE_DISCREPANCY_NOTE} — only {@code message}</li>
     *   <li>{@code EMAIL} — {@code message}, {@code to}</li>
     *   <li>{@code SHOW} / {@code HIDE} — {@code message},
     *       {@code destinationProperty} (OID), {@code runOnStatus}</li>
     *   <li>{@code INSERT} — list of {@code properties} (each
     *       {@code oid} + {@code value} / {@code valueExpression})</li>
     *   <li>{@code EVENT} — {@code targetEventOid}, {@code runOnStatus}</li>
     *   <li>{@code NOTIFICATION} — {@code to}, {@code subject}, {@code message}</li>
     *   <li>{@code RANDOMIZE} — list of {@code properties} +
     *       {@code stratificationFactors}</li>
     * </ul>
     *
     * <p>Read-only viewer (RX.1) projects whatever fields the bean
     * carries — the SPA renders best-effort. RX.5+ will surface CRUD
     * forms per type.
     */
    @Schema(name = "RuleActionDto")
    public record RuleActionDto(
            int id,
            String actionType,
            boolean expressionEvaluatesTo,
            String message,
            Map<String, Object> typeSpecific,
            PhaseGatesDto phaseGates
    ) {}

    /**
     * The 5-phase gate booleans on {@code rule_action_run}.
     */
    @Schema(name = "PhaseGatesDto")
    public record PhaseGatesDto(
            boolean administrativeDataEntry,
            boolean initialDataEntry,
            boolean doubleDataEntry,
            boolean importDataEntry,
            boolean batch
    ) {}
}
