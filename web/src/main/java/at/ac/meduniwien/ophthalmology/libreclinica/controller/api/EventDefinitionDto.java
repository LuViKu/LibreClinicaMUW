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
 * Phase E A8.2 — event-definition wire shape.
 *
 * <p>One row per {@code study_event_definition}. Returned by the
 * list ({@code GET}), create ({@code POST}), and edit ({@code PUT})
 * endpoints. CRF assignments are NOT inlined — they're queried via
 * the A8.3 {@code /event-definitions/{sedOid}/crfs} surface.
 *
 * <p>{@code status} carries the legacy {@code Status.getName()}
 * string ({@code "available"} / {@code "removed"} / etc.) so the
 * SPA can render lifecycle badges without re-coding the enum.
 */
@Schema(name = "EventDefinitionDto")
public record EventDefinitionDto(
        String oid,
        String name,
        String description,
        String category,
        String type,
        boolean repeating,
        int ordinal,
        String status
) {}
