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
 * Phase E.4 M3 — Subject Detail DTO.
 *
 * <p>Wire shape consumed by the SPA {@code SubjectDetail} TS type in
 * {@code web/src/spa/src/types/subject.ts}. Mirrors
 * {@link SubjectListItemDto} byte-for-byte for the matrix-overlap
 * fields and adds the per-event richer metadata + subject-level study
 * fields the detail view renders that the matrix doesn't.
 *
 * <p>Fields beyond {@link SubjectListItemDto}:
 * <ul>
 *   <li>{@code studyOid} / {@code studyName} — the active study the
 *       subject is enrolled in; the SPA renders these in the breadcrumb
 *       and the page header in place of the matrix's stub
 *       {@code siteLabel}.</li>
 *   <li>{@code events[]} carries the richer {@link EventCellDetailDto}
 *       which extends {@link SubjectListItemDto.EventCellDto} with
 *       date_start, date_end, location, and a SPA-friendly
 *       {@code dataEntryStage} string drawn from the primary CRF's
 *       completion_status.</li>
 * </ul>
 *
 * <p>{@code dataEntryStage} taxonomy (mapped via
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage}):
 * <pre>
 *   null                              — no event_crf row (not started)
 *   "not-started"                     — completion_status_id = 1
 *   "data-being-entered"              — completion_status_id = 2
 *   "initial-data-entry-completed"    — completion_status_id = 3
 *   "validation-completed"            — completion_status_id = 4 or 5
 *   "locked"                          — completion_status_id = 7
 * </pre>
 * The mapping collapses Initial Double Data Entry (4) and Double Data
 * Entry Complete (5) into a single SPA value because the SPA's M3 view
 * has no "validating" sub-state — both render the same indicator.
 */
@Schema(name = "SubjectDetailDto")
public record SubjectDetailDto(
        String id,
        String secondaryId,
        String siteOid,
        String siteLabel,
        String studyOid,
        String studyName,
        String gender,
        Integer yearOfBirth,
        String groupLabel,
        String enrolledOn,
        List<EventCellDetailDto> events,
        boolean signed,
        /**
         * Phase E A3-lock — true when the study_subject's status is
         * {@code LOCKED}. The SPA renders a "frozen" badge + hides
         * edit / data-entry actions in that case.
         */
        boolean locked,
        int openQueries,
        /**
         * Phase E.6 Tier 1 — ophthalmology study-eye scope.
         *
         * <p>One of {@code "OD" / "OS" / "OU"} or {@code null} for
         * non-ophth studies / pre-randomization subjects. Persisted in
         * {@code study_subject.study_eye}.
         */
        String studyEye,
        /**
         * Phase E.6 Tier 1 — eligibility-screening date as ISO
         * {@code YYYY-MM-DD}. {@code null} when not recorded.
         * Persisted in {@code study_subject.screening_date}.
         */
        String screeningDate
) {

    /**
     * Detail-view event cell — superset of {@link SubjectListItemDto.EventCellDto}.
     *
     * <p>{@code dateStart} / {@code dateEnd} are ISO {@code YYYY-MM-DD}
     * (date only — the study_event table stores timestamp but the SPA
     * doesn't render time-of-day in the detail view).
     */
    @Schema(name = "EventCellDetailDto")
    public record EventCellDetailDto(
            /**
             * Phase E A4 — numeric study_event.id as a string. Empty
             * when no row exists yet (the event-definition slot is
             * unscheduled). Required by the PUT/DELETE
             * {@code /api/v1/events/{id}} endpoints.
             */
            String eventId,
            String eventDefinitionOid,
            String label,
            String status,
            int openQueries,
            String dateStart,
            String dateEnd,
            String location,
            String dataEntryStage
    ) {}
}
