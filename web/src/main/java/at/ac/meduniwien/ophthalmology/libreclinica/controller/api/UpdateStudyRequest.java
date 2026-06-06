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
 * Phase E A8.1 — PUT /api/v1/studies/{studyOid} request body.
 *
 * <p>Every field is optional ({@code null} = leave unchanged).
 * Mirrors {@code EditUserAccountServlet:114–191}'s shape — the
 * controller diffs each field independently and writes one
 * {@code audit_log_event} row per changed column (DR-009).
 *
 * <p>NOT editable via this endpoint (covered by other surfaces):
 * <ul>
 *   <li>{@code uniqueProtocolId} — re-uniquing on edit is unsafe;
 *       legacy {@code UpdateStudyServletNew} accepts edits but
 *       doesn't validate collisions</li>
 *   <li>{@code status} — covered by A8.5
 *       ({@code POST /studies/{oid}/status})</li>
 *   <li>{@code parameters} (study_parameter_value rows) — covered by
 *       {@link StudyParametersApiController}
 *       ({@code PUT /api/v1/studies/{oid}/parameters}, Phase E.6
 *       study-params)</li>
 * </ul>
 */
@Schema(name = "UpdateStudyRequest")
public record UpdateStudyRequest(
        String name,
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
