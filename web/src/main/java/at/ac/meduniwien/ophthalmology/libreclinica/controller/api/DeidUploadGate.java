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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.FileKindSniffer;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestArtifactStore;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.NeutralFilename;

/**
 * Layer 2 of the de-identification check, the part that needs no file on
 * disk: what the request claims, checked against what the server knows.
 *
 * <p>Used by every authenticated upload path when
 * {@link DeidentificationPolicy#isRequired() de-identification is required}.
 * The browser has already stripped the file (layer 1); nothing here takes
 * that on trust.
 *
 * <ol>
 *   <li>only E2E and DICOM are kinds ({@code 415} otherwise);</li>
 *   <li>{@code patientId} is the label of a study subject the caller may see
 *       and, when a visit is named, that visit's subject — never free text;</li>
 *   <li>{@code deidConfirmed=true} and {@code deidSha256} equal the SHA-256
 *       of the bytes actually received;</li>
 *   <li>the received filename is the neutral name for that label.</li>
 * </ol>
 *
 * <p>Content checks (E2E chunks, DICOM header) follow once the bytes are on
 * disk: {@code E2eDeidentificationVerifier} and the sidecar's {@code /verify}.
 *
 * <p>A rejection is {@code 422 {code:"DEID_REQUIRED", message, violations}}
 * where {@code violations} are field names only. Nothing read from the file
 * or the request is ever echoed or logged; the audit row carries the names
 * and the file's SHA-256.
 */
final class DeidUploadGate {

    private static final Logger LOG = LoggerFactory.getLogger(DeidUploadGate.class);

    static final String CODE = "DEID_REQUIRED";
    static final String MESSAGE =
            "This deployment accepts only de-identified files; the upload was refused. "
                    + "Strip the file's patient data and try again.";

    /** Field names, as reported in {@code violations}. */
    static final String V_FILE_TYPE = "fileType";
    static final String V_PATIENT_ID = "patientId";
    static final String V_CONFIRMED = "deidConfirmed";
    static final String V_SHA256 = "deidSha256";
    static final String V_FILENAME = "filename";

    private DeidUploadGate() {}

    /** What an upload's content check needs to know, and who to tell when it fails. */
    record Context(String label, String sha256, String neutralFilename, Consumer<List<String>> onReject) {
        void rejected(List<String> violations) {
            if (onReject != null) onReject.accept(violations);
        }
    }

    sealed interface Check permits Pass, Reject {}

    record Pass(String label, String sha256, String filename) implements Check {}

    record Reject(int status, List<String> violations, String sha256) implements Check {
        ResponseEntity<?> toResponse() {
            return DeidUploadGate.response(status, violations);
        }
    }

    /**
     * The account-less upload routes, when de-identification is required:
     * there is no user to attribute a confirmation to and no site visibility
     * to bound the label, so they are closed.
     */
    static ResponseEntity<?> accountlessClosed() {
        return response(403, List.of("accountlessUploadClosed"));
    }

