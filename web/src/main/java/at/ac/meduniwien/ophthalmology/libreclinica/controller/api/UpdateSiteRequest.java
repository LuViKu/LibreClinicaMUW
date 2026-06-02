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
 * Phase E A8.4 — PUT
 * {@code /api/v1/studies/{parentOid}/sites/{siteOid}} request body.
 *
 * <p>Every field optional ({@code null} = leave unchanged). Per-field
 * diff + audit emission mirrors A7.2 + A8.1 + A8.2. NOT editable
 * here: {@code uniqueProtocolId}, {@code status} (covered by
 * disable/restore), {@code parentStudyId} (move-between-studies is
 * unsupported).
 */
@Schema(name = "UpdateSiteRequest")
public record UpdateSiteRequest(
        String name,
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
        String facilityContactEmail
) {}
