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
import java.time.Instant;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestFileReference;

/**
 * DR-036 — what an ingress does when the picture it was just handed is
 * already known.
 *
 * <p>Every door a file comes through — the upload page, the C-STORE receiver,
 * the Remidio pull, the OCT route — asks the same question after the
 * exact-bytes check has passed: is there an earlier row whose
 * {@code pixel_sha256} equals this file's? The answer, the <em>twin</em>, and
 * the label the new file was claimed for decide between two outcomes:
 *
 * <ul>
 *   <li><strong>Duplicate</strong> — the twin carries the same label and has
 *       not been dismissed. The new file is the same capture sent again by
 *       another path (a re-export, a re-save); it is refused the way a
 *       byte-identical file is, pointing at the row that exists.</li>
 *   <li><strong>Held</strong> — the twin carries another label, or no label,
 *       or was dismissed. Two labels for one picture is a mistake somebody
 *       has to look at, and the platform must not decide which one is right:
 *       the new file lands in the inbox unfiled, whatever visit the uploader
 *       named, with an audit row naming the twin. The inbox shows the pair;
 *       an operator files or dismisses the new one.</li>
 * </ul>
 *
 * <p>A file without a fingerprint (an unreadable picture, a sidecar that
 * predates it) has no twin and is filed as before.
 */
final class IngestTwins {

    private static final Logger LOG = LoggerFactory.getLogger(IngestTwins.class);

    private IngestTwins() {}

    /**
     * The earliest other row that shows the same picture.
     *
     * @param label the label the twin is known under: its bound subject's
     *              label when it is filed, otherwise the patient id it
     *              arrived with; null when it has neither
     */
    record Twin(long ingestItemId, String status, String label, Integer boundStudySubjectId,
                Integer boundStudyEventId, Instant receivedAt) {

        boolean isDismissed() {
            return "DISMISSED".equals(status);
        }
    }

    enum Verdict {
        /** No twin — file as usual. */
        NONE,
        /** Same picture, same label — refuse, pointing at the twin. */
        DUPLICATE,
        /** Same picture, another label (or a dismissed twin) — land unfiled and say so. */
        HELD
    }

    private static final String TWIN_SQL = "SELECT ii.ingest_item_id, ii.status, ii.patient_id, "
            + "ii.bound_study_subject_id, ii.bound_study_event_id, ii.received_at, ss.label AS bound_label "
            + "  FROM ingest_item ii "
            + "  LEFT JOIN study_subject ss ON ss.study_subject_id = ii.bound_study_subject_id "
            + " WHERE ii.pixel_sha256 = ? ";

    /** The twin of a picture that is not in the table yet; null when there is none or the digest is null. */
    static Twin find(DataSource dataSource, String pixelSha256) {
        if (pixelSha256 == null || pixelSha256.isBlank()) return null;
        try (Connection c = dataSource.getConnection()) {
            return find(c, pixelSha256);
        } catch (SQLException e) {
            LOG.warn("twin lookup failed: {}", e.getMessage());
            return null;
        }
    }

