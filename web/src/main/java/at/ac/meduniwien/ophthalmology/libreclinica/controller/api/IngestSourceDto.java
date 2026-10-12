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
 * DR-040 phase 2 — one ingest source as the system administrator's list shows
 * it: {@code GET /api/v1/admin/ingest-sources} (every kind, uploaders
 * included) and the answer of {@code PUT .../{id}/location}.
 *
 * <p>{@code locationId} is null while the source is not assigned: what it
 * sends waits for an administrator, and {@code pendingItemCount} says how
 * many of its files are still unbound. {@code lastSeenAt} is the last time the
 * source was seen (the last request with an uploader's token, the last file
 * from an AE title or the Remidio account), null if never. {@code status} is
 * {@code revoked} for an uploader whose token was revoked and
 * {@code active} for everything else.
 */
@Schema(name = "IngestSourceDto")
public record IngestSourceDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"uploader", "dicom_ae", "remidio", "portal"})
        String kind,
        @Schema(nullable = true, description = "The AE title of a dicom_ae, the account of a remidio; null for an uploader.")
        String sourceKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(nullable = true) Integer locationId,
        @Schema(nullable = true) String locationName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Files of this source that are still unbound (UNBOUND with ingest_source_id = id).")
        int pendingItemCount,
        @Schema(nullable = true) String lastSeenAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"active", "revoked"})
        String status
) {}
