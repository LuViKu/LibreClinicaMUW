/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics.CrtComputeService;

/**
 * nAMD treat-and-extend Slice 3 (2026-06-20) — copy AI fluid
 * metrics from {@code retinal_inference_result.output_payload} into
 * the matching {@code item_data} rows of an event_crf.
 *
 * <p>Triggered explicitly via {@code POST /event-crfs/{id}:autoPopulateRetinal}.
 * For each completed retinal_inference_job linked to the event_crf,
 * the service reads the JSONB output payload + the job's laterality
 * (OD / OS) and writes per-eye fluid metrics into the
 * {@code NAMD_OD_*_MM3} / {@code NAMD_OS_*_MM3} items defined by
 * Slice 2 + the AI-items extension.
 *
 * <p>Writes are idempotent — re-running for the same job updates the
 * existing item_data row instead of duplicating it. The
 * {@code item_data.source_kind} + {@code source_retinal_job_id}
 * columns added in {@code lc-muw-2026-06-20-item-data-source-retinal-job.xml}
 * stamp the row as auto-populated so the SPA can render the "AI"
 * badge + tooltip link back to the source job.
 *
 * <p>Operator overrides land as regular item_data UPDATEs through
 * the standard CRF-entry path; the existing
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AuditTypeIds}
 * audit-write conventions still apply.
 */
@Service
public class RetinalResultItemDataPopulator {

    private static final Logger LOG = LoggerFactory.getLogger(RetinalResultItemDataPopulator.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE_KIND = "retinal_inference";

    /**
     * Mapping from fluid runner's output_payload JSON key to the OD-eye
     * NAMD item OID. The OS variant is derived by replacing {@code _OD_}
     * with {@code _OS_} since the items are laterality-coded.
     */
    private static final Map<String, String> OD_METRIC_TO_ITEM_OID;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("irf_mm3", "I_NAMD_OD_IRF_MM3");
        m.put("srf_mm3", "I_NAMD_OD_SRF_MM3");
        m.put("ped_mm3", "I_NAMD_OD_PED_MM3");
        m.put("total_fluid_volume_mm3", "I_NAMD_OD_TOTAL_FLUID_MM3");
        OD_METRIC_TO_ITEM_OID = Map.copyOf(m);
    }

    private final DataSource dataSource;

    /**
     * 2026-06-24 — CRT compute service. Optional so the existing
     * single-arg ctor used by sliced ITs stays valid. When wired,
     * the populator additionally derives + writes per-eye CRT
     * (central 1mm) into {@code OD/OS_CRT_CENTRAL_1MM_UM} items
     * after the fluid items land. When null, the CRT pass is
     * skipped silently.
     */
    private CrtComputeService crtComputeService;

    public RetinalResultItemDataPopulator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Autowired(required = false)
    public void setCrtComputeService(CrtComputeService crtComputeService) {
        this.crtComputeService = crtComputeService;
    }

    /**
     * Outcome carrier for a populate run — surfaces what the controller
     * should report back to the SPA.
     */
    public record PopulateResult(int eventCrfId,
                                 int jobsProcessed,
                                 int rowsWritten,
                                 List<String> warnings) {}

