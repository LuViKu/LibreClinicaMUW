/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

/**
 * Phase E.6 — Data-export MVP.
 *
 * <p>One saved-dataset row, surfaced in the SPA's
 * {@code DatasetListView}. The "oid" field is the {@code dataset.id}
 * rendered as a string: {@code dataset} rows have no OID column in
 * the schema (only the autoincrement PK), but the SPA layer wants
 * a stable string handle that mirrors the convention used for
 * studies / events / CRFs / subjects elsewhere in the API.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code oid} — the dataset id as a string.</li>
 *   <li>{@code id} — the dataset id as an int (used by mutating
 *       endpoints like {@code POST /datasets/{datasetId}/export}).</li>
 *   <li>{@code name} / {@code description} — saved by the legacy
 *       Create-Dataset wizard.</li>
 *   <li>{@code ownerName} — username of the user who saved the
 *       dataset definition.</li>
 *   <li>{@code dateCreated} — ISO-8601 UTC timestamp.</li>
 *   <li>{@code lastRunAt} — ISO-8601 UTC timestamp of the most-recent
 *       export run; {@code null} when never exported.</li>
 *   <li>{@code fileCount} — number of {@code archived_dataset_file}
 *       rows currently on disk for this dataset.</li>
 * </ul>
 */
public record DatasetDto(
        String oid,
        int id,
        String name,
        String description,
        String ownerName,
        String dateCreated,
        String lastRunAt,
        int fileCount) {
}
