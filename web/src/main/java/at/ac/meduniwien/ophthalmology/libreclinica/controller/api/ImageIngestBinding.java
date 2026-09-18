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
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestPerformedItemPopulator;

/**
 * Shared "which visit does this image belong to" resolution for the DR-025
 * ingress paths, plus the audit row for binds that no operator performed.
 *
 * <p>Two ingresses bind without a user: the Optomed Lumo's C-STORE carries the
 * accession we issued on its worklist, and the Remidio upload page can carry a
 * visit the operator picked on an unauthenticated form. Both used to land rows
 * with {@code bound_by_user_id} null and — for the DICOM path — no audit row at
 * all, so a bound image had no trace of how it got bound. They now share this
 * resolution and always leave an audit row.
 */
final class ImageIngestBinding {

    private static final Logger LOG = LoggerFactory.getLogger(ImageIngestBinding.class);

    /** Accession shape the Modality Worklist issues — see dicom_scp.worklist. */
    private static final Pattern WORKLIST_ACCESSION = Pattern.compile("^LC(\\d{1,9})$");

    private ImageIngestBinding() {}

    /**
     * A visit an image can be filed against: the subject, the event, and the
     * event's first live CRF (null when the CRF has not been started — a
     * planned visit binds at event level).
     */
    record EventTarget(int studySubjectId, int studyEventId, Integer eventCrfId) {}

    /** @return the study_event id an accession names, or null when it is not one of ours. */
    static Integer studyEventIdFromAccession(String accession) {
        if (accession == null) return null;
        Matcher m = WORKLIST_ACCESSION.matcher(accession.trim());
        return m.matches() ? Integer.valueOf(m.group(1)) : null;
    }

