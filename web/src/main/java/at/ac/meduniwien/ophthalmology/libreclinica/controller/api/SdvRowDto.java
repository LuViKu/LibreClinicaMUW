/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

/**
 * Phase E.4 M9 — wire-shape for {@code GET /pages/api/v1/sdv}.
 *
 * <p>Mirrors the Vue SPA's {@code SdvRow} TS interface in
 * {@code web/src/spa/src/types/sdv.ts} byte-for-byte. One row per
 * event-CRF in the session-bound active study, regardless of
 * verification state — the SPA's status filter narrows
 * client-side.
 *
 * @param eventCrfOid    stringified event_crf_id — opaque to the SPA
 * @param subjectId      StudySubject.label, e.g. "M-001"
 * @param siteLabel      Study.name of the (sub-)study the subject is on
 * @param eventLabel     friendly event name, e.g. "V1 Inclusion"
 * @param eventStartDate ISO YYYY-MM-DD, or empty when unscheduled
 * @param crfName        CRF display name + version, e.g. "Demographics v1.0"
 * @param crfLanguage    BCP-47 tag; falls back to {@code "en"}
 * @param status         {@code pending | query | verified | locked}
 * @param requirement    {@code required-100 | required-partial | not-required}
 * @param openQueries    count of open discrepancy notes against the
 *                       event-CRF — drives the "query" override on
 *                       {@code status} and the "only with queries"
 *                       filter checkbox
 * @param lastUpdatedAt  ISO instant of the last data-entry edit; empty
 *                       when never edited
 */
public record SdvRowDto(
        String eventCrfOid,
        String subjectId,
        String siteLabel,
        String eventLabel,
        String eventStartDate,
        String crfName,
        String crfLanguage,
        String status,
        String requirement,
        int openQueries,
        String lastUpdatedAt
) {}
