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
import java.util.List;

/**
 * Phase E RX.5 — response body for
 * {@code POST /api/v1/rules/validate-target}.
 *
 * <p>Always returned with HTTP 200 — the validation outcome lives in
 * the {@code valid} flag + {@code errors} list, not the status code.
 * Lets the SPA distinguish "expression is well-formed but doesn't
 * resolve OIDs against this study" from "the network call failed".
 *
 * <p>{@code errors} is empty when {@code valid == true}. Each entry
 * carries a human-readable message — typically the parser's
 * {@code OCRERR_xxxx} code already resolved against the active locale
 * bundle, e.g. {@code "OCRERR_0022 : Item not found in this study"}.
 */
@Schema(name = "ValidateTargetResponse")
public record ValidateTargetResponse(
        boolean valid,
        List<ValidationIssue> errors
) {
    /**
     * One issue surfaced by the expression checker. Field is the
     * fixed string {@code "target"} since the validate-target
     * endpoint scopes to a single field; SPA renders the message
     * inline next to the target textarea.
     */
    @Schema(name = "ValidateTargetResponse.ValidationIssue")
    public record ValidationIssue(String message) {}
}