    /**
     * Resolve a study_event to its bind target. Removed/locked events and
     * subjects (status 5/7) resolve to null so an image is never filed against
     * a visit that is no longer live.
     */
    static EventTarget resolveEventTarget(Connection c, int studyEventId) throws SQLException {
        String sql = "SELECT se.study_subject_id, ec.event_crf_id "
                + "  FROM study_event se "
                + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + "   AND ss.status_id NOT IN (5, 7) "
                + "  LEFT JOIN event_crf ec ON ec.study_event_id = se.study_event_id "
                + "   AND ec.status_id NOT IN (5, 7) "
                + " WHERE se.study_event_id = ? AND se.subject_event_status_id NOT IN (5, 7) "
                + " ORDER BY ec.event_crf_id ASC NULLS LAST LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int ss = rs.getInt("study_subject_id");
                int ecf = rs.getInt("event_crf_id");
                return new EventTarget(ss, studyEventId, rs.wasNull() ? null : ecf);
            }
        }
    }

    /**
     * Resolve a study_event that an unauthenticated caller named, refusing any
     * event whose date does not match the one the operator is filing for.
     *
     * <p>Without the date check the portal would accept any integer and file an
     * image against an arbitrary visit of any study — the form is not
     * authenticated, so the event id alone cannot be trusted.
     *
     * @param onDate the acquisition date the operator submitted; when null the
     *               event must be scheduled for today
     * @return the target, or null when the event is not live, not scheduled or
     *         data-entry-started, or is not on that date
     */
    static EventTarget resolveEventTargetForPortal(Connection c, int studyEventId, LocalDate onDate)
            throws SQLException {
        LocalDate day = onDate == null ? LocalDate.now() : onDate;
        String sql = "SELECT se.study_subject_id, ec.event_crf_id "
                + "  FROM study_event se "
                + "  JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id "
                + "   AND ss.status_id NOT IN (5, 7) "
                + "  LEFT JOIN event_crf ec ON ec.study_event_id = se.study_event_id "
                + "   AND ec.status_id NOT IN (5, 7) "
                + " WHERE se.study_event_id = ? "
                + "   AND se.subject_event_status_id IN (1, 3) "
                + "   AND date(se.date_start) = ? "
                + " ORDER BY ec.event_crf_id ASC NULLS LAST LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studyEventId);
            ps.setDate(2, java.sql.Date.valueOf(day));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int ss = rs.getInt("study_subject_id");
                int ecf = rs.getInt("event_crf_id");
                return new EventTarget(ss, studyEventId, rs.wasNull() ? null : ecf);
            }
        }
    }

    /**
     * Audit a bind that no operator performed.
     *
     * <p>Written with {@code user_id} NULL, the convention the unauthenticated
     * portals already use for their audit rows ({@code OCT_UPLOAD_PUBLIC},
     * {@code BCVA_ENTRY_PUBLIC}). Reuses {@link AuditTypeIds#IMAGE_BIND} — the
     * event is the same, only the actor differs, and the new value records the
     * policy so "who bound this" is answerable from the trail.
     *
     * <p>Never throws: an image that is stored and bound must not be rejected
     * because its audit row could not be written; the failure is logged instead.
     */
    static void writeSystemBindAudit(DataSource dataSource, long imageIngestId,
                                     String matchPolicy, int studyEventId) {
        // Same column set and NULL-user convention as the portals' own audit
        // writers (PublicOctUploadController.writePublicOctUploadAuditRow).
        String sql = "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, "
                + "user_id, audit_table, entity_id, entity_name, old_value, new_value) "
                + "VALUES (?, now(), NULL, ?, ?, ?, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, AuditTypeIds.IMAGE_BIND);
            ps.setString(2, "ingest_item");
            ps.setInt(3, (int) imageIngestId);
            ps.setString(4, "status");
            ps.setString(5, "UNBOUND");
            // Pack the policy + visit into new_value so the audit view can
            // explain a bind that has no user behind it.
            ps.setString(6, "BOUND;match_policy=" + matchPolicy + ";study_event_id=" + studyEventId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("could not audit the system bind of ingest_item {}: {}",
                    imageIngestId, e.getMessage());
        }
    }

    /**
     * Tick the visit's "this modality was performed" box for a bind that just
     * happened. See {@link IngestPerformedItemPopulator} for what it will and
     * will not overwrite.
     *
     * <p>Called from all three bind paths so the behaviour cannot diverge
     * between an operator reconciling in the inbox, a worklist auto-bind and a
     * portal upload that carried its visit.
     *
     * <p>Never throws. The image is bound, which is the outcome that matters
     * clinically; an un-ticked checklist box is visible on the form and can be
     * ticked by hand.
     *
     * @param actorUserId the binding user, or {@code null} for a machine bind —
     *                    which is then attributed to the locked {@code system}
     *                    account rather than to a person
     */
    static void tickPerformed(DataSource dataSource, long imageIngestId, EventTarget target,
                              String sourceKind, String deviceKey, Integer actorUserId) {
        if (target == null || target.eventCrfId() == null) return;
        try {
            Integer studyId = studyIdOfSubject(dataSource, target.studySubjectId());
            if (studyId == null) return;
            int actor = actorUserId != null && actorUserId > 0
                    ? actorUserId
                    : resolveSystemActor(dataSource);
            if (actor <= 0) {
                LOG.warn("performed-tick skipped for image {}: no system service account to attribute it to",
                        imageIngestId);
                return;
            }
            new IngestPerformedItemPopulator(dataSource).markPerformed(
                    imageIngestId, target.eventCrfId(), sourceKind, deviceKey, studyId, actor);
        } catch (RuntimeException e) {
            LOG.warn("performed-tick failed for image {}: {}", imageIngestId, e.getMessage());
        }
    }

    private static Integer studyIdOfSubject(DataSource dataSource, int studySubjectId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_id FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (SQLException e) {
            LOG.warn("could not resolve the study of study_subject {}: {}", studySubjectId, e.getMessage());
            return null;
        }
    }

    private static int resolveSystemActor(DataSource dataSource) {
        Integer id = IngestPerformedItemPopulator.systemUserId(dataSource);
        return id == null ? 0 : id;
    }
}