    /**
     * Auto-populate item_data for every completed retinal_inference_job
     * linked to {@code eventCrfId}. Returns a {@link PopulateResult}
     * summarising the writes; throws on infrastructure failure (SQL,
     * malformed JSON).
     */
    public PopulateResult populateForEventCrf(int eventCrfId, int operatorUserId) {
        List<JobMetrics> jobs = loadCompletedJobs(eventCrfId);
        int rowsWritten = 0;
        java.util.ArrayList<String> warnings = new java.util.ArrayList<>();

        for (JobMetrics job : jobs) {
            String lateralityToken = lateralityToken(job.laterality);
            if (lateralityToken == null) {
                warnings.add("Job " + job.jobId + " has unsupported laterality '"
                        + job.laterality + "' — skipped.");
                continue;
            }
            for (Map.Entry<String, String> e : OD_METRIC_TO_ITEM_OID.entrySet()) {
                String metricKey = e.getKey();
                String odOid = e.getValue();
                String targetOid = odOid.replace("_OD_", "_" + lateralityToken + "_");
                Double value = readNumeric(job.payload, metricKey);
                if (value == null) {
                    // Metric not present in this job's payload — skip silently
                    // (not every task emits every key, and the runner may add
                    // new keys we don't yet map).
                    continue;
                }
                try {
                    writeItemData(eventCrfId, targetOid, value,
                            job.jobId, operatorUserId);
                    rowsWritten++;
                } catch (SQLException sqlEx) {
                    warnings.add("Failed to write " + targetOid + " from job "
                            + job.jobId + ": " + sqlEx.getMessage());
                    LOG.warn("RetinalResultItemDataPopulator: write failed for ecrf={} item={} job={}",
                            eventCrfId, targetOid, job.jobId, sqlEx);
                }
            }
        }
        // 2026-06-24 — CRT (central 1 mm) pass. Runs only when the
        // service is wired AND the event_crf resolves to a study_event
        // (which it always does for real visits — guarded for safety).
        // Per-eye CRT is derived from the paired GA + BM done jobs on
        // the same study_event; only events with BOTH present yield a
        // value (the user-stated contract). All-or-nothing failure here
        // doesn't abort the fluid writes that already succeeded.
        if (crtComputeService != null) {
            try {
                Integer studyEventId = resolveStudyEventId(eventCrfId);
                if (studyEventId != null) {
                    Map<CrtComputeService.Eye, CrtComputeService.Result> perEye =
                            crtComputeService.computeForStudyEvent(studyEventId);
                    for (Map.Entry<CrtComputeService.Eye, CrtComputeService.Result> entry : perEye.entrySet()) {
                        CrtComputeService.Eye eye = entry.getKey();
                        CrtComputeService.Result r = entry.getValue();
                        String oid = eye == CrtComputeService.Eye.OD
                                ? "I_OD_CRT_CENTRAL_1MM_UM"
                                : "I_OS_CRT_CENTRAL_1MM_UM";
                        try {
                            writeItemData(eventCrfId, oid, r.crtMicrons(),
                                    r.gaJobId(), operatorUserId);
                            rowsWritten++;
                            writeCrtAuditRow(eventCrfId, eye, r, operatorUserId);
                        } catch (SQLException sqlEx) {
                            warnings.add("Failed to write " + oid + " from GA job " + r.gaJobId()
                                    + " + BM job " + r.bmJobId() + ": " + sqlEx.getMessage());
                            LOG.warn("CRT write failed for ecrf={} eye={} ga={} bm={}: {}",
                                    eventCrfId, eye, r.gaJobId(), r.bmJobId(), sqlEx.getMessage());
                        }
                    }
                }
            } catch (RuntimeException crtEx) {
                // CrtComputeService swallows MetricComputationException
                // internally + returns empty results — anything raised
                // here is a genuine infrastructure failure.
                warnings.add("CRT compute failed for ecrf=" + eventCrfId + ": " + crtEx.getMessage());
                LOG.warn("CRT compute failed for ecrf={}: {}", eventCrfId, crtEx.getMessage(), crtEx);
            }
        }

        LOG.info("RetinalResultItemDataPopulator: ecrf={} jobs={} rows_written={}",
                eventCrfId, jobs.size(), rowsWritten);
        return new PopulateResult(eventCrfId, jobs.size(), rowsWritten, List.copyOf(warnings));
    }

