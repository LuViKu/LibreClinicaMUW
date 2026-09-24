/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Observed;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.system.DirectoryUsage;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DR-033 — the two System Status panels that look beyond the app server:
 * the uploaders on the acquisition PCs, and the storage the platform uses.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/uploaders} — the last heartbeat of every
 *       uploader program, classified (ok, warning, disabled, stopped,
 *       offline), plus what actually arrived per device in the last day and
 *       week. The two answer different questions: a program can report
 *       itself healthy while the photographer exports into another folder,
 *       and files can arrive through the upload page while the program on
 *       the PC is dead.</li>
 *   <li>{@code DELETE /api/v1/admin/uploaders/{id}} — forget a program (a PC
 *       that was replaced). A program that is still running re-appears with
 *       its next heartbeat.</li>
 *   <li>{@code GET /api/v1/admin/storage} — the newest hourly scan: bytes and
 *       files per store, the filesystems under them with free space, the
 *       change against the scan a week earlier and, where space is
 *       shrinking, the days until the disk is full; plus the database size
 *       and its largest tables, read live.</li>
 *   <li>{@code POST /api/v1/admin/storage/rescan} — scan now (202).</li>
 * </ul>
 *
 * <p>Sysadmin only, like the rest of the System Status page: anonymous 401,
 * any other session 403.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin tooling",
     description = "Sysadmin-only diagnostic + configuration surfaces — SPA replacement for the legacy admin JSPs.")
@SuppressWarnings("null")
public class SystemHealthApiController {

    private static final Logger LOG = LoggerFactory.getLogger(SystemHealthApiController.class);

    /** The scan the trend compares with: about a week back, and at least this long ago. */
    static final int TREND_DAYS = 7;
    static final double MIN_TREND_SPAN_DAYS = 0.8;
    /** Beyond this the estimate means nothing; the page says "not in sight". */
    static final double MAX_DAYS_UNTIL_FULL = 3650;
    private static final int LARGEST_TABLES = 5;
    private static final int INGEST_DEVICE_ROWS = 20;

    private final DataSource dataSource;
    private final StorageUsageSampler sampler;

    @Autowired
    public SystemHealthApiController(@Qualifier("dataSource") DataSource dataSource, StorageUsageSampler sampler) {
        this.dataSource = dataSource;
        this.sampler = sampler;
    }

    /* ====================================================================== */
    /* Uploaders                                                              */
    /* ====================================================================== */

