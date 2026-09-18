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
import java.sql.Types;
import java.time.Instant;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestPerformedItemPopulator;

/**
 * P3.2 — what happens when somebody says whose visit a file belongs to.
 *
 * <p>Binding is not a status change. It is the moment a file stops being an
 * anonymous arrival and becomes a named patient's study data, and several
 * things have to follow from it together: the row moves to BOUND, the visit's
 * "this modality was performed" box is ticked because the file is the evidence,
 * and an audit row records who decided. Doing those in three places — the
 * inbox, the DICOM worklist auto-bind, the portal upload — is how they drift,
 * and a drift here means a CRF that asserts something no file supports.
 *
 * <p>So this is the one implementation, and it is also where the reverse lives.
 *
 * <p><strong>Unbind is new.</strong> Before this, a mis-bind was fixed by
 * editing the row: the file moved back but the tick it caused stayed, and the
 * form kept asserting a modality had been performed on a visit with nothing
 * left to show for it. Undoing a bind now undoes what the bind did.
 *
 * <p>P3.3 adds cancelling the inference jobs a bind started, once
 * {@code retinal_inference_job.ingest_item_id} exists to find them by. Until
 * then no bind starts a job, so there is nothing to cancel.
 */
public final class IngestBindService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestBindService.class);

    private final DataSource dataSource;

    public IngestBindService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** How a binding was arrived at — {@code ingest_item.match_policy}. */
    public static final String POLICY_MANUAL = "manual";
    public static final String POLICY_WORKLIST = "worklist";
    public static final String POLICY_PORTAL = "portal";
    public static final String POLICY_VISIT_PICKED = "visit-picked";
    public static final String POLICY_SUGGESTED = "suggested";
    public static final String POLICY_BACKFILL = "backfill";

    /**
     * Who is doing this.
     *
     * @param user  the person, or null when no person is involved — a worklist
     *              auto-bind, or a portal upload from an unauthenticated form.
     *              The distinction is recorded rather than smoothed over:
     *              attributing a machine's bind to a named person is precisely
     *              what an audit trail must not do.
     * @param study the actor's active study, for the audit row's context
     */
    public record Actor(UserAccountBean user, StudyBean study) {

        public static Actor system() {
            return new Actor(null, null);
        }

        public boolean isSystem() {
            return user == null || user.getId() == 0;
        }

        public Integer userId() {
            return isSystem() ? null : user.getId();
        }
    }

    /** The outcome, so a caller can pick its own status code and message. */
    public enum Result {
        /** Done. */
        OK,
        /** The row is not in the state this transition starts from. */
        WRONG_STATE,
        /** No such row. */
        NOT_FOUND,
        /** The database refused. */
        FAILED
    }

    /* ------------------------------------------------------------------ */
    /* bind                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Bind an UNBOUND file to a visit.
     *
     * <p>The caller is responsible for having checked that the actor may
     * reconcile and that the target is inside their visibility — this decides
     * what binding <em>means</em>, not who is allowed to ask.
     *
     * @param eventCrfId the visit's CRF instance, or null when it has not been
     *                   started; the tick is skipped in that case, because
     *                   there is no form to tick
     */
    public Result bind(long ingestItemId, int studySubjectId, Integer studyEventId,
                       Integer eventCrfId, String matchPolicy, Actor actor) {
        try (Connection c = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_item SET status='BOUND', match_policy=?, "
                            + "bound_study_subject_id=?, bound_study_event_id=?, bound_event_crf_id=?, "
                            + "bound_by_user_id=?, bound_at=? "
                            + " WHERE ingest_item_id=? AND status='UNBOUND'")) {
                ps.setString(1, matchPolicy);
                ps.setInt(2, studySubjectId);
                setIntOrNull(ps, 3, studyEventId);
                setIntOrNull(ps, 4, eventCrfId);
                setIntOrNull(ps, 5, actor.userId());
                ps.setTimestamp(6, Timestamp.from(Instant.now()));
                ps.setLong(7, ingestItemId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) return existsState(c, ingestItemId);

            writeBindAudit(ingestItemId, studyEventId, matchPolicy, actor);

            // The file on the visit is the evidence that this device was used
            // on it, so the checklist box follows from the bind rather than
            // being typed again.
            if (eventCrfId != null) {
                Device dev = readDevice(c, ingestItemId);
                if (dev != null) {
                    ImageIngestBinding.tickPerformed(dataSource, ingestItemId,
                            new ImageIngestBinding.EventTarget(studySubjectId, studyEventId, eventCrfId),
                            dev.sourceKind(), dev.deviceKey(), actor.userId());
                }
            }
            LOG.info("ingest_item {} bound to study_subject {} ({})",
                    ingestItemId, studySubjectId, matchPolicy);
            return Result.OK;
        } catch (SQLException e) {
            LOG.error("bind failed for ingest_item {}: {}", ingestItemId, e.getMessage());
            return Result.FAILED;
        }
    }

    /* ------------------------------------------------------------------ */
    /* unbind                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Return a BOUND file to the inbox, undoing what binding it caused.
     *
     * <p>The file keeps its candidate and its device — it is the same file, it
     * is simply not yet filed — so it reappears in the inbox with the same
     * suggestion it had the first time.
     */
    public Result unbind(long ingestItemId, Actor actor) {
        int actorId = actor.userId() != null ? actor.userId() : systemActorId();
        // Undo the CRF value FIRST. If the row goes UNBOUND and then this
        // fails, the form is left asserting something with no file behind it;
        // in the other order a failure leaves a bound file, which is merely the
        // state the operator was already trying to correct.
        IngestPerformedItemPopulator.ClearOutcome cleared =
                new IngestPerformedItemPopulator(dataSource).clearPerformed(ingestItemId, actorId);
        if (cleared == IngestPerformedItemPopulator.ClearOutcome.FAILED) {
            return Result.FAILED;
        }

        try (Connection c = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_item SET status='UNBOUND', match_policy=NULL, "
                            + "bound_study_subject_id=NULL, bound_study_event_id=NULL, "
                            + "bound_event_crf_id=NULL, bound_item_id=NULL, "
                            + "bound_by_user_id=NULL, bound_at=NULL "
                            + " WHERE ingest_item_id=? AND status='BOUND'")) {
                ps.setLong(1, ingestItemId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) return existsState(c, ingestItemId);

            writeAudit(AuditTypeIds.INGEST_UNBIND, ingestItemId, actor,
                    "ingested file unbound", "BOUND", "UNBOUND;cleared=" + cleared);
            LOG.info("ingest_item {} unbound ({})", ingestItemId, cleared);
            return Result.OK;
        } catch (SQLException e) {
            LOG.error("unbind failed for ingest_item {}: {}", ingestItemId, e.getMessage());
            return Result.FAILED;
        }
    }

    /* ------------------------------------------------------------------ */
    /* dismiss                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Mark an UNBOUND file as not study data.
     *
     * <p>A test exposure, an image of the wrong patient, whatever a device
     * flushes on first contact. The reason is kept because the retention sweep
     * deletes the file later and the row is the only record that it existed.
     */
    public Result dismiss(long ingestItemId, String reason, Actor actor) {
        String message = (reason == null || reason.isBlank()) ? "dismissed" : reason.trim();
        if (message.length() > 500) message = message.substring(0, 500);

        try (Connection c = dataSource.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE ingest_item SET status='DISMISSED', status_message=?, "
                            + "bound_by_user_id=?, bound_at=? "
                            + " WHERE ingest_item_id=? AND status='UNBOUND'")) {
                ps.setString(1, message);
                setIntOrNull(ps, 2, actor.userId());
                ps.setTimestamp(3, Timestamp.from(Instant.now()));
                ps.setLong(4, ingestItemId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) return existsState(c, ingestItemId);

            writeAudit(AuditTypeIds.IMAGE_DISMISS, ingestItemId, actor,
                    "ingested file dismissed", "UNBOUND", "DISMISSED");
            return Result.OK;
        } catch (SQLException e) {
            LOG.error("dismiss failed for ingest_item {}: {}", ingestItemId, e.getMessage());
            return Result.FAILED;
        }
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    /** Tell "no such row" from "wrong state", so the caller can say which. */
    private static Result existsState(Connection c, long ingestItemId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Result.WRONG_STATE : Result.NOT_FOUND;
            }
        }
    }

    private static void setIntOrNull(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER); else ps.setInt(idx, v);
    }

    /** Which ingress a file came through, and which device sent it. */
    private record Device(String sourceKind, String deviceKey) {}

    /**
     * Falls back to the DICOM calling AE title for rows written before
     * {@code device} existed, so files already sitting in the inbox still tick
     * the right box when they are reconciled.
     */
    private static Device readDevice(Connection c, long ingestItemId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_kind, COALESCE(device, source_ae_title) AS device_key "
                        + "FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Device(rs.getString("source_kind"), rs.getString("device_key"));
            }
        } catch (SQLException e) {
            LOG.warn("could not read the device of ingest_item {}: {}", ingestItemId, e.getMessage());
            return null;
        }
    }

    private int systemActorId() {
        Integer id = IngestPerformedItemPopulator.systemUserId(dataSource);
        return id == null ? 0 : id;
    }

    /**
     * A bind by a person goes through the standard audit writer; one with no
     * person behind it uses the user-less idiom the portals already use, and
     * packs the policy into the new value so "who bound this" stays answerable.
     */
    private void writeBindAudit(long ingestItemId, Integer studyEventId,
                                String matchPolicy, Actor actor) {
        if (actor.isSystem()) {
            ImageIngestBinding.writeSystemBindAudit(dataSource, ingestItemId, matchPolicy,
                    studyEventId == null ? 0 : studyEventId);
            return;
        }
        writeAudit(AuditTypeIds.IMAGE_BIND, ingestItemId, actor,
                "ingested file bound", "UNBOUND", "BOUND;match_policy=" + matchPolicy);
    }

    /** Never throws: a completed transition must not be undone by a failed log. */
    private void writeAudit(int auditType, long ingestItemId, Actor actor,
                            String label, String oldValue, String newValue) {
        try {
            EventCrfsApiController.writeAuditEvent(new AuditEventDAO(dataSource), auditType,
                    actor.user(), actor.study(), null, label,
                    "ingest_item", (int) ingestItemId, "status", oldValue, newValue);
        } catch (RuntimeException e) {
            LOG.warn("could not audit {} of ingest_item {}: {}", label, ingestItemId, e.getMessage());
        }
    }
}
