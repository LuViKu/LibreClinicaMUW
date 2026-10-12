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
 * DR-040 phase 2 — one uploader program that has been seen lately and has
 * never reported with a token, as {@code GET /api/v1/admin/ingest-sources/anonymous-uploaders}
 * lists it. Enough to recognise the PC; nothing about how it is doing (that is
 * the uploaders page and System Status) and not its instance id.
 */
@Schema(name = "AnonymousUploaderDto")
public record AnonymousUploaderDto(
        @Schema(nullable = true, description = "The PC's name as the program reports it.")
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The program, e.g. export-watcher or optomed-bridge.")
        String kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The last heartbeat, an ISO-8601 instant.")
        String lastSeenAt
) {}
