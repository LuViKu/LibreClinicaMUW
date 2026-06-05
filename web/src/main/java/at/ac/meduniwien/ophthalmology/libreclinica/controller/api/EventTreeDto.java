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
 * Phase E.6 — Data Export Phase 2 — denormalised event/CRF/version/item
 * tree returned by {@code GET /api/v1/studies/{studyOid}/event-tree}.
 *
 * <p>The legacy five-step "Create Dataset" wizard fired ~5 round-trips
 * to compose this; the SPA wizard collapses it into one. Each tree
 * node carries the per-level identifiers + names + ordinals the wizard
 * needs to render a checkbox tree and serialise the resulting picks
 * back into {@link CreateDatasetRequest}.
 *
 * <p>Removed (status DELETED / AUTO_DELETED) rows are filtered out at
 * every level so the wizard only ever surfaces live entities.
 */
@Schema(name = "EventTreeNode")
public record EventTreeDto(
        String eventOid,
        String eventName,
        int eventOrdinal,
        boolean repeating,
        List<CrfNode> crfs
) {

    @Schema(name = "EventTreeCrfNode")
    public record CrfNode(
            String crfOid,
            String crfName,
            List<VersionNode> versions
    ) {}

    @Schema(name = "EventTreeVersionNode")
    public record VersionNode(
            int versionId,
            String versionOid,
            String versionName,
            List<ItemNode> items
    ) {}

    @Schema(name = "EventTreeItemNode")
    public record ItemNode(
            int itemId,
            String oid,
            String name,
            String dataType
    ) {}
}
