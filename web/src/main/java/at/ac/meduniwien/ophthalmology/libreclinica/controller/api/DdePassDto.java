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
 * Phase E.6 dde — wire-shape for {@code GET /api/v1/eventCrfs/{id}/dde-pass}.
 *
 * <p>Tells the SPA which DDE pass the caller is starting.
 * <ul>
 *   <li>{@code pass=1} — initial data entry; equivalent to today's
 *       single-pass flow. Returned when the EventCRF has no
 *       {@code date_completed} yet (IDE not complete).</li>
 *   <li>{@code pass=2} — blind second-pass; the caller is a DIFFERENT
 *       clerk re-keying the form from the paper original. Server
 *       returns an EMPTY {@code values} map — the IDE values are
 *       deliberately NOT exposed (server-side blinding). The SPA
 *       renders a banner ({@code dde.banner.blindSecondPass}) on
 *       top of an otherwise-empty CRF Entry form.</li>
 *   <li>{@code pass=reconcile} — every item keyed both passes;
 *       discrepancies spawned as FAILEDVAL notes; awaiting DM/Admin
 *       resolution. The SPA redirects the caller to
 *       {@code DdeReconcileView}.</li>
 * </ul>
 *
 * <p>{@code idePass1ClerkId} is the numeric user id (or {@code 0} when
 * the IDE clerk could not be resolved). The endpoint compares it
 * against the session user and returns {@code 403 same-clerk} when
 * the caller IS the IDE clerk — DDE requires a different pair of
 * eyes. Carrying the id on the wire keeps the SPA from rendering the
 * "Start Pass 2" button when the caller's about to get a 403.
 *
 * @param eventCrfOid    numeric event_crf_id as a string (matches CrfEntryDto)
 * @param pass           {@code 1 | 2 | reconcile}
 * @param idePass1ClerkId numeric user id of the IDE clerk (0 when unknown)
 * @param mismatchCount  count of items needing reconciliation
 *                       (always 0 for pass=1 / pass=2; >=0 for pass=reconcile)
 */
@Schema(name = "DdePassDto")
public record DdePassDto(
        String eventCrfOid,
        String pass,
        int idePass1ClerkId,
        int mismatchCount
) {}
