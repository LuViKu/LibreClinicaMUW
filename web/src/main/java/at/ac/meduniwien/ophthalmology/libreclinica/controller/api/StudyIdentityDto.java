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
 * Phase E A8.1 — study identity / protocol / metadata wire shape.
 *
 * <p>Returned by POST {@code /api/v1/studies}, PUT
 * {@code /api/v1/studies/{studyOid}}, and the disable / restore
 * lifecycle endpoints. Distinct from the M12
 * {@code StudyBuildDto} (which carries count snapshots for the build
 * dashboard) so the create/edit paths and the dashboard can evolve
 * independently.
 *
 * <p>{@code parentStudyOid} is {@code null} for top-level studies;
 * for sites it carries the parent's OID. Sites also fill
 * {@code siteLabel} with the site's name for A4 site-visibility
 * convenience.
 */
@Schema(name = "StudyIdentityDto")
public record StudyIdentityDto(
        String oid,
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
        String phase,
        String status,
        String parentStudyOid,
        String parentStudyName
) {}
