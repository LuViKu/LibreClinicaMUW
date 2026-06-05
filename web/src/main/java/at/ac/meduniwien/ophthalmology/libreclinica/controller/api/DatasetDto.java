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
import java.util.Map;

/**
 * Phase E.6 — Data Export wire shape for a single saved dataset.
 *
 * <p>Superset of Phase 1 (list / archived files / format-export) and
 * Phase 2 (create-dataset wizard). One DTO carries both shapes so the
 * SPA list view, the wizard hydration, and the create/update echo
 * can all share the same response type.
 *
 * <h2>Phase 1 fields (list view + export trigger)</h2>
 *
 * <ul>
 *   <li>{@code oid} — the dataset id as a string. {@code dataset} rows
 *       have no OID column in the schema (only the autoincrement PK),
 *       but the SPA layer wants a stable string handle that mirrors
 *       the convention used for studies / events / CRFs / subjects.</li>
 *   <li>{@code id} — the dataset id as an int (used by mutating
 *       endpoints).</li>
 *   <li>{@code name} / {@code description} — dataset metadata.</li>
 *   <li>{@code ownerName} — username of the operator who saved the
 *       dataset, or {@code null} when the owner is unknown.</li>
 *   <li>{@code dateCreated} — ISO-8601 UTC timestamp; may be {@code null}
 *       for very old rows.</li>
 *   <li>{@code lastRunAt} — ISO-8601 UTC timestamp of the most-recent
 *       export run; {@code null} when never exported.</li>
 *   <li>{@code fileCount} — number of {@code archived_dataset_file} rows
 *       currently on disk for this dataset.</li>
 * </ul>
 *
 * <h2>Phase 2 fields (wizard hydration)</h2>
 *
 * <ul>
 *   <li>{@code studyId} — owning study id; mirrors the legacy column.</li>
 *   <li>{@code status} — legacy {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status}
 *       enum name ({@code "available"} / {@code "removed"} / …) so the
 *       SPA can render lifecycle badges.</li>
 *   <li>{@code eventDefinitionOids} — selected event-definition OIDs,
 *       derived from the persisted SQL fragment on read.</li>
 *   <li>{@code crfVersionIds} — derived from the item set (each item
 *       belongs to exactly one CRF version).</li>
 *   <li>{@code itemIds} — selected item ids.</li>
 *   <li>{@code includeFlags} — 18 string→boolean entries keyed by
 *       camelCase flag names that line up with the SPA's
 *       {@code InclusionFlags} TypeScript type.</li>
 *   <li>{@code numRuns} — execution count.</li>
 *   <li>{@code hasRun} — true when {@code numRuns > 0}; the wizard
 *       disables structural edits when this is true and surfaces a
 *       "duplicate to edit" affordance instead.</li>
 * </ul>
 *
 * <p>The list endpoint populates Phase 1 fields and leaves the Phase 2
 * fields blank/empty; the {@code GET /datasets/{id}} endpoint populates
 * both. The SPA tolerates missing Phase 2 fields on the list response.
 */
@Schema(name = "DatasetDto")
public record DatasetDto(
        String oid,
        int id,
        String name,
        String description,
        String ownerName,
        String dateCreated,
        String lastRunAt,
        int fileCount,
        int studyId,
        String status,
        List<String> eventDefinitionOids,
        List<Integer> crfVersionIds,
        List<Integer> itemIds,
        Map<String, Boolean> includeFlags,
        int numRuns,
        boolean hasRun) {
}
