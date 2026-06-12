/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto;

import java.util.List;

/**
 * Phase E-hardening B5 — canonical 400/4xx-response body shape for
 * the {@code /pages/api/v1/**} adapters.
 *
 * <p>Matches the SPA's existing {@code AddSubjectError} TS shape
 * byte-for-byte: an {@code errors} array of {@link FieldError}
 * {field, message} pairs plus a top-level {@code message} summary.
 *
 * <p>Extracted verbatim from {@code SubjectsApiController} where it
 * used to live as a nested record. Hosting it here lets sibling
 * controllers and {@link
 * at.ac.meduniwien.ophthalmology.libreclinica.controller.api.ApiExceptionHandler}
 * share the same wire contract without reaching into a peer
 * controller's namespace.
 *
 * <p>Wire shape:
 * <pre>{@code
 *   { "message": "<human-readable summary>",
 *     "errors":  [ { "field": "<dotted.path>", "message": "<…>" }, … ] }
 * }</pre>
 *
 * <p>Framework-level catch-all branches in {@link
 * at.ac.meduniwien.ophthalmology.libreclinica.controller.api.ApiExceptionHandler}
 * always emit {@code errors: []} — Spring's exception payload doesn't
 * carry per-field details. The SPA's {@code ApiError.parseBody} is
 * extra-field tolerant, so this is a non-breaking change.
 */
public record ValidationErrorBody(String message, List<FieldError> errors) {
    public record FieldError(String field, String message) {}
}
