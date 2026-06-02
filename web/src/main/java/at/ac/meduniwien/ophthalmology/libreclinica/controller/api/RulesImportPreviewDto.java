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
 * Phase E RX.2 — preview shape returned by
 * {@code POST /api/v1/rules/import}.
 *
 * <p>Wraps the legacy {@code RulesPostImportContainer} counts plus a
 * flat issues list so the SPA can render the "review-then-commit" UX
 * without traversing the JAXB bean graph.
 *
 * <p>The {@code previewToken} is a server-issued UUID the operator
 * passes back to {@code POST /api/v1/rules/import/commit} to retrieve
 * the parked container from the session-scoped cache. Tokens expire
 * 15 minutes after issue — beyond that the commit endpoint returns
 * 410 Gone. See
 * {@link RulesImportApiController#PREVIEW_TTL_SECONDS}.
 *
 * <p>Counts mirror {@code RulesPostImportContainer.getValid* /
 * getDuplicate* / getInValid*}:
 * <ul>
 *   <li><b>Valid</b> — new entries the commit will persist as-is</li>
 *   <li><b>Duplicate</b> — entries whose OID already exists in the
 *       study; commit replaces them unless {@code ignoreDuplicates =
 *       true}</li>
 *   <li><b>Invalid</b> — entries the validator rejected; commit
 *       silently skips them</li>
 * </ul>
 *
 * <p>The {@code issues} list aggregates every {@code importErrors}
 * entry from invalid + duplicate wrappers so operators see the full
 * picture before clicking Commit. Severity is {@code ERROR} for
 * invalid entries (commit will skip) and {@code WARNING} for
 * duplicates (commit will replace unless asked to ignore).
 */
public record RulesImportPreviewDto(
        @Schema(description = "Opaque token returned by /import; pass it back to /import/commit within 15 minutes.")
        String previewToken,
        @Schema(description = "Number of new rule definitions the commit will persist.")
        int validRuleCount,
        @Schema(description = "Number of rule definitions whose OID already exists; commit will replace them unless ignoreDuplicates=true.")
        int duplicateRuleCount,
        @Schema(description = "Number of rule definitions the validator rejected; commit will skip them.")
        int invalidRuleCount,
        @Schema(description = "Number of new rule sets the commit will persist.")
        int validRuleSetCount,
        @Schema(description = "Number of rule sets whose target already has a definition; commit will replace them unless ignoreDuplicates=true.")
        int duplicateRuleSetCount,
        @Schema(description = "Number of rule sets the validator rejected; commit will skip them.")
        int invalidRuleSetCount,
        @Schema(description = "Per-entry validator findings — surface these to operators before committing.")
        List<ImportIssue> issues) {

    /**
     * One validator finding scoped to a single rule or rule set.
     *
     * @param scope      {@code "rule"} or {@code "ruleSet"}
     * @param identifier the rule OID (for scope=rule) or the
     *                   rule-set target expression
     *                   (for scope=ruleSet)
     * @param severity   {@code "ERROR"} for invalid (commit will
     *                   skip), {@code "WARNING"} for duplicates
     *                   (commit will replace)
     * @param message    human-readable description; typically an
     *                   {@code OCRERR_*} message bound by the
     *                   validator or "duplicate"
     */
    public record ImportIssue(
            @Schema(description = "\"rule\" or \"ruleSet\".") String scope,
            @Schema(description = "Rule OID or rule-set target expression.") String identifier,
            @Schema(description = "\"ERROR\" (skip on commit) or \"WARNING\" (replace on commit).") String severity,
            @Schema(description = "Validator message; typically an OCRERR_* code with substituted parameters.") String message) {}
}
