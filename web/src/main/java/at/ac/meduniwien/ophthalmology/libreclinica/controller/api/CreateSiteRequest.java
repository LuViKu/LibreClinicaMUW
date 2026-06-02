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
 * Phase E A8.4 — POST {@code /api/v1/studies/{parentOid}/sites}
 * request body.
 *
 * <p>A site is structurally a {@code StudyBean} with
 * {@code parent_study_id > 0}. The legacy {@code CreateSubStudyServlet}
 * accepts identity + facility metadata; we accept the same identity
 * fields as the parent ({@link CreateStudyRequest}) plus optional
 * facility-contact fields the legacy form collects.
 *
 * <p>Required: {@code name} (≤100), {@code uniqueProtocolId} (≤30,
 * unique across the entire study tree — same rule as parent studies),
 * {@code principalInvestigator} (≤255).
 *
 * <p>OID is server-generated as {@code S_<UNIQUE_PROTOCOL_ID>}
 * matching the parent-study OID convention.
 */
@Schema(name = "CreateSiteRequest")
public record CreateSiteRequest(
        String name,
        String uniqueProtocolId,
        String briefSummary,
        String principalInvestigator,
        String facilityName,
        String facilityCity,
        String facilityState,
        String facilityZip,
        String facilityCountry,
        String facilityContactName,
        String facilityContactDegree,
        String facilityContactPhone,
        String facilityContactEmail,
        Integer initialPrincipalInvestigatorUserId
) {}
