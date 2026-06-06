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
import java.util.Map;

/**
 * Phase E.6 dde — body of {@code POST /api/v1/eventCrfs/{id}/dde-commit}.
 *
 * <p>The DDE clerk has finished the blind pass-2 re-key and is
 * submitting their values for diff against the pass-1 (IDE) values.
 *
 * <p>{@code values} keys are item OIDs, same convention as
 * {@link EventCrfsApiController.SaveItemsRequest}. Values that match
 * the pass-1 entry silently flip {@code item_data.status_id} from
 * AVAILABLE→AVAILABLE (no-op). Mismatches spawn a FAILEDVAL
 * discrepancy_note row (via the generalisation landed by the
 * {@code discrepancy-full} cluster) and pin the EventCRF in
 * {@code dde-conflicts} status until DM reconciliation.
 *
 * @param values pass-2 values keyed by item OID
 */
@Schema(name = "DdeCommitRequest")
public record DdeCommitRequest(
        Map<String, Object> values
) {}