    static Twin find(Connection c, String pixelSha256) throws SQLException {
        if (pixelSha256 == null || pixelSha256.isBlank()) return null;
        try (PreparedStatement ps = c.prepareStatement(TWIN_SQL + " ORDER BY ii.ingest_item_id LIMIT 1")) {
            ps.setString(1, pixelSha256);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    /** One row, in the twin's shape; null when there is no such row. */
    static Twin load(DataSource dataSource, long ingestItemId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     TWIN_SQL.replace("WHERE ii.pixel_sha256 = ? ", "WHERE ii.ingest_item_id = ? "))) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            LOG.warn("ingest_item lookup failed for {}: {}", ingestItemId, e.getMessage());
            return null;
        }
    }

    /** The earliest other row with the same picture as an existing row; null when it stands alone. */
    static Twin twinOf(DataSource dataSource, long ingestItemId) {
        String sql = TWIN_SQL.replace("WHERE ii.pixel_sha256 = ? ",
                "WHERE ii.pixel_sha256 = (SELECT o.pixel_sha256 FROM ingest_item o WHERE o.ingest_item_id = ?) "
                        + "AND ii.ingest_item_id <> ? ")
                + " ORDER BY ii.ingest_item_id LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ingestItemId);
            ps.setLong(2, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (SQLException e) {
            LOG.warn("twin lookup failed for ingest_item {}: {}", ingestItemId, e.getMessage());
            return null;
        }
    }

    private static Twin read(ResultSet rs) throws SQLException {
        int ss = rs.getInt("bound_study_subject_id");
        Integer boundSubject = rs.wasNull() ? null : ss;
        int se = rs.getInt("bound_study_event_id");
        Integer boundEvent = rs.wasNull() ? null : se;
        String boundLabel = rs.getString("bound_label");
        String label = boundSubject != null && boundLabel != null ? boundLabel : rs.getString("patient_id");
        Timestamp received = rs.getTimestamp("received_at");
        return new Twin(rs.getLong("ingest_item_id"), rs.getString("status"),
                label == null || label.isBlank() ? null : label.trim(),
                boundSubject, boundEvent, received == null ? null : received.toInstant());
    }

    /** What to do with a new file claimed for {@code newLabel}, given its twin. */
    static Verdict verdict(Twin twin, String newLabel) {
        if (twin == null) return Verdict.NONE;
        if (!twin.isDismissed() && sameLabel(twin.label(), newLabel)) return Verdict.DUPLICATE;
        return Verdict.HELD;
    }

    /** Labels are typed at devices: trimmed and case-insensitive. Two absent labels agree. */
    static boolean sameLabel(String a, String b) {
        String x = a == null || a.isBlank() ? null : a.trim().toLowerCase(Locale.ROOT);
        String y = b == null || b.isBlank() ? null : b.trim().toLowerCase(Locale.ROOT);
        return x == null ? y == null : x.equals(y);
    }

    /** The label of a study subject, for the "same label?" question when the file names a visit. */
    static String subjectLabel(Connection c, int studySubjectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT label FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static String subjectLabel(DataSource dataSource, int studySubjectId) {
        try (Connection c = dataSource.getConnection()) {
            return subjectLabel(c, studySubjectId);
        } catch (SQLException e) {
            LOG.warn("label lookup failed for study_subject {}: {}", studySubjectId, e.getMessage());
            return null;
        }
    }

    /** The value packed into the audit row of a held file, and appended to a later bind's. */
    static String heldValue(Twin twin, String newLabel, String route) {
        StringBuilder sb = new StringBuilder("held;same_image_as=").append(twin.ingestItemId())
                .append(";twin_status=").append(twin.status());
        if (twin.label() != null) sb.append(";twin_label=").append(twin.label());
        if (newLabel != null && !newLabel.isBlank()) sb.append(";claimed_label=").append(newLabel.trim());
        if (route != null) sb.append(";route=").append(route);
        return sb.toString();
    }

    /**
     * Record that a file was held back, against the new row, naming the twin.
     *
     * <p>A person's upload is attributed to them; every other ingress writes
     * the user-less row the portals write. Never throws: the file is stored
     * and in the inbox, which is the outcome that matters; a missing audit
     * row is logged.
     *
     * @param actor who uploaded, or null for a machine ingress
     * @param route which door — "upload", "dicom", "remidio", "portal-oct"
     */
    static void writeHeldAudit(DataSource dataSource, long ingestItemId, Twin twin, String newLabel,
                               IngestBindService.Actor actor, String route) {
        if (ingestItemId <= 0 || ingestItemId > Integer.MAX_VALUE) {
            LOG.warn("could not audit the held file: ingest_item id out of the audit column's range");
            return;
        }
        String newValue = heldValue(twin, newLabel, route);
        if (actor != null && !actor.isSystem()) {
            try {
                String reference = IngestFileReference.describe(dataSource, ingestItemId);
                EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource),
                        AuditTypeIds.INGEST_DUPLICATE_HELD, actor.user(), actor.study(), null,
                        "ingested file held back: same image already known",
                        "ingest_item", (int) ingestItemId, reference == null ? "status" : reference,
                        null, newValue);
            } catch (RuntimeException e) {
                LOG.warn("could not audit the held ingest_item {}: {}", ingestItemId, e.getMessage());
            }
            return;
        }
        String sql = "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                + "VALUES (?, now(), NULL, ?, ?, ?, NULL, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, AuditTypeIds.INGEST_DUPLICATE_HELD);
            ps.setString(2, "ingest_item");
            ps.setInt(3, (int) ingestItemId);
            String reference = IngestFileReference.describe(c, ingestItemId);
            ps.setString(4, reference == null ? "status" : reference);
            ps.setString(5, newValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("could not audit the held ingest_item {}: {}", ingestItemId, e.getMessage());
        }
    }
}
