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
 * DR-040 phase 2 — one uploader (a program on an acquisition PC with a token)
 * as the uploaders page shows it: {@code GET /api/v1/uploaders} and the
 * answer of every call that changes one.
 *
 * <p>Nothing here can be used as a credential: {@code tokenHint} is the first
 * eight characters of the token, which tells tokens apart and nothing more.
 * The token itself is returned once, inside {@link IssuedUploaderDto}.
 *
 * <p>All times are ISO-8601 instants. {@code lastUsedAt} is the last request
 * made with the token (written at most once a minute); {@code lastHeartbeatAt}
 * is the last heartbeat of the program that token reported as
 * ({@code instanceUid}), null while it has not reported.
 */
@Schema(name = "UploaderDto")
public record UploaderDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int locationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locationCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locationName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Unique per location without regard to case.")
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The first eight characters of the token after the mwu_ prefix.")
        String tokenHint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"active", "revoked"})
        String status,
        @Schema(nullable = true, description = "User name of whoever issued it.") String createdBy,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String createdAt,
        @Schema(nullable = true) String lastUsedAt,
        @Schema(nullable = true) String revokedAt,
        @Schema(nullable = true, description = "The heartbeat instance the token last reported as.") String instanceUid,
        @Schema(nullable = true) String lastHeartbeatAt
) {}
