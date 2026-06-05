/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.extract;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase E.6 — Audit hook for dataset-export events.
 *
 * <p>Centralises the direct-JDBC {@code INSERT INTO audit_log_event}
 * pattern callers in the export pipeline use when a dataset file leaves
 * the system boundary (download via the SPA, retention GC sweep, etc.).
 *
 * <p>The on-disk side of the export is owned by the existing
 * {@link GenerateExtractFileService} / XSLT pipeline — this service
 * only records that the egress happened so operators can review it in
 * the SPA's Audit Log timeline.
 *
 * <p>Mirrors the direct-JDBC pattern already used by
 * {@code MeApiController.emitProfileAudit} and
 * {@code StudiesApiController.writeStudyFieldAudit}: the legacy
 * {@code AuditEventDAO.create} writes to the {@code audit_event} table
 * (not {@code audit_log_event}) and drops the type / value / entity_name
 * columns the SPA's {@code AuditApiController} reads.
 */
public final class ExportAuditService {

    private static final Logger LOG = LoggerFactory.getLogger(ExportAuditService.class);

    /**
     * audit_log_event_type_id for dataset-export events. Seeded in
     * Liquibase changeset {@code lc-muw-2026-06-05-audit-event-type-dataset-export.xml}.
     * {@code AuditApiController.variantForType} maps this id into the
     * "admin" bucket so it surfaces under the existing Admin filter.
     */
    public static final int AUDIT_TYPE_DATASET_EXPORTED = 52;

    private ExportAuditService() { /* static helpers only */ }

    /**
     * Emit one {@code audit_log_event} row for a dataset export.
     *
     * <p>Schema-side: writes a row with
     * <ul>
     *   <li>{@code audit_log_event_type_id = 52}</li>
     *   <li>{@code audit_date = now()}</li>
     *   <li>{@code user_id} — the operator who triggered the export
     *       (or a system user for retention GC passes)</li>
     *   <li>{@code audit_table = 'dataset'}</li>
     *   <li>{@code entity_id = datasetId}</li>
     *   <li>{@code entity_name = "{studyName}/{datasetName}"} so the
     *       SPA's Audit Log row exposes the right context as the
     *       {@code details} chip. Caller passes a pre-joined value;
     *       a future helper can split it if needed.</li>
     *   <li>{@code new_value = "{format}: {sizeBytes} bytes → archived_dataset_file:{archivedFileId}"}
     *       — caller-readable inline body the SPA renders verbatim.</li>
     * </ul>
     *
     * <p>Failures are logged + swallowed: a dropped audit row should
     * not make the user think the export itself failed.
     *
     * @param ds              the application {@code DataSource}; never null
     * @param userId          actor user_id (or a system user_id for GC passes)
     * @param datasetId       PK of the {@code dataset} row that was exported
     * @param studyName       display name of the owning study
     * @param format          export format label (e.g. {@code "CSV"}, {@code "ODM-XML"})
     * @param sizeBytes       on-disk size of the produced file (bytes)
     * @param archivedFileId  PK of the {@code archived_dataset_file} row
     *                        the export produced — {@code -1} when the
     *                        export was streamed directly without persisting
     */
    public static void emitExportAudit(DataSource ds,
                                       int userId,
                                       int datasetId,
                                       String studyName,
                                       String format,
                                       long sizeBytes,
                                       int archivedFileId) {
        if (ds == null) {
            LOG.warn("emitExportAudit called with null DataSource — skipping audit write for dataset_id={}",
                    datasetId);
            return;
        }
        String entityName = (studyName == null ? "" : studyName);
        String newValue = String.format(
                "%s: %d bytes → archived_dataset_file:%d",
                (format == null ? "" : format),
                Math.max(0L, sizeBytes),
                archivedFileId);

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (?, now(), ?, 'dataset', ?, ?, NULL, ?)")) {
            ps.setInt(1, AUDIT_TYPE_DATASET_EXPORTED);
            ps.setInt(2, userId);
            ps.setInt(3, datasetId);
            ps.setString(4, entityName);
            ps.setString(5, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Don't propagate — the export has already succeeded; a
            // missed audit row shouldn't make the user think the
            // download failed.
            LOG.warn("Failed to write dataset-export audit row for dataset_id={} user_id={}: {}",
                    datasetId, userId, e.getMessage());
        }
    }
}