    static ResponseEntity<?> response(int status, List<String> violations) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", CODE);
        body.put("message", MESSAGE);
        body.put("violations", violations);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * The checks that need no file on disk.
     *
     * @param sniffed         what the bytes are; null when none of the supported kinds
     * @param visible         the studies the caller's site visibility reaches
     * @param visitLabel      the subject label of the visit the upload names, or null
     */
    static Check precheck(DataSource dataSource, MultipartFile file, FileKindSniffer.Sniffed sniffed,
                          String patientId, String deidConfirmed, String deidSha256,
                          Set<Integer> visible, String visitLabel) {
        String received;
        try {
            received = sha256Of(file);
        } catch (IOException | RuntimeException e) {
            LOG.warn("deidentification: could not read the received file: {}", e.getClass().getSimpleName());
            return new Reject(422, List.of(V_SHA256), null);
        }

        // (a) the kind, from the bytes
        if (sniffed == null || sniffed.kind() == IngestArtifactStore.Kind.IMAGE) {
            return new Reject(415, List.of(V_FILE_TYPE), received);
        }

        List<String> violations = new ArrayList<>();

        // (b) the label
        String label = patientId == null ? "" : patientId.trim();
        if (label.isEmpty() || !isVisibleLabel(dataSource, label, visible)
                || (visitLabel != null && !visitLabel.equals(label))) {
            violations.add(V_PATIENT_ID);
        }

        // (c) the browser's confirmation, checked against the bytes
        if (!"true".equalsIgnoreCase(deidConfirmed == null ? "" : deidConfirmed.trim())) {
            violations.add(V_CONFIRMED);
        }
        String claimed = deidSha256 == null ? "" : deidSha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (!claimed.matches("[0-9a-f]{64}") || !claimed.equals(received)) {
            violations.add(V_SHA256);
        }

        // (f) the stored name
        String filename = file.getOriginalFilename();
        // A name cannot be neutral for a label that is not one, so it is
        // judged only once the label is.
        if (!violations.contains(V_PATIENT_ID)
                && !NeutralFilename.matches(filename, label, sniffed.extension())) {
            violations.add(V_FILENAME);
        }

        if (!violations.isEmpty()) return new Reject(422, violations, received);
        return new Pass(label, received, filename);
    }

    /** Streaming SHA-256 of the multipart payload, 64 lower-case hex characters. */
    static String sha256Of(MultipartFile file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JRE", e);
        }
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** The label of a live study subject inside {@code visible}. */
    static boolean isVisibleLabel(DataSource dataSource, String label, Set<Integer> visible) {
        if (label == null || label.isBlank() || visible == null || visible.isEmpty()) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM study_subject WHERE label = ? AND study_id = ANY (?) "
                             + "AND COALESCE(status_id, 1) NOT IN (5, 7) LIMIT 1")) {
            Array ids = c.createArrayOf("integer", visible.toArray());
            ps.setString(1, label);
            ps.setArray(2, ids);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("deidentification: label lookup failed ({}); refusing", e.getSQLState());
            return false;
        }
    }

    /** True when {@code label} is the label of any live study subject, visibility aside; the stored-data scan's question. */
    static boolean isStudyLabel(Connection c, String label) throws SQLException {
        if (label == null || label.isBlank()) return false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM study_subject WHERE label = ? LIMIT 1")) {
            ps.setString(1, label);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /* ---------------- audit ---------------- */

    /** An upload refused: field names and the file's SHA-256, never a value. */
    static void auditRejected(DataSource dataSource, UserAccountBean user, StudyBean study,
                              List<String> violations, String sha256, String channel) {
        write(dataSource, AuditTypeIds.DEID_UPLOAD_REJECTED, user, study, 0,
                "DEID_REQUIRED: " + String.join(",", violations), "",
                "sha256=" + (sha256 == null ? "" : sha256) + ";channel=" + channel, null);
    }

    /**
     * The browser's confirmation, kept with the row it let in: who uploaded,
     * which bytes (SHA-256) and when ({@code audit_date}). No file content,
     * no name, no label.
     */
    static void auditConfirmed(DataSource dataSource, UserAccountBean user, StudyBean study,
                               long ingestItemId, String sha256, Integer studyEventId) {
        write(dataSource, AuditTypeIds.DEID_UPLOAD_CONFIRMED, user, study, (int) ingestItemId,
                "deid_confirmed", "",
                "sha256=" + sha256 + ";confirmed_by=" + (user == null ? 0 : user.getId())
                        + (studyEventId == null ? "" : ";study_event_id=" + studyEventId),
                studyEventId);
    }

    private static void write(DataSource dataSource, int type, UserAccountBean user, StudyBean study,
                              int entityId, String entityName, String oldValue, String newValue,
                              Integer studyEventId) {
        try {
            String name = entityName.length() > 250 ? entityName.substring(0, 250) : entityName;
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), type, user, study, null,
                    "de-identification of an uploaded file", "ingest_item", entityId, name, oldValue, newValue,
                    null, studyEventId);
        } catch (RuntimeException e) {
            LOG.warn("deidentification: could not write the audit row: {}", e.getClass().getSimpleName());
        }
    }
}
