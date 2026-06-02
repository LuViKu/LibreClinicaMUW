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
 * Phase E RX.5 — request body for
 * {@code POST /api/v1/rules/validate-target}.
 *
 * <p>Wraps the legacy {@code ExpressionService.ruleSetExpressionChecker}
 * so the SPA can live-validate a target expression as the operator
 * types (debounced). Read-only — no rule set is persisted.
 */
@Schema(name = "ValidateTargetRequest")
public record ValidateTargetRequest(String target) {}
