/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DR-025 — Modality Worklist source for the {@code dicom-scp} sidecar.
 *
 * <p>The Optomed Lumo's standard-DICOM integration is worklist-driven: the
 * camera pulls a Modality Worklist (C-FIND) to pick the scheduled patient/exam,
 * then C-STOREs the study carrying that identity. The sidecar answers the
 * C-FIND from the entries served here — LibreClinica's <em>scheduled</em> visits
 * ({@code study_event}, status scheduled / data-entry-started) in a date window.
 * The sidecar stamps each item with the accession {@code LC<study_event_id>} and
 * a deterministic StudyInstanceUID; {@link DicomIngestApiController} parses the
 * accession back and auto-binds the returning study to that visit.
 *
 * <p>Identity is the EDC's pseudonymised subject label — the EDC holds no real
 * names. Not a browser endpoint: whitelisted {@code permitAll} and gated by the
 * shared-secret {@code X-MUW-Dicom-Token} ({@code core.dicom.ingest.token}); the
 * reverse proxy must never expose it. Nothing patient-identifying is logged.
 */
@RestController
@RequestMapping("/api/v1/internal/dicom-worklist")
@Tag(name = "DICOM worklist (internal)",
     description = "Scheduled visits as Modality Worklist entries for the dicom-scp sidecar.")
public class DicomWorklistApiController {

    private static final Logger LOG = LoggerFactory.getLogger(DicomWorklistApiController.class);

    private static final String TOKEN_HEADER = "X-MUW-Dicom-Token";
    /** Widest window a single query may span — a wildcard C-FIND can't dump the schedule. */
    private static final int MAX_WINDOW_DAYS = 31;
    private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("HHmmss");

    private final DataSource dataSource;

    @Autowired
    public DicomWorklistApiController(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** One worklist entry — the sidecar maps this onto a PS3.4 K.6 MWL item. */
    public record WorklistEntry(int studyEventId, String subjectLabel, String gender,
                                String dateOfBirth, String date, String time,
                                String eventLabel, String studyName, String modality) {}

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> worklist(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {

        String expected = cfg("core.dicom.ingest.token", "");
        if (expected.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("message", "DICOM ingest token not configured"));
        }
        if (token == null || !constantTimeEquals(token, expected)) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid or missing " + TOKEN_HEADER));
        }

        LocalDate d0 = parseIsoDateOrNull(from);
        if (d0 == null) d0 = LocalDate.now();
        LocalDate d1 = parseIsoDateOrNull(to);
        if (d1 == null) d1 = d0;
        if (d1.isBefore(d0)) {
            LocalDate t = d0; d0 = d1; d1 = t;
        }
        if (d1.isAfter(d0.plusDays(MAX_WINDOW_DAYS))) {
            d1 = d0.plusDays(MAX_WINDOW_DAYS);
        }

        // Scheduled (1) / data-entry-started (3) visits of non-removed subjects.
        String sql = "SELECT se.study_event_id, ss.label, sub.gender, sub.date_of_birth, sub.dob_collected, "
                + "       se.date_start, se.start_time_flag, se.sample_ordinal, "
                + "       sed.name AS definition_name, s.name AS study_name "
                + "  FROM study_event se "
                + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + "  JOIN subject sub ON sub.subject_id = ss.subject_id "
                + "  JOIN study s ON s.study_id = ss.study_id "
                + "  JOIN study_event_definition sed "
                + "    ON sed.study_event_definition_id = se.study_event_definition_id "
                + " WHERE date(se.date_start) BETWEEN ? AND ? "
                + "   AND se.subject_event_status_id IN (1, 3) "
                + "   AND ss.status_id NOT IN (5, 7) "
                + " ORDER BY se.date_start, ss.label";
        List<WorklistEntry> entries = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, Date.valueOf(d0));
            ps.setDate(2, Date.valueOf(d1));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp start = rs.getTimestamp("date_start");
                    boolean timeMeaningful = rs.getBoolean("start_time_flag");
                    boolean dobCollected = rs.getBoolean("dob_collected");
                    Date dob = rs.getDate("date_of_birth");
                    String defName = rs.getString("definition_name");
                    int ordinal = rs.getInt("sample_ordinal");
                    String eventLabel = ordinal > 1 ? defName + " (#" + ordinal + ")" : defName;
                    entries.add(new WorklistEntry(
                            rs.getInt("study_event_id"),
                            rs.getString("label"),
                            rs.getString("gender"),
                            (dobCollected && dob != null) ? dob.toLocalDate().toString() : null,
                            start != null ? start.toLocalDateTime().toLocalDate().toString() : null,
                            (timeMeaningful && start != null) ? start.toLocalDateTime().format(TM) : null,
                            eventLabel,
                            rs.getString("study_name"),
                            "OP"));
                }
            }
        } catch (SQLException e) {
            LOG.error("DICOM worklist query failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("message", "worklist query failed"));
        }
        LOG.info("DICOM worklist served: {} entries for {}..{}", entries.size(), d0, d1);
        return ResponseEntity.ok(Map.of("from", d0.toString(), "to", d1.toString(), "entries", entries));
    }

    private static LocalDate parseIsoDateOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String cfg(String key, String dflt) {
        try {
            String raw = CoreResources.getField(key);
            if (raw != null && !raw.isBlank()) return raw.trim();
        } catch (Exception ignored) {
            // CoreResources unavailable — use the default.
        }
        return dflt;
    }
}
