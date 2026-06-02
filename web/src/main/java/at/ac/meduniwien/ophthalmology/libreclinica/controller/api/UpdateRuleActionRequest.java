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
 * Phase E RX.6 — request body for
 * {@code PUT /api/v1/rule-sets/{id}/actions/{actionId}}.
 *
 * <p>All fields are optional; a {@code null} field is left as-is on
 * the persisted bean. The backend computes a per-field diff and emits
 * one {@code audit_log_event} row per actually-changed field
 * ({@code message}, {@code expression_evaluates_to}, {@code to}, and
 * the five {@code run_*_data_entry} / {@code run_batch} phase gates).
 *
 * <h2>Scope cut</h2>
 *
 * <p>The inline edit surface covers the four action types the RX.5
 * create endpoint supports — {@code FILE_DISCREPANCY_NOTE},
 * {@code EMAIL}, {@code SHOW}, {@code HIDE}. The other four
 * ({@code INSERT}, {@code EVENT}, {@code NOTIFICATION},
 * {@code RANDOMIZE}) still require the XML import path; the
 * controller returns 404 if the targeted action is one of those.
 *
 * <p>Action-type morph (insert → randomize etc.) is intentionally
 * out of scope: operators delete + recreate via the wizard. That
 * keeps the audit log cleaner and avoids needing migration code for
 * the type-specific property tables.
 *
 * <p>{@code destinationProperty} edits for {@code SHOW} / {@code HIDE}
 * actions are also deferred — the {@code rule_action_property} rows
 * are set at creation and stay; renaming them mid-life would require
 * the same scope-aware OID validation the create path runs, which
 * doubles the surface for the same operator gesture as "delete +
 * recreate".
 *
 * <h2>Phase gates</h2>
 *
 * <p>When {@code phaseGates} is non-null the controller updates the
 * five booleans wholesale; when it's null the persisted gates stay
 * as-is. The SPA submits the full set every time so a partial edit
 * (e.g. "just disable batch") still gives the operator a sanity
 * check on the remaining four gates before save.
 */
@Schema(name = "UpdateRuleActionRequest")
public record UpdateRuleActionRequest(
        String message,
        Boolean expressionEvaluatesTo,
        String to,
        CreateRuleActionRequest.PhaseGatesInput phaseGates
) {}
