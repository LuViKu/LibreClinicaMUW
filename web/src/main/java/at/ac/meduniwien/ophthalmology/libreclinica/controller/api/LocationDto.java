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
 * DR-040 — one location, as the system administrator's list shows it:
 * {@code GET /api/v1/admin/locations} and the response of the state changes
 * that do not need the lists ({@code disable}, {@code restore}, {@code create}).
 *
 * <p>The four counts are what decides whether the location can be disabled
 * and what an administrator should look at first:
 * <ul>
 *   <li>{@code siteCount} — sites (sub-studies) at the location that are not
 *       removed;</li>
 *   <li>{@code studyCount} — parent or single-site studies at the location
 *       that are not removed;</li>
 *   <li>{@code ingestItemCount} — ingested files of every status whose origin
 *       is the location;</li>
 *   <li>{@code userCount} — explicit user assignments
 *       ({@code user_location}); the users who reach the location through a
 *       role on a site there are not counted.</li>
 * </ul>
 *
 * <p>{@code status} is a stable key: {@code available} or {@code removed}.
 * The address fields are null when never given.
 */
@Schema(name = "LocationDto")
public record LocationDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int id,
        /** The stable identifier; unique without regard to case, immutable after creation. */
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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int userCount
) {}
