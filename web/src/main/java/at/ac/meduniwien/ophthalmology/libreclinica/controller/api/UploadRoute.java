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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.FileKindSniffer;

/**
 * DR-029 — the bits of routing an upload that the public and the staff
 * controller do identically: look at the bytes, answer in one shape, and
 * fill in what the OCT route needs from what the operator picked.
 */
final class UploadRoute {

    private static final Logger LOG = LoggerFactory.getLogger(UploadRoute.class);

    static final String UNSUPPORTED_MESSAGE =
            "unsupported file — JPEG, PNG, DICOM (.dcm) or a Spectralis .e2e export";

    private UploadRoute() {}

    /** What the file is, by its leading bytes; null when it is none of the supported kinds. */
    static FileKindSniffer.Sniffed sniff(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(FileKindSniffer.HEAD_BYTES);
        } catch (IOException e) {
            LOG.warn("upload: could not read the file head: {}", e.getMessage());
            return null;
        }
        return FileKindSniffer.sniff(head, file.getOriginalFilename());
    }

    /** One response shape for every kind, so the page has one thing to parse. */
    static ResponseEntity<?> respond(IngestUploadService.Outcome outcome) {
        return switch (outcome) {
            case IngestUploadService.Created c -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ingestItemId", c.ingestItemId());
                // The image page's field name, so anything built against it keeps working.
                body.put("imageIngestId", c.ingestItemId());
                body.put("jobId", null);
                body.put("kind", c.kind());
                body.put("format", c.format());
                body.put("status", c.status());
                body.put("laterality", c.laterality());
                body.put("acquisitionDate", c.acquisitionDate() == null ? null : c.acquisitionDate().toString());
                body.put("device", c.device());
                body.put("imagingModalityId", c.imagingModalityId());
                body.put("deidentified", c.deidentified());
                yield ResponseEntity.status(201).body(body);
            }
            case IngestUploadService.Duplicate d -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("message", "Diese Datei wurde bereits hochgeladen.");
                body.put("duplicate", true);
                body.put("existingIngestItemId", d.existingIngestItemId());
                body.put("existingJobId", d.existingJobId());
                yield ResponseEntity.status(409).body(body);
            }
            case IngestUploadService.Rejected r -> ResponseEntity.status(r.status()).body(Map.of("message", r.message()));
            case IngestUploadService.Undone u -> ResponseEntity.noContent().build();
        };
    }

    /**
     * Stamp the OCT route's answer with the kind, so the page does not have to
     * remember which route it took to read the response.
     */
    static ResponseEntity<?> asE2e(ResponseEntity<?> octResponse) {
        Object body = octResponse.getBody();
        if (!(body instanceof Map<?, ?> map)) return octResponse;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        out.putIfAbsent("kind", "e2e");
        out.putIfAbsent("format", "e2e");
        return ResponseEntity.status(octResponse.getStatusCode()).body(out);
    }

    /** The visit an open CRF belongs to — the picker hands over either, the image route wants the visit. */
    static Integer studyEventIdForEventCrf(DataSource dataSource, int eventCrfId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_event_id FROM event_crf WHERE event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int v = rs.getInt(1);
                return rs.wasNull() ? null : v;
            }
        } catch (SQLException e) {
            LOG.warn("study_event lookup failed for event_crf {}: {}", eventCrfId, e.getMessage());
            return null;
        }
    }

    /**
     * The subject label behind a visit. The OCT route insists on a patient id
     * for every scan; when the operator picked the visit rather than typing
     * the label, the label is the visit's.
     */
    static String subjectLabelForStudyEvent(DataSource dataSource, int studyEventId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ss.label FROM study_event se "
                             + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                             + " WHERE se.study_event_id = ?")) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            LOG.warn("label lookup failed for study_event {}: {}", studyEventId, e.getMessage());
            return null;
        }
    }

    static LocalDate parseIsoDateOrNull(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception bad) {
            return null;
        }
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
