/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

/**
 * Phase E RX.3 — successful response for {@code POST /api/v1/rules/test-expression}.
 *
 * <p>{@code result} carries the parser's stringified evaluation
 * (typically {@code "true"} / {@code "false"} for boolean rules, a
 * numeric literal for arithmetic). {@code evaluatedAt} is an ISO-8601
 * UTC instant the parser was invoked — useful when the SPA caches
 * results client-side.
 */
public record TestExpressionResponse(String result, String evaluatedAt) {}
