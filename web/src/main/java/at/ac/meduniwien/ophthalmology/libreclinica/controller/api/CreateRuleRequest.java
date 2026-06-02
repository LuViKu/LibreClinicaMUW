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
 * Phase E RX.5 — request body for {@code POST /api/v1/rules}.
 *
 * <p>{@code oid} is the operator-supplied OID for the new
 * {@code rule.oc_oid}. Must match the legacy OID grammar
 * ({@code ^[A-Z][A-Z0-9_]*$} per RX.5 spec — the underlying
 * {@code OidGenerator} validator is slightly looser
 * ({@code ^[A-Z_0-9]+$}); the RX.5 controller pre-validates with the
 * tighter pattern so freshly-created OIDs match the convention the
 * legacy import path produces (no leading underscore / digit, all
 * uppercase).
 *
 * <p>{@code expression} is the rule body — the V1 expression that
 * gets evaluated to a boolean per fire. Must pass
 * {@code ExpressionService.ruleExpressionChecker} against the active
 * study's scope.
 *
 * <p>All four fields tolerate {@code null} — the controller surfaces
 * a 400 with a per-field error envelope when required values are
 * missing.
 */
@Schema(name = "CreateRuleRequest")
public record CreateRuleRequest(
        String oid,
        String name,
        String description,
        String expression
) {}
