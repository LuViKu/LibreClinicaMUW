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
 * Phase E A8.1 — POST /api/v1/studies request body.
 *
 * <p>Collapses the legacy 6-page {@code CreateStudyServlet} wizard
 * into one flat request, the same way A7.1 collapsed
 * {@code CreateUserAccountServlet}. Per-page validation may survive
 * as client-side staging in the SPA; the server validates the whole
 * payload in one shot.
 *
 * <p>Required fields (mirror {@code CreateStudyServlet:405–429}):
 * {@code name} (≤100), {@code uniqueProtocolId} (≤30, unique across
 * studies), {@code briefSummary} (≤255), {@code principalInvestigator}
 * (≤255), {@code sponsor} (≤255).
 *
 * <p>OID is <b>server-generated</b> — the caller may not pass it.
 * The legacy {@code createStepOne} writes the row with {@code oid =
 * null} and a follow-up step populates it; the response carries the
 * generated OID.
 */
@Schema(name = "CreateStudyRequest")
public record CreateStudyRequest(
        String name,
        String uniqueProtocolId,
        String briefSummary,
        String principalInvestigator,
        String sponsor,
        String officialTitle,
        String secondaryProtocolId,
        String collaborators,
        String protocolDescription,
        String contactEmail,
        String protocolType,
        String phase
) {}
