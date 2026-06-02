/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.5 RX.3b — body of {@code POST /api/v1/rule-sets/{id}/dry-run}.
 *
 * <p>One {@code Result} per (rule, subject-set) that the rule_set
 * would fire for. {@code subjects} is the list of subject OIDs that
 * match the rule's expression — empty when no current subject data
 * triggers the rule. {@code actionSummary} is a human-readable
 * preview ("Would create discrepancy note: '<msg>'", "Would insert
 * '<value>' into item '<oid>'", etc.) sourced from the legacy
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBasedViewContainer}
 * shape that the legacy {@code RunRuleSetServlet} also reads.
 *
 * <p>This endpoint is now safe to expose: the
 * {@code Show/Hide/Insert} processor fall-through bug was closed in
 * the same PR (see commits on Phase E.5 RX.3b).
 */
@Schema(name = "RuleSetDryRunResponse",
        description = "Per-rule preview of what a rule_set would do if run in SAVE mode.")
public record RuleSetDryRunResponse(
        int ruleSetId,
        String ruleSetTarget,
        List<Result> results
) {
    @Schema(name = "RuleSetDryRunResult")
    public record Result(
            String ruleName,
            String ruleOid,
            String expression,
            String executeOn,
            String actionType,
            String actionSummary,
            List<String> subjects
    ) {}
}
