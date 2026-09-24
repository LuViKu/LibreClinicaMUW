/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Invalid;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.UploaderHeartbeat.Parsed;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DR-033 — the uploaders on the acquisition PCs say they are alive.
 *
 * <p>The Export Watcher (Clarus and Spectralis PCs) and the Optomed Bridge
 * (the PC the Lumo docks to) are tray programs on Windows machines the
 * platform cannot see. Every couple of minutes each posts one small JSON
 * document here — whether it is running and switched on, how many files wait
 * and for how long, what failed, how full its disk is — and the System
 * Status page shows the last one per program, and when it came.
 *
 * <p><strong>Posture.</strong> Unauthenticated, like the upload front door
 * the same programs already use, and for the same reason: nothing is
 * entered on those PCs but a URL. What keeps it honest:
 * <ul>
 *   <li>Nothing about a patient travels here — counts, ages in seconds,
 *       disk figures, coded problems, the PC's name and a version. Every
 *       field is bounded ({@link UploaderHeartbeat}); the body is capped at
 *       {@value #MAX_BODY_BYTES} bytes before it is parsed.</li>
 *   <li>The row a heartbeat updates is found by a random instance id the
 *       program generated for itself on first start and never shows. Knowing
 *       a PC's name is not enough to report on its behalf; only a copy of its
 *       settings file is.</li>
 *   <li>New programs are admitted up to {@value #MAX_INSTANCES}; beyond that
 *       a new one is refused (429) and existing ones keep reporting. A
 *       sysadmin clears stale rows on the page.</li>
 *   <li>Its own per-client budget in {@code PublicOctUploadRateLimitFilter},
 *       apart from the upload page's lookups — a heartbeat must never cost
 *       the watcher a {@code /resolve}.</li>
 *   <li>{@value #ENABLED_KEY}{@code =false} turns it off: 404, as if it did
 *       not exist.</li>
 * </ul>
 *
 * <p>Nothing a program sends is logged: new registrations are logged by row
 * id and program kind (a validated lower-case token), never by name.
 */
@RestController
@RequestMapping("/api/v1/device/uploader")
@Tag(name = "Device uploaders",
     description = "Heartbeats from the upload programs on acquisition PCs (DR-033). No patient data.")
// Heritage null-analysis suppression, as in the sibling controllers.
@SuppressWarnings("null")
public class UploaderHeartbeatApiController {

    private static final Logger LOG = LoggerFactory.getLogger(UploaderHeartbeatApiController.class);

    static final String ENABLED_KEY = "core.uploaderHealth.heartbeat.enabled";
    static final int MAX_BODY_BYTES = 16 * 1024;
    static final int MAX_INSTANCES = 50;
    /** A refused registration is logged at most this often, so a flood cannot fill the log. */
    private static final long CAP_WARN_EVERY_MS = 10 * 60_000L;

    private final DataSource dataSource;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile long lastCapWarn;

    @Autowired
    public UploaderHeartbeatApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Operation(operationId = "recordUploaderHeartbeat", summary = "Record an uploader's heartbeat",
            description = "Upserts the program's row by its instance id. 200 recorded; 400 malformed; "
                    + "404 heartbeats switched off; 413 body over 16 KB; 429 too many programs.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = HeartbeatDoc.class))))
    @PostMapping(value = "/heartbeat", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> recordHeartbeat(HttpServletRequest request) {
        if (!enabled()) {
            return ResponseEntity.status(404).body(Map.of("message", "not found"));
        }
        byte[] body;
        try {
            body = readBounded(request.getInputStream(), MAX_BODY_BYTES);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "the heartbeat could not be read"));
        }
        if (body == null) {
            return ResponseEntity.status(413).body(Map.of("message", "a heartbeat is at most 16 KB"));
        }
        Parsed hb;
        try {
            JsonNode node = mapper.readTree(body);
            hb = UploaderHeartbeat.parse(node);
        } catch (Invalid e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "the heartbeat is not JSON"));
        }

        try (Connection c = dataSource.getConnection()) {
            boolean known = exists(c, hb.instanceUid());
            if (!known && count(c) >= MAX_INSTANCES) {
                warnCapReached();
                return ResponseEntity.status(429).body(Map.of("message",
                        "the platform already follows " + MAX_INSTANCES
                                + " uploaders; remove stale ones on the System Status page"));
            }
            int id = upsert(c, hb);
            if (!known) LOG.info("uploader heartbeat: new {} registered as #{}", hb.kind(), id);
            return ResponseEntity.ok(Map.of("recorded", true));
        } catch (SQLException e) {
            LOG.error("uploader heartbeat: could not be recorded ({})", e.getSQLState());
            return ResponseEntity.status(500).body(Map.of("message", "the heartbeat could not be recorded"));
        }
    }

    /**
     * {@code datainfo.properties} read, overridable for tests (CoreResources
     * is not initialised in MockMvc runs). On unless explicitly {@code false}.
     */
    protected String configField(String key, String fallback) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable outside a booted container.
        }
        return fallback;
    }

    private boolean enabled() {
        return !"false".equalsIgnoreCase(configField(ENABLED_KEY, "true"));
    }

    private void warnCapReached() {
        long now = System.currentTimeMillis();
        if (now - lastCapWarn > CAP_WARN_EVERY_MS) {
            lastCapWarn = now;
            LOG.warn("uploader heartbeat: refused a new program — {} are already registered", MAX_INSTANCES);
        }
    }

    /** The whole body, or null when it is longer than {@code max}. */
    static byte[] readBounded(InputStream in, int max) throws IOException {
        byte[] buf = in.readNBytes(max + 1);
        return buf.length > max ? null : buf;
    }

    private static boolean exists(Connection c, String uid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM uploader_instance WHERE instance_uid = ?")) {
            ps.setString(1, uid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static int count(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM uploader_instance");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * One statement, so two heartbeats of a new program racing each other
     * end as one row. Ages become timestamps here, on the server's clock.
     */
    private static int upsert(Connection c, Parsed hb) throws SQLException {
        String sql = "INSERT INTO uploader_instance (instance_uid, kind, display_name, version, "
                + " first_seen_at, last_seen_at, running, stop_reason, enabled, heartbeat_interval_sec, "
                + " last_activity_at, last_upload_at, uploaded_today, pending_files, oldest_pending_minutes, "
                + " failed_files, disk_free_bytes, disk_total_bytes, problems) "
                + "VALUES (?, ?, ?, ?, now(), now(), ?, ?, ?, ?, "
                + " now() - (?::bigint * interval '1 second'), now() - (?::bigint * interval '1 second'), "
                + " ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (instance_uid) DO UPDATE SET "
                + " kind = EXCLUDED.kind, display_name = EXCLUDED.display_name, version = EXCLUDED.version, "
                + " last_seen_at = now(), running = EXCLUDED.running, stop_reason = EXCLUDED.stop_reason, "
                + " enabled = EXCLUDED.enabled, heartbeat_interval_sec = EXCLUDED.heartbeat_interval_sec, "
                // An activity or upload the program no longer knows of (it was
                // restarted) must not erase the one the server already has.
                + " last_activity_at = COALESCE(EXCLUDED.last_activity_at, uploader_instance.last_activity_at), "
                + " last_upload_at = COALESCE(EXCLUDED.last_upload_at, uploader_instance.last_upload_at), "
                + " uploaded_today = EXCLUDED.uploaded_today, pending_files = EXCLUDED.pending_files, "
                + " oldest_pending_minutes = EXCLUDED.oldest_pending_minutes, failed_files = EXCLUDED.failed_files, "
                + " disk_free_bytes = EXCLUDED.disk_free_bytes, disk_total_bytes = EXCLUDED.disk_total_bytes, "
                + " problems = EXCLUDED.problems "
                + "RETURNING uploader_instance_id";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, hb.instanceUid());
            ps.setString(i++, hb.kind());
            ps.setString(i++, hb.name());
            ps.setString(i++, hb.version());
            ps.setBoolean(i++, hb.running());
            ps.setString(i++, hb.stopReason());
            ps.setBoolean(i++, hb.enabled());
            ps.setInt(i++, hb.intervalSec());
            setLong(ps, i++, hb.secondsSinceActivity());
            setLong(ps, i++, hb.secondsSinceUpload());
            setInt(ps, i++, hb.uploadedToday());
            setInt(ps, i++, hb.pendingFiles());
            setInt(ps, i++, hb.oldestPendingMinutes());
            setInt(ps, i++, hb.failedFiles());
            setLong(ps, i++, hb.diskFreeBytes());
            setLong(ps, i++, hb.diskTotalBytes());
            ps.setString(i, UploaderHeartbeat.joinProblems(hb.problems()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, v);
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, Types.INTEGER);
        else ps.setInt(i, v);
    }

    /** The heartbeat document, for the API description only — the handler reads the body itself. */
    @Schema(name = "UploaderHeartbeat", description = "One heartbeat. Times are ages in seconds, never clock readings.")
    record HeartbeatDoc(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Random UUID the program generated on first start")
            String instanceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "export-watcher")
            String kind,
            @Schema(description = "The PC's name as the page shows it", example = "CLARUS-PC")
            String name,
            @Schema(example = "2026-09-24")
            String version,
            @Schema(description = "false in the last heartbeat before the program exits")
            Boolean running,
            @Schema(allowableValues = {"exit", "session-end"})
            String stopReason,
            @Schema(description = "false while uploading is switched off in the program")
            Boolean enabled,
            @Schema(description = "How often the program reports; offline after three missed", example = "120")
            Integer heartbeatIntervalSec,
            Long secondsSinceActivity,
            Long secondsSinceUpload,
            Integer uploadedToday,
            Integer pendingFiles,
            Integer oldestPendingMinutes,
            Integer failedFiles,
            Long diskFreeBytes,
            Long diskTotalBytes,
            @ArraySchema(maxItems = 10, schema = @Schema(example = "server-unreachable"))
            List<String> problems) {
    }
}
