/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DR-040 phase 2 — the uploader programs that would stop working when a second
 * location is created: seen in the last seven days, never reported with a
 * token. {@code GET /api/v1/admin/ingest-sources/anonymous-uploaders}.
 *
 * <p>{@code count} is all of them; {@code instances} lists the newest 50, so
 * a page can say "N uploaders have no token yet" and name some.
 */
@Schema(name = "AnonymousUploadersDto")
public record AnonymousUploadersDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "How many uploader programs were seen in the last 7 days and have no token.")
        int count,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The newest of them, at most 50, newest first.")
        List<AnonymousUploaderDto> instances
) {}
