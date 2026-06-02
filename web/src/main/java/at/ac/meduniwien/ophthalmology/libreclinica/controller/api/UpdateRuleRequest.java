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
 * Phase E RX.6 — request body for {@code PUT /api/v1/rules/{id}}.
 *
 * <p>All fields are optional; a {@code null} field is interpreted as
 * "leave the persisted value unchanged" so the SPA can submit a
 * minimal patch (only the fields the operator actually edited). The
 * backend computes a per-field diff and emits one
 * {@code audit_log_event} row per actually-changed field.
 *
 * <p>Scope cut: rule {@code oid} is intentionally not in this DTO —
 * RX.6 only edits {@code name} / {@code description} /
 * {@code expression}. Renaming the OID would invalidate every
 * rule_set_rule binding that references the old OID, which is a
 * "rule health" follow-up out of RX.6 scope.
 *
 * <p>When {@code expression} is non-null, the backend re-runs the
 * same {@code ExpressionService.ruleExpressionChecker} the create
 * endpoint uses, so a malformed body surfaces as a 400 with the
 * same error envelope shape RX.5 emits.
 */
@Schema(name = "UpdateRuleRequest")
public record UpdateRuleRequest(
        String name,
        String description,
        String expression
) {}
