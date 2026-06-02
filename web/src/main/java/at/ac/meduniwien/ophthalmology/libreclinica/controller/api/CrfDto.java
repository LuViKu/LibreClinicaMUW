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
 * Phase E A8.3 — CRF library row wire shape.
 *
 * <p>One row per {@code crf} table entry. The {@code versions} list
 * is inlined for the GET endpoints so the SPA can show "Visit form
 * v1.0 / v1.1 …" without a second round-trip per CRF; on
 * POST-create the list is empty (versions ship via the separate
 * upload endpoint).
 *
 * <p>{@code status} carries the legacy {@code Status.getName()}
 * string ({@code "available"} / {@code "removed"} / etc.).
 */
@Schema(name = "CrfDto")
public record CrfDto(
        String oid,
        String name,
        String description,
        String status,
        List<CrfVersionDto> versions
) {

    /**
     * One row per {@code crf_version} table entry. Returned inline on
     * the CRF GET endpoints + on its own from the version upload
     * endpoint.
     */
    @Schema(name = "CrfVersionDto")
    public record CrfVersionDto(
            String oid,
            String name,
            String description,
            String revisionNotes,
            String status,
            String uploadedAt
    ) {}
}
