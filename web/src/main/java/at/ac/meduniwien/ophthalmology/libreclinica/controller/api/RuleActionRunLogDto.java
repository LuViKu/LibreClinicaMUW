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
 * Phase E RX.1b — wire shape for a single {@code rule_action_run_log}
 * row.
 *
 * <p>Returned in arrays by {@code GET /api/v1/rule-sets/{id}/run-log}.
 * Mirrors the legacy {@code RuleActionRunLogBean} fields the SPA
 * needs to render a fire-history audit row.
 *
 * <p>{@code firedAt} is intentionally nullable: the
 * {@code rule_action_run_log} table has no timestamp column (see
 * {@code migration/amethyst/2010-01-13-4575.xml} changeset {@code -8}).
 * Ordering is by auto-increment primary key DESC, which matches
 * insertion order in practice but means we cannot surface a real
 * fire timestamp. The field stays in the wire shape so a future
 * schema migration (adding {@code date_run TIMESTAMP}) can populate
 * it without re-versioning the contract.
 *
 * <p>{@code itemDataId} is nullable because some action types (e.g.
 * {@code Notification}) don't write a per-item-data row.
 */
@Schema(name = "RuleActionRunLogDto")
public record RuleActionRunLogDto(
        int id,
        String actionType,
        String ruleOid,
        Integer itemDataId,
        String value,
        String firedAt
) {}