    /**
     * Resolve the study_event_id of the event_crf so the CRT compute
     * can look up sibling jobs on the same visit. Returns null if the
     * event_crf is somehow detached (defensive — shouldn't happen for
     * real visits).
     */
    private Integer resolveStudyEventId(int eventCrfId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_event_id FROM event_crf WHERE event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException sqlEx) {
            LOG.warn("resolveStudyEventId failed for ecrf={}: {}", eventCrfId, sqlEx.getMessage());
            return null;
        }
    }

    /**
     * Write the {@code RETINAL_CRT_AUTOPOPULATE} audit_log_event row.
     * Best-effort — a write failure here doesn't roll back the
     * item_data row (the source_retinal_job_id column already
     * provides the value's primary lineage; the audit row is an
     * audit-timeline convenience). Audit type id 122 is seeded by
     * {@code lc-muw-2026-06-24-namd-visit-crt-items.xml}.
     */
    private void writeCrtAuditRow(int eventCrfId, CrtComputeService.Eye eye,
                                  CrtComputeService.Result r, int operatorUserId) {
        String oldValue = "eye=" + eye + ";ga_job_id=" + r.gaJobId() + ";bm_job_id=" + r.bmJobId();
        String newValue = "value_um=" + formatValue(r.crtMicrons())
                + ";pixels_in_disk=" + r.pixelsInDisk();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                             + "  user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                             + "VALUES (122, NOW(), ?, 'event_crf', ?, ?, ?, ?)")) {
            ps.setInt(1, operatorUserId);
            ps.setInt(2, eventCrfId);
            ps.setString(3, eye == CrtComputeService.Eye.OD
                    ? "OD_CRT_CENTRAL_1MM_UM" : "OS_CRT_CENTRAL_1MM_UM");
            ps.setString(4, oldValue);
            ps.setString(5, newValue);
            ps.executeUpdate();
        } catch (SQLException sqlEx) {
            LOG.warn("RETINAL_CRT_AUTOPOPULATE audit-write failed for ecrf={} eye={}: {}",
                    eventCrfId, eye, sqlEx.getMessage());
        }
    }

    private List<JobMetrics> loadCompletedJobs(int eventCrfId) {
        java.util.ArrayList<JobMetrics> out = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT j.job_id, j.eye_laterality, r.output_payload "
                             + "  FROM retinal_inference_job j "
                             + "  JOIN retinal_inference_result r ON r.job_id = j.job_id "
                             + " WHERE j.event_crf_id = ? AND j.status = 'done' "
                             + " ORDER BY j.job_id ASC")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long jobId = rs.getLong("job_id");
                    String laterality = rs.getString("eye_laterality");
                    String payloadJson = rs.getString("output_payload");
                    JsonNode payload;
                    try {
                        payload = (payloadJson == null || payloadJson.isBlank())
                                ? JSON.createObjectNode()
                                : JSON.readTree(payloadJson);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException jsonEx) {
                        LOG.warn("Malformed output_payload JSON for job {}: {}",
                                jobId, jsonEx.getMessage());
                        payload = JSON.createObjectNode();
                    }
                    out.add(new JobMetrics(jobId, laterality, payload));
                }
            }
        } catch (SQLException sqlEx) {
            throw new IllegalStateException(
                    "Failed to load retinal jobs for event_crf=" + eventCrfId, sqlEx);
        }
        return out;
    }

    private static String lateralityToken(String laterality) {
        if (laterality == null) return null;
        String upper = laterality.trim().toUpperCase();
        return switch (upper) {
            case "OD", "OS" -> upper;
            case "R", "RIGHT" -> "OD";
            case "L", "LEFT" -> "OS";
            default -> null;
        };
    }

    private static Double readNumeric(JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        if (node == null || node.isNull() || !node.isNumber()) return null;
        return node.asDouble();
    }

    /**
     * Idempotent upsert of an {@code item_data} row keyed by
     * (event_crf_id, item_id). When a row from the same source job
     * already exists, UPDATE it; when a row from a DIFFERENT source
     * (or operator entry) exists, leave it alone + emit a warning
     * (operator overrides win).
     */
    private void writeItemData(int eventCrfId, String itemOid,
                               double value, long sourceJobId,
                               int operatorUserId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Integer itemId = resolveItemId(c, itemOid);
            if (itemId == null) {
                throw new SQLException(
                        "Item OID '" + itemOid + "' not found — has the NAMD_VISIT AI section been seeded?");
            }
            Integer existingId = findExistingItemDataId(c, eventCrfId, itemId);
            String valueStr = formatValue(value);
            if (existingId == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO item_data "
                                + "  (item_id, event_crf_id, status_id, value, "
                                + "   date_created, owner_id, ordinal, deleted, "
                                + "   source_kind, source_retinal_job_id) "
                                + "VALUES (?, ?, 1, ?, NOW(), ?, 1, false, ?, ?)")) {
                    ps.setInt(1, itemId);
                    ps.setInt(2, eventCrfId);
                    ps.setString(3, valueStr);
                    ps.setInt(4, operatorUserId);
                    ps.setString(5, SOURCE_KIND);
                    ps.setLong(6, sourceJobId);
                    ps.executeUpdate();
                }
            } else {
                // Only auto-overwrite when the existing row came from
                // THIS source (re-run with new metrics) or from an
                // earlier auto-populate. Operator-entered rows are
                // never overwritten by the auto-populator.
                Long existingSourceJob = readSourceJobId(c, existingId);
                if (existingSourceJob == null) {
                    LOG.info("Skip auto-overwrite: ecrf={} item={} carries operator value",
                            eventCrfId, itemOid);
                    return;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE item_data "
                                + "   SET value = ?, "
                                + "       date_updated = NOW(), "
                                + "       update_id = ?, "
                                + "       source_kind = ?, "
                                + "       source_retinal_job_id = ? "
                                + " WHERE item_data_id = ?")) {
                    ps.setString(1, valueStr);
                    ps.setInt(2, operatorUserId);
                    ps.setString(3, SOURCE_KIND);
                    ps.setLong(4, sourceJobId);
                    ps.setInt(5, existingId);
                    ps.executeUpdate();
                }
            }
        }
    }

    private static Integer resolveItemId(Connection c, String itemOid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_id FROM item WHERE oc_oid = ?")) {
            ps.setString(1, itemOid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private static Integer findExistingItemDataId(Connection c, int eventCrfId, int itemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_data_id FROM item_data "
                        + "WHERE event_crf_id = ? AND item_id = ? AND COALESCE(deleted, false) = false "
                        + "ORDER BY ordinal ASC LIMIT 1")) {
            ps.setInt(1, eventCrfId);
            ps.setInt(2, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private static Long readSourceJobId(Connection c, int itemDataId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_retinal_job_id FROM item_data WHERE item_data_id = ?")) {
            ps.setInt(1, itemDataId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static String formatValue(double v) {
        // Item data values are stored as varchar; use a stable
        // representation that the form-rendering layer parses back
        // without locale ambiguity.
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    /** Slim row carrier between {@link #loadCompletedJobs} and {@link #populateForEventCrf}. */
    private record JobMetrics(long jobId, String laterality, JsonNode payload) {}
}
