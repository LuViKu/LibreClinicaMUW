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
import java.util.List;

/**
 * {@code GET /api/v1/crfs/{crfOid}}: one CRF with its versions, its items and
 * the studies that use it. Legacy parity: {@code ViewCRFServlet} and
 * {@code viewCRF.jsp} (without "run all rules for this CRF").
 *
 * @param mayEdit whether the caller may change the name and description
 *                ({@code PUT /crfs/{oid}}), so the SPA shows the action only
 *                where the server would accept it
 */
@Schema(name = "CrfDetailDto")
public record CrfDetailDto(
        String oid,
        String name,
        String description,
        String status,
        boolean mayEdit,
        List<CrfDto.CrfVersionDto> versions,
        List<Item> items,
        List<StudyUse> studies
) {

    /**
     * One item of the CRF, across its versions. {@code integrity} is the
     * legacy check that an item keeps the same item group in every version:
     * {@code ok}; {@code problem} when a conflicting placement is in an
     * available version; {@code warning} when every conflicting version is
     * removed or locked. {@code placements} lists the groups the item was
     * found in, one entry per conflicting version, when it is not {@code ok}.
     */
    @Schema(name = "CrfDetailItem")
    public record Item(
            String name,
            String oid,
            String description,
            String dataType,
            List<String> versions,
            String integrity,
            List<Placement> placements
    ) {}

    /** An item group an item sits in, in one version. */
    @Schema(name = "CrfDetailItemPlacement")
    public record Placement(String groupLabel, String versionName) {}

    /**
     * A study (or site) with an event-definition CRF for this CRF. Legacy
     * {@code StudyDAO.getStudyIdsByCRF}: every such row counts, whatever its
     * status.
     */
    @Schema(name = "CrfDetailStudyUse")
    public record StudyUse(
            String oid,
            String name,
            String uniqueProtocolId,
            String status,
            String parentOid,
            String parentName
    ) {}
}
