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
 *
 * <p>DR-040 phase 4: {@code locationId} moves the site to another
 * location (available and in the caller's scope, else 400 on
 * {@code locationId}); {@code null} keeps it, and a site cannot be made
 * location-less. A move copies the new location's name, city, zip and
 * country into the facility address, over any of those sent alongside.
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
        String facilityContactEmail,
        Integer locationId
) {}
