/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 Milestone B — wire shape for the cross-CRF response-set
 * catalog ({@code GET /api/v1/response-sets}).
 *
 * <p>Backed by a distinct-tuples view of the existing
 * {@code response_set} table (see DR-020): the catalog is virtual,
 * surfacing reusable {@code (label, responseType, optionsText,
 * optionsValues)} combinations so operators authoring a new CRF can
 * pick an existing definition rather than re-typing options.
 *
 * <p>Field shape mirrors
 * {@link CrfVersionAuthoringRequest.ResponseSet} so the SPA can drop a
 * picked catalog entry straight into the authoring draft.
 */
@Schema(name = "ResponseSetCatalogEntry")
public record ResponseSetDto(
        String label,
        String responseType,
        List<CrfVersionAuthoringRequest.Option> options,
        long usageCount,
        boolean inActiveStudy
) {
}
