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
 * DR-040 phase 2 — an uploader and the token that was just made for it. The
 * answer of {@code POST /api/v1/locations/{locationId}/uploaders} (issue) and
 * {@code POST /api/v1/uploaders/{id}/replace}, and <strong>the only place a
 * token is ever returned</strong>: the platform keeps its hash and an
 * eight-character hint, so a later read cannot show it again.
 *
 * <p>{@link #toString} leaves the token out, so a log line that prints the
 * object cannot leak it.
 */
@Schema(name = "IssuedUploaderDto")
public record IssuedUploaderDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UploaderDto uploader,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "mwu_ followed by 43 base64url characters. Shown once; copy it now.",
                example = "mwu_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        String token
) {
    @Override
    public String toString() {
        return "IssuedUploaderDto[uploader=" + uploader + ", token=<not shown>]";
    }
}
