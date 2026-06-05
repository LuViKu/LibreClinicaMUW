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
 * <p>One generated export file row, surfaced in the SPA's per-dataset
 * "Files" sub-table. Mirrors {@code archived_dataset_file} closely:
 *
 * <ul>
 *   <li>{@code id} — primary key of the archived_dataset_file row.</li>
 *   <li>{@code name} — the zip filename (e.g.
 *       {@code MyDataset_odm.xml.zip}).</li>
 *   <li>{@code formatName} — short human label for the export format
 *       ({@code "ODM"}, {@code "CSV"}, {@code "Excel"}, …).</li>
 *   <li>{@code sizeBytes} — raw byte size on disk (the legacy DAO
 *       stores this as an int — extracts &gt; 2 GiB are out of scope
 *       for v1).</li>
 *   <li>{@code generatedAt} — ISO-8601 UTC timestamp.</li>
 *   <li>{@code downloadUrl} — relative URL the SPA can hit to stream
 *       the file. Points at the new
 *       {@code /pages/api/v1/archived-files/{id}/download}
 *       endpoint defined alongside.</li>
 * </ul>
 */
public record ArchivedFileDto(
        int id,
        String name,
        String formatName,
        long sizeBytes,
        String generatedAt,
        String downloadUrl) {
}