    @Operation(operationId = "listUploaders", summary = "Uploaders on the acquisition PCs, and what arrived per device")
    @GetMapping(value = "/uploaders", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploaders(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("heartbeatEnabled",
                !"false".equalsIgnoreCase(configField(UploaderHeartbeatApiController.ENABLED_KEY, "true")));
        try (Connection c = dataSource.getConnection()) {
            body.put("uploaders", listUploaders(c));
            body.put("ingestByDevice", ingestByDevice(c));
        } catch (SQLException e) {
            LOG.error("uploaders: could not be read ({})", e.getSQLState());
            return ResponseEntity.status(500).body(Map.of("message", "the uploaders could not be read"));
        }
        return ResponseEntity.ok(body);
    }

    @Operation(operationId = "forgetUploader", summary = "Forget an uploader; it re-appears with its next heartbeat if it still runs")
    @DeleteMapping(value = "/uploaders/{id:[0-9]+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> forgetUploader(@PathVariable("id") int id, HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM uploader_instance WHERE uploader_instance_id = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() == 0) {
                return ResponseEntity.status(404).body(Map.of("message", "no uploader " + id));
            }
        } catch (SQLException e) {
            LOG.error("uploaders: could not remove #{} ({})", id, e.getSQLState());
            return ResponseEntity.status(500).body(Map.of("message", "the uploader could not be removed"));
        }
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        LOG.info("uploaders: #{} removed by sysadmin id={}", id, ub.getId());
        return ResponseEntity.noContent().build();
    }

    private static List<Map<String, Object>> listUploaders(Connection c) throws SQLException {
        String sql = "SELECT uploader_instance_id, kind, display_name, version, first_seen_at, last_seen_at, "
                + " EXTRACT(EPOCH FROM (now() - last_seen_at))::bigint AS seen_age, "
                + " running, stop_reason, enabled, heartbeat_interval_sec, last_activity_at, last_upload_at, "
                + " uploaded_today, pending_files, oldest_pending_minutes, failed_files, "
                + " disk_free_bytes, disk_total_bytes, problems "
                + "FROM uploader_instance ORDER BY display_name, kind, uploader_instance_id";
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long seenAge = Math.max(0L, rs.getLong("seen_age"));
                int interval = rs.getInt("heartbeat_interval_sec");
                boolean running = rs.getBoolean("running");
                boolean enabled = rs.getBoolean("enabled");
                Integer failed = intOrNull(rs, "failed_files");
                Integer oldest = intOrNull(rs, "oldest_pending_minutes");
                Long free = longOrNull(rs, "disk_free_bytes");
                Long total = longOrNull(rs, "disk_total_bytes");
                List<String> problems = UploaderHeartbeat.splitProblems(rs.getString("problems"));
                Status st = UploaderHeartbeat.classify(new Observed(seenAge, interval, running, enabled,
                        problems, failed, oldest, free, total));

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("uploader_instance_id"));
                m.put("kind", rs.getString("kind"));
                m.put("name", rs.getString("display_name"));
                m.put("version", rs.getString("version"));
                m.put("status", st.status());
                m.put("issues", st.issues());
                m.put("stopReason", running ? null : rs.getString("stop_reason"));
                m.put("firstSeenAt", iso(rs.getTimestamp("first_seen_at")));
                m.put("lastSeenAt", iso(rs.getTimestamp("last_seen_at")));
                m.put("secondsSinceSeen", seenAge);
                m.put("heartbeatIntervalSec", interval);
                m.put("running", running);
                m.put("enabled", enabled);
                m.put("lastActivityAt", iso(rs.getTimestamp("last_activity_at")));
                m.put("lastUploadAt", iso(rs.getTimestamp("last_upload_at")));
                m.put("uploadedToday", intOrNull(rs, "uploaded_today"));
                m.put("pendingFiles", intOrNull(rs, "pending_files"));
                m.put("oldestPendingMinutes", oldest);
                m.put("failedFiles", failed);
                m.put("diskFreeBytes", free);
                m.put("diskTotalBytes", total);
                out.add(m);
            }
        }
        return out;
    }

    /**
     * What arrived, per device and ingress, over the last 90 days: when the
     * last file came, and how many in the last day and week. Every ingress
     * is here — the watcher and the bridge, the upload page, the DICOM
     * receiver, the Remidio pull — because the question "is data flowing"
     * does not care which one carried it.
     */
    private static List<Map<String, Object>> ingestByDevice(Connection c) throws SQLException {
        String sql = "SELECT COALESCE(device, '') AS device, source_kind, max(received_at) AS last_received_at, "
                + " count(*) FILTER (WHERE received_at >= now() - interval '24 hours') AS last_24h, "
                + " count(*) FILTER (WHERE received_at >= now() - interval '7 days') AS last_7d "
                + "FROM ingest_item WHERE received_at >= now() - interval '90 days' "
                + "GROUP BY 1, 2 ORDER BY max(received_at) DESC LIMIT " + INGEST_DEVICE_ROWS;
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                String device = rs.getString("device");
                m.put("device", device == null || device.isEmpty() ? null : device);
                m.put("sourceKind", rs.getString("source_kind"));
                m.put("lastReceivedAt", iso(rs.getTimestamp("last_received_at")));
                m.put("last24h", rs.getLong("last_24h"));
                m.put("last7d", rs.getLong("last_7d"));
                out.add(m);
            }
        }
        return out;
    }

    /* ====================================================================== */
    /* Storage                                                                */
    /* ====================================================================== */

    @Operation(operationId = "storageUsage", summary = "Storage per file store and filesystem, the database, and the trend over a week")
    @GetMapping(value = "/storage", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> storage(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scanning", sampler.isScanning());
        try (Connection c = dataSource.getConnection()) {
            Timestamp latest = latestSample(c);
            body.put("sampledAt", iso(latest));
            body.put("scanDurationMs", sampler.lastDurationMs() >= 0 ? sampler.lastDurationMs() : null);
            if (latest == null) {
                // Nothing measured yet (a fresh install, or the first hour
                // after an upgrade): start a scan instead of showing nothing.
                if (!sampler.isScanning()) {
                    sampler.requestRescan();
                    body.put("scanning", true);
                }
                body.put("stores", List.of());
                body.put("filesystems", List.of());
            } else {
                buildStorage(c, latest, body);
            }
            body.put("database", database(c, latest));
        } catch (SQLException e) {
            LOG.error("storage: could not be read ({})", e.getSQLState());
            return ResponseEntity.status(500).body(Map.of("message", "the storage figures could not be read"));
        }
        return ResponseEntity.ok(body);
    }

    @Operation(operationId = "rescanStorage", summary = "Measure the storage now; the result replaces the page's figures when done")
    @PostMapping(value = "/storage/rescan", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rescan(HttpSession session) {
        ResponseEntity<?> guard = requireSysadmin(session);
        if (guard != null) return guard;
        boolean started = sampler.requestRescan();
        return ResponseEntity.accepted().body(Map.of("scanning", true, "started", started));
    }

    private static Timestamp latestSample(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT max(sampled_at) FROM storage_usage_sample WHERE store_key <> ?")) {
            ps.setString(1, StorageUsageSampler.DATABASE_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1) : null;
            }
        }
    }

    /** A row of a scan: one store at one time. */
    private record Sample(String key, String path, boolean present, long used, Long files, boolean complete,
                          String fsKey, String fsType, Long fsTotal, Long fsUsable, Timestamp at) {
    }

    private void buildStorage(Connection c, Timestamp latest, Map<String, Object> body) throws SQLException {
        List<Sample> now = samplesAt(c, latest);
        Map<String, Sample> before = samplesAround(c, latest);

        List<Map<String, Object>> stores = new ArrayList<>();
        Map<String, Map<String, Object>> filesystems = new LinkedHashMap<>();
        Map<String, List<String>> storesByFs = new HashMap<>();
        for (Sample s : now) {
            if (StorageUsageSampler.DATABASE_KEY.equals(s.key())) continue;
            Sample b = before.get(s.key());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("path", s.path());
            m.put("present", s.present());
            m.put("usedBytes", s.used());
            m.put("fileCount", s.files());
            m.put("complete", s.complete());
            m.put("fsKey", s.fsKey());
            m.put("containerLayer", DirectoryUsage.isContainerLayer(s.fsType()));
            m.put("usedBytesBefore", b == null ? null : b.used());
            m.put("beforeAt", b == null ? null : iso(b.at()));
            stores.add(m);

            if (s.fsKey() != null && s.fsTotal() != null) {
                storesByFs.computeIfAbsent(s.fsKey(), k -> new ArrayList<>()).add(s.key());
                filesystems.computeIfAbsent(s.fsKey(), k -> filesystem(s, b));
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : filesystems.entrySet()) {
            e.getValue().put("stores", storesByFs.getOrDefault(e.getKey(), List.of()));
        }
        body.put("stores", stores);
        body.put("filesystems", new ArrayList<>(filesystems.values()));
    }

    private static Map<String, Object> filesystem(Sample s, Sample before) {
        Map<String, Object> fs = new LinkedHashMap<>();
        fs.put("key", s.fsKey());
        fs.put("type", s.fsType());
        fs.put("containerLayer", DirectoryUsage.isContainerLayer(s.fsType()));
        long total = s.fsTotal();
        long usable = s.fsUsable() == null ? 0L : s.fsUsable();
        fs.put("totalBytes", total);
        fs.put("usableBytes", usable);
        fs.put("usedPercent", total > 0 ? Math.round(1000.0 * (total - usable) / total) / 10.0 : null);
        Long usableBefore = before != null && s.fsKey().equals(before.fsKey()) ? before.fsUsable() : null;
        fs.put("usableBytesBefore", usableBefore);
        fs.put("beforeAt", usableBefore == null ? null : iso(before.at()));
        fs.put("daysUntilFull", usableBefore == null ? null
                : daysUntilFull(usableBefore, usable, spanDays(before.at(), s.at())));
        return fs;
    }

    /**
     * Days until nothing is usable, at the rate free space shrank between two
     * scans; null when it did not shrink, or the estimate would be absurdly far.
     */
    static Double daysUntilFull(long usableBefore, long usableNow, double spanDays) {
        if (spanDays < MIN_TREND_SPAN_DAYS) return null;
        double perDay = (usableBefore - usableNow) / spanDays;
        if (perDay <= 0) return null;
        double days = usableNow / perDay;
        if (days > MAX_DAYS_UNTIL_FULL) return null;
        return Math.floor(days * 10) / 10.0;
    }

    private static double spanDays(Timestamp from, Timestamp to) {
        return (to.getTime() - from.getTime()) / 86_400_000.0;
    }

    private static List<Sample> samplesAt(Connection c, Timestamp at) throws SQLException {
        String sql = "SELECT store_key, store_path, present, used_bytes, file_count, complete, fs_key, fs_type, "
                + " fs_total_bytes, fs_usable_bytes, sampled_at FROM storage_usage_sample "
                + "WHERE sampled_at = ? ORDER BY storage_usage_sample_id";
        List<Sample> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, at);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(sample(rs));
            }
        }
        return out;
    }

    /**
     * Per store, the scan closest to a week before {@code latest}, taken
     * from between eight days and twenty hours earlier — a young install
     * compares with what it has, and nothing compares with itself.
     */
    private static Map<String, Sample> samplesAround(Connection c, Timestamp latest) throws SQLException {
        String sql = "SELECT DISTINCT ON (store_key) store_key, store_path, present, used_bytes, file_count, "
                + " complete, fs_key, fs_type, fs_total_bytes, fs_usable_bytes, sampled_at "
                + "FROM storage_usage_sample "
                + "WHERE sampled_at BETWEEN ?::timestamp - interval '8 days' AND ?::timestamp - interval '20 hours' "
                + "ORDER BY store_key, abs(EXTRACT(EPOCH FROM (sampled_at - (?::timestamp - interval '"
                + TREND_DAYS + " days'))))";
        Map<String, Sample> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, latest);
            ps.setTimestamp(2, latest);
            ps.setTimestamp(3, latest);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Sample s = sample(rs);
                    out.put(s.key(), s);
                }
            }
        }
        return out;
    }

    private static Sample sample(ResultSet rs) throws SQLException {
        return new Sample(rs.getString("store_key"), rs.getString("store_path"), rs.getBoolean("present"),
                rs.getLong("used_bytes"), longOrNull(rs, "file_count"), rs.getBoolean("complete"),
                rs.getString("fs_key"), rs.getString("fs_type"), longOrNull(rs, "fs_total_bytes"),
                longOrNull(rs, "fs_usable_bytes"), rs.getTimestamp("sampled_at"));
    }

    /** The database, read live, with its size a week back from the samples. */
    private static Map<String, Object> database(Connection c, Timestamp latest) throws SQLException {
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("sizeBytes", StorageUsageSampler.databaseSize(c));
        Long before = null;
        String beforeAt = null;
        if (latest != null) {
            Sample b = samplesAround(c, latest).get(StorageUsageSampler.DATABASE_KEY);
            if (b != null) {
                before = b.used();
                beforeAt = iso(b.at());
            }
        }
        db.put("sizeBytesBefore", before);
        db.put("beforeAt", beforeAt);
        List<Map<String, Object>> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT relname, pg_total_relation_size(relid) AS bytes FROM pg_catalog.pg_statio_user_tables "
                        + "ORDER BY 2 DESC LIMIT " + LARGEST_TABLES);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("name", rs.getString("relname"));
                t.put("bytes", rs.getLong("bytes"));
                tables.add(t);
            }
        }
        db.put("largestTables", tables);
        return db;
    }

    /* ====================================================================== */
    /* Helpers                                                                */
    /* ====================================================================== */

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    /** {@code datainfo.properties} read, overridable for tests. */
    protected String configField(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable outside a booted container.
        }
        return fallback;
    }

    private static ResponseEntity<?> requireSysadmin(HttpSession session) {
        UserAccountBean ub = (UserAccountBean) session.getAttribute("userBean");
        if (ub == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Authentication required."));
        }
        if (!ub.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message", "Sysadmin privilege required."));
        }
        return null;
    }
}
