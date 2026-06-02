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
 * Phase E A4 — body of {@code PUT /pages/api/v1/events/{id}}.
 *
 * <p>Editable fields on an existing {@code study_event}:
 * scheduled date / end date / free-text location / SPA-side status
 * string. The event's {@code study_event_definition_id} and
 * {@code study_subject_id} are immutable post-creation (they're FK
 * anchors used by event_crf joins).
 *
 * <p>All fields nullable: omitted = unchanged. {@code dateStarted}
 * and {@code dateEnded} accept ISO {@code YYYY-MM-DD} strings;
 * passing the literal string {@code ""} clears the field.
 *
 * <p>Status is restricted server-side to user-controlled
 * transitions ({@code scheduled | stopped | skipped}); derived
 * statuses ({@code data-entry-started}, {@code completed},
 * {@code signed}, {@code locked}) are not accepted here — they
 * come from CRF completion / signing workflows.
 *
 * @param dateStarted ISO YYYY-MM-DD, or empty to clear, or null to
 *                    leave unchanged.
 * @param dateEnded   same shape as dateStarted.
 * @param location    free-text location, or empty to clear, or null
 *                    to leave unchanged.
 * @param status      SPA-side status: {@code scheduled | stopped |
 *                    skipped}. {@code null} to leave unchanged.
 */
@Schema(name = "UpdateEventRequest")
public record UpdateEventRequest(
        String dateStarted,
        String dateEnded,
        String location,
        String status
) {}
