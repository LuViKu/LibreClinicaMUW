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
 * Phase E.4 M1 — entry in the user's available-studies list.
 *
 * <p>Returned by {@code GET /pages/api/v1/studies}. Each row pairs a
 * study (or site, when parentOid != null) with the user's role on it.
 * The SPA renders these as a picker; submitting one POSTs to
 * {@code /me/activeStudy} which binds the session-scoped study.
 */
@Schema(name = "StudyOptionDto")
public record StudyOptionDto(
        String oid,
        String name,
        /**
         * Institutional protocol short-code (DB column
         * {@code study.unique_identifier}). Distinct from {@link #oid}
         * (system-assigned API handle). Used by the SPA's eye-transition
         * dialog to prefill the target subject-ID with a
         * "{uniqueIdentifier}-" prefix when the patient is being newly
         * enrolled into the target study. Phase E.6 follow-up
         * 2026-06-10.
         */
        String uniqueIdentifier,
        /** OID of the parent study if this is a site, null otherwise. */
        String parentOid,
        /** Display name of the parent study (denormalised for the picker). */
        String parentName,
        /** Role label mapped into the SPA's UserRole union. */
        String role,
        boolean isSite,
        boolean isActive
) {}
