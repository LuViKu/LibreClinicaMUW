/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.time.Instant;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.DatasetBean;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>Stand-in {@link ExportFileMaterializer} used until Phase 1's
 * synchronous extract path ships its production materializer bean.
 * Returns a record with zero bytes + a placeholder fileReference so
 * the queued → done transition is testable end-to-end without
 * standing up the full extract pipeline.
 *
 * <p>Phase 1 will override this by registering a higher-priority
 * {@code @Bean} of type {@link ExportFileMaterializer} (Spring's bean
 * resolution finds it before this fallback, which is instantiated
 * directly by the runner via {@code new …Materializer()} when no
 * managed bean is present).
 */
public class PlaceholderExportFileMaterializer implements ExportFileMaterializer {

    @Override
    public Result materialize(DatasetBean dataset, String format, int submittedByUserId) {
        String stem = (dataset == null || dataset.getName() == null || dataset.getName().isBlank())
                ? "dataset_" + (dataset == null ? 0 : dataset.getId())
                : dataset.getName().replaceAll("[^A-Za-z0-9_-]", "_");
        String name = stem + "_" + Instant.now().toEpochMilli() + "." + extensionFor(format);
        // The file path is intentionally not on disk — Phase 1 owns
        // the real file lifecycle (writing under generalFileDir +
        // zipping). The non-empty fileReference keeps the DB shape
        // valid and the placeholder visible in the SPA's job list.
        String fileReference = "/placeholder/" + name;
        return new Result(name, fileReference, 0L);
    }

    private static String extensionFor(String format) {
        if (format == null) return "txt";
        switch (format.toLowerCase()) {
            case "odm":
            case "xml": return "xml";
            case "csv": return "csv";
            case "tab": return "tab";
            case "excel":
            case "xls": return "xls";
            case "xlsx": return "xlsx";
            case "pdf": return "pdf";
            default: return "txt";
        }
    }
}
