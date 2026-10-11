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
 * DR-040 — the body of {@code POST /api/v1/admin/locations} (create) and of
 * {@code PUT /api/v1/admin/locations/{id}} (edit).
 *
 * <ul>
 *   <li>Create: {@code code} and {@code name} are required; the address
 *       fields are optional and stored as given, trimmed (blank is none).</li>
 *   <li>Edit: {@code name} is required. The code is immutable, so a
 *       {@code code} in the body is ignored. An address field that is absent
 *       or null is left as it is; an empty string clears it.</li>
 * </ul>
 */
@Schema(name = "LocationWriteRequest")
public record LocationWriteRequest(
        @Schema(description = "Create only: 2 to 30 letters, digits, underscore or hyphen; unique without regard to case. Ignored on edit.",
                example = "MUW-AUGEN")
        String code,
        @Schema(description = "At most 100 characters; unique without regard to case.")
        String name,
        @Schema(description = "At most 255 characters.") String street,
        @Schema(description = "At most 255 characters.") String city,
        @Schema(description = "At most 64 characters.") String zip,
        @Schema(description = "At most 64 characters.") String country
) {}
