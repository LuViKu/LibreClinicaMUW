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
 * DR-040 — a study or site that is at a location, in
 * {@link LocationDetailDto#studies()}. Removed studies are not listed.
 *
 * <p>{@code status} is the lower-case key of the study's status
 * ({@code available}, {@code pending}, {@code frozen}, {@code locked},
 * {@code unavailable}, {@code private}, {@code signed}); {@code parentOid} is
 * the parent study's OID for a site and null for a study.
 */
@Schema(name = "LocationStudyDto")
public record LocationStudyDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String oid,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isSite,
        @Schema(nullable = true) String parentOid,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status
) {}
