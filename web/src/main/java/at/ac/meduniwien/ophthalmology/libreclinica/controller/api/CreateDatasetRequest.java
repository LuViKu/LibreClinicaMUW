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
 * Phase E.6 — Data Export Phase 2 — create / edit body for
 * {@link DatasetsApiController}.
 *
 * <p>Used by {@code POST /api/v1/studies/{studyOid}/datasets} (create) and
 * {@code PUT /api/v1/datasets/{datasetId}} (edit). Carries:
 *
 * <ul>
 *   <li>{@code name} / {@code description} — dataset metadata; {@code name}
 *       must be unique within the parent study and ≤2000 chars.</li>
 *   <li>{@code eventDefinitionOids} — at least one. Each must resolve to a
 *       {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean}
 *       under the target study (no cross-study leakage).</li>
 *   <li>{@code crfVersionIds} — at least one. The wizard collapses
 *       "all-versions" CRF picks into the full version list before posting.</li>
 *   <li>{@code itemIds} — at least one. Each must belong to a version in
 *       {@code crfVersionIds}; the controller does NOT re-derive these from
 *       the CRF tree, so the wizard is authoritative.</li>
 *   <li>{@code includeFlags} — string→boolean map keyed by the SPA's
 *       camelCase flag names (e.g. {@code "dob"}, {@code "gender"},
 *       {@code "eventStartDate"}). Unknown keys are silently ignored; any
 *       missing key is treated as {@code false}. See
 *       {@link DatasetsApiController#FLAG_KEYS} for the full set.</li>
 *   <li>{@code selectedItemOids} — Phase 3 carry-over: per-item OIDs the
 *       wizard's Phase 3 {@code FilterBuilder} sources its operator
 *       selector from. Optional; not part of dataset persistence today.</li>
 *   <li>{@code filters} — Phase 3 predicate list. Optional; reserved for
 *       Phase 4 async-export persistence.</li>
 * </ul>
 *
 * <p>The legacy {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean}
 * model encodes event-definition + item selection into a SQL fragment
 * which is later parsed by
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.dao.extract.DatasetDAO#initialDatasetData(int)}.
 * There is no separate {@code dataset_event_map} / {@code dataset_item_map}
 * table; the SQL fragment IS the join. The controller builds the bean's
 * {@code eventIds} + {@code itemIds} + {@code SQLStatement} from the
 * request payload and the legacy DAO persists it unchanged. CRF-version
 * selection is tracked exclusively client-side (the legacy column set has
 * no crf-version map either; the dataset's item list implicitly pins the
 * version set since each item belongs to exactly one version).
 */
@Schema(name = "CreateDatasetRequest")
public record CreateDatasetRequest(
        String name,
        String description,
        List<String> eventDefinitionOids,
        List<Integer> crfVersionIds,
        List<Integer> itemIds,
        Map<String, Boolean> includeFlags,
        List<String> selectedItemOids,
        List<DatasetsApiController.DatasetFilterDto> filters
) {
}
