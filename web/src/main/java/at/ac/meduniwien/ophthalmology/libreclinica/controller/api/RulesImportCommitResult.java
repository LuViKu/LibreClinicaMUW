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

/**
 * Phase E RX.2 — commit summary returned by
 * {@code POST /api/v1/rules/import/commit}.
 *
 * <p>Counts are derived from the parked container at commit time:
 * <ul>
 *   <li><b>{@code rulesCreated}</b> — {@code validRuleDefs.size()}
 *       (new rule definitions)</li>
 *   <li><b>{@code rulesReplaced}</b> — {@code duplicateRuleDefs.size()}
 *       when {@code ignoreDuplicates = false}; otherwise zero</li>
 *   <li><b>{@code ruleSetsCreated}</b> —
 *       {@code validRuleSetDefs.size()}</li>
 *   <li><b>{@code ruleSetsReplaced}</b> —
 *       {@code duplicateRuleSetDefs.size()} when
 *       {@code ignoreDuplicates = false}</li>
 *   <li><b>{@code actionsCreated}</b> — total
 *       {@code RuleSetRuleBean} action rows attached across the new
 *       + replaced rule sets, projected from the pre-save bean graph
 *       (post-save counts would require a refetch)</li>
 *   <li><b>{@code committedAt}</b> — ISO-8601 instant captured
 *       server-side immediately after {@code saveImport} returned</li>
 * </ul>
 *
 * <p>The counts are a best-effort projection of what was persisted —
 * the underlying {@code RuleSetService.saveImport} does not return a
 * per-entry receipt, so we count from the container the validator
 * built. In practice this matches what the operator sees, because
 * {@code saveImport} processes the same valid + duplicate buckets the
 * preview projected.
 */
public record RulesImportCommitResult(
        @Schema(description = "Number of new rule definitions created.") int rulesCreated,
        @Schema(description = "Number of pre-existing rule definitions overwritten. Zero when ignoreDuplicates=true.")
        int rulesReplaced,
        @Schema(description = "Number of new rule sets created.") int ruleSetsCreated,
        @Schema(description = "Number of pre-existing rule sets overwritten. Zero when ignoreDuplicates=true.")
        int ruleSetsReplaced,
        @Schema(description = "Total action rows attached across the new + replaced rule sets.")
        int actionsCreated,
        @Schema(description = "ISO-8601 instant the commit completed (server clock).")
        String committedAt) {}
