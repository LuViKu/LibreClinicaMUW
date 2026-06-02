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
 * Phase E A8.2 — PUT
 * {@code /api/v1/studies/{studyOid}/event-definitions/{sedOid}}
 * request body.
 *
 * <p>Every field optional ({@code null} = unchanged). Mirrors
 * {@code UpdateEventDefinitionServlet:129–133}. Per-field diff emits
 * audit rows on each changed column (additive over legacy —
 * DR-009 audit-on-write).
 *
 * <p>NOT editable here: {@code oid} (identity), {@code studyId}
 * (move-between-studies unsupported), {@code ordinal} (covered by
 * the reorder endpoint), {@code status} (covered by disable).
 */
@Schema(name = "UpdateEventDefinitionRequest")
public record UpdateEventDefinitionRequest(
        String name,
        String description,
        String category,
        String type,
        Boolean repeating
) {}
