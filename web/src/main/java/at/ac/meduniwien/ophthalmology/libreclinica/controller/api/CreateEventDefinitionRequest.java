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
 * Phase E A8.2 — POST {@code /api/v1/studies/{studyOid}/event-definitions}
 * request body.
 *
 * <p>Mirrors the fields {@code DefineStudyEventServlet:189–192}
 * validates on the create path. CRF assignments are NOT part of this
 * payload — they ship in A8.3 ({@code /event-definitions/{sedOid}/crfs}
 * endpoints). Operators create the event definition first, then add
 * CRFs via the dedicated surface.
 *
 * <p>Required: {@code name} (≤2000, blank rejected),
 * {@code type} (one of {@code scheduled} / {@code unscheduled} /
 * {@code common}). Optional: {@code description}, {@code category}
 * (≤2000 each), {@code repeating} (default {@code false}).
 *
 * <p>{@code ordinal} is server-assigned (next after the last existing
 * event def in the study). The caller may NOT pass it.
 */
@Schema(name = "CreateEventDefinitionRequest")
public record CreateEventDefinitionRequest(
        String name,
        String description,
        String category,
        String type,
        Boolean repeating
) {}
