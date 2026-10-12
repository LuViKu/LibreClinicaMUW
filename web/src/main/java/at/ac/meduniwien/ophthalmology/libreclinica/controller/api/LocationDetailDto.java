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
 * DR-040 — one location with everything the administrator's detail page
 * shows: every field of {@link LocationDto}, the studies and sites that are
 * at the location, and the users assigned to it explicitly. The response of
 * {@code GET /api/v1/admin/locations/{id}}, of {@code PUT} (edit) and of the
 * two user-assignment calls.
 *
 * <p>{@code studies} lists the studies and sites whose {@code location_id} is
 * this location and that are not removed, by name; a site carries its parent
 * study's OID. {@code users} lists the explicit assignments by user name.
 */
@Schema(name = "LocationDetailDto")
public record LocationDetailDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"available", "removed"})
        String status,
        @Schema(nullable = true) String street,
        @Schema(nullable = true) String city,
        @Schema(nullable = true) String zip,
        @Schema(nullable = true) String country,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int siteCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int studyCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ingestItemCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int userCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LocationStudyDto> studies,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LocationUserDto> users
) {

    /** The summary's fields plus the two lists. */
    static LocationDetailDto of(LocationDto base, List<LocationStudyDto> studies, List<LocationUserDto> users) {
        return new LocationDetailDto(base.id(), base.code(), base.name(), base.status(),
                base.street(), base.city(), base.zip(), base.country(),
                base.siteCount(), base.studyCount(), base.ingestItemCount(), base.userCount(),
                studies, users);
    }
}
