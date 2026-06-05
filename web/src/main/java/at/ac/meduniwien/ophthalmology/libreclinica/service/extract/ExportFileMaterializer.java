/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>Seam between the async runner ({@link ExportJobRunner}) and the
 * synchronous extract pipeline Phase 1 owns. The runner does not
 * care how the file gets produced — only that, given a dataset +
 * format string, eventually a file lives on disk and we know its
 * metadata.
 *
 * <p>Phase 1 ships {@code SynchronousExportMaterializer} (which calls
 * {@link GenerateExtractFileService#createODMFile(String, long, String,
 * DatasetBean, int, String, String)} and friends). Until Phase 1
 * lands the runner falls back to
 * {@link PlaceholderExportFileMaterializer} so the queued → done
 * state transition is observable end-to-end (and the unit test on
 * the runner stays decoupled from the heavy extract pipeline).
 */
public interface ExportFileMaterializer {

    /**
     * Generate the export file for {@code dataset} in {@code format}
     * and return its metadata.
     *
     * @param dataset the dataset row to export
     * @param format  the format string the controller stored on the
     *                queued row ({@code "odm" | "csv" | "tab" |
     *                "excel" | "pdf"}).
     * @param submittedByUserId the user_id from the queued row.
     */
    Result materialize(DatasetBean dataset, String format, int submittedByUserId) throws Exception;

    /** Metadata for the produced file. */
    record Result(String name, String fileReference, long fileSize) {}
}
