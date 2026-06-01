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

/**
 * Phase E.4 M3 — Sign Subject preflight checks DTO.
 *
 * <p>Backs {@code GET /pages/api/v1/subjects/{oid}/preflightForSign}.
 * The M8 (Sign Subject) view consumes the result; M3 just ships the
 * endpoint so the backend is ready when the UI lands.
 *
 * <p>Check semantics per the Sign Subject mockup at
 * {@code docs/development/modernization/phase-e/ux-mockups/investigator-sign-subject.html}:
 * <ul>
 *   <li>{@code events-complete} — pass iff every scheduled
 *       study_event has subject_event_status_id ∈ {4 completed, 8
 *       signed}; warn if any in-progress; fail if any still
 *       scheduled but never started.</li>
 *   <li>{@code crfs-complete} — pass iff every event_crf has
 *       completion_status_id=1 (per the seed convention — NOT 3:
 *       see note below) AND a date_completed; warn if any still in
 *       initial-data-entry.</li>
 *   <li>{@code open-queries} — pass if none; warn otherwise (open
 *       queries do NOT block signing per the mockup).</li>
 *   <li>{@code subject-not-signed} — pass if study_subject.status_id
 *       != 8; fail if already signed (signing is one-way).</li>
 *   <li>{@code user-role-can-sign} — pass if current userRole is
 *       INVESTIGATOR or STUDYDIRECTOR.</li>
 * </ul>
 *
 * <p><strong>Note on completion_status mapping:</strong> the seed data
 * uses {@code completion_status_id=1} (per legacy convention "1 means
 * the form was completed") on rows where {@code date_completed} is set.
 * The {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.DataEntryStage}
 * enum maps 1 → UNCOMPLETED but the seed comment + the live data on
 * M-001/M-003 shows that the seed treats {@code completion_status_id=1
 * AND date_completed IS NOT NULL} as "complete". We honour that
 * convention: a CRF is considered complete if {@code date_completed}
 * is non-null, regardless of the formal stage id. This keeps M-003's
 * preflight clean.
 */
public record SignPreflightDto(
        List<CheckRow> checks,
        int blockingFailures,
        int warnings,
        boolean subjectAlreadySigned,
        boolean userRoleCanSign
) {

    /**
     * One preflight check row.
     *
     * <p>{@code status} is one of {@code "pass" | "warn" | "fail"}.
     * {@code id} is a stable identifier the SPA keys off (per the
     * brief: {@code events-complete}, {@code crfs-complete},
     * {@code open-queries}, {@code subject-not-signed},
     * {@code user-role-can-sign}).
     */
    public record CheckRow(
            String id,
            String status,
            String title,
            String detail
    ) {}
}
