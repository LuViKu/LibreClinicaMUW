/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.Map;

/**
 * Phase E RX.3 — request body for {@code POST /api/v1/rules/test-expression}.
 *
 * <p>The {@code expression} is the OpenClinica V1 expression body to
 * parse and evaluate (e.g. {@code "2 + 2 eq 4"} or
 * {@code "ITEM_BP_SYS gt 140"}). {@code testValues} is an optional
 * map of variable-OID → mock string value; when present it overrides
 * the parser's default substitution.
 *
 * <p>Both fields tolerate {@code null} — the controller handles
 * presence checks itself so the wire contract stays loose (the SPA
 * may POST {@code {expression}} without a values map for a
 * constant-only sanity check).
 */
public record TestExpressionRequest(String expression, Map<String, String> testValues) {}
