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
import java.time.LocalDate;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.admin.AuditEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.IngestItemRepository;
import at.ac.meduniwien.ophthalmology.libreclinica.service.ingest.PerformedItemAutoTicker;

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
     *                   started; the checklist tick still happens, because the
     *                   ticker starts the form that carries the box
     */
    public Result bind(long ingestItemId, int studySubjectId, Integer studyEventId,
                       Integer eventCrfId, String matchPolicy, Actor actor) {
        return bind(ingestItemId, studySubjectId, studyEventId, eventCrfId, matchPolicy, actor, null);
    }

    /**
     * As above, recording what the file's own date had to say about the visit.
     *
     * <p>The verdict goes in the audit row rather than being checked here.
     * Filing a scan whose date disagrees with the visit is a judgement a
     * person is allowed to make — a device with a wrong clock, a visit
     * recorded on the wrong day, an export that spans two sessions — and
     * refusing it outright would leave the file stuck in the inbox with no way
     * forward. What must not happen is it being made silently, so the trail
     * carries the two dates and the fact somebody saw them.
     *
     * @param dateCheck the verdict from {@link #checkVisitDate}, or null when
     *                  the caller did not run one (worklist auto-bind, portal)
     */
    public Result bind(long ingestItemId, int studySubjectId, Integer studyEventId,
                       Integer eventCrfId, String matchPolicy, Actor actor,
                       DateCheck dateCheck) {
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

            writeBindAudit(ingestItemId, studyEventId, matchPolicy, actor, dateCheck);

            // The file on the visit is the evidence that this device was used
            // on it, so the checklist box follows from the bind rather than
            // being typed again.
            //
            // P3.4 — a visit is enough; the ticker starts the form carrying the
            // box when nobody has opened it. Requiring a started CRF meant the
            // checklist silently disagreed with the files until somebody did.
            if (studyEventId != null) {
                Device dev = readDevice(c, ingestItemId);
                if (dev != null) {
                    ImageIngestBinding.tickPerformed(dataSource, ingestItemId,
                            new ImageIngestBinding.EventTarget(studySubjectId, studyEventId, eventCrfId),
                            dev.sourceKind(), dev.deviceKey(), dev.laterality(), actor.userId());
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
        PerformedItemAutoTicker.ClearOutcome cleared =
                new PerformedItemAutoTicker(dataSource).clearPerformed(ingestItemId, actorId);
        if (cleared == PerformedItemAutoTicker.ClearOutcome.FAILED) {
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

    /* ------------------------------------------------------------------ */
    /* does the file's own date agree with the visit's?                    */
    /* ------------------------------------------------------------------ */

    /**
     * What comparing a file's acquisition date to a visit's date produced.
     *
     * @param verdict    {@link #VERDICT_AGREES}, {@link #VERDICT_MISMATCH} or
     *                   {@link #VERDICT_UNVERIFIED}
     * @param fileDate   the date read out of the file, or null when none was
     * @param visitDate  the visit's {@code date_start}, or null when unknown
     * @param dateSource the provenance recorded on the row, for the message
     */
    public record DateCheck(String verdict, LocalDate fileDate, LocalDate visitDate,
                            String dateSource) {

        /** The file says it was taken on the day of the visit. */
        public static final String VERDICT_AGREES = "agrees";
        /** The file says it was taken on a different day. */
        public static final String VERDICT_MISMATCH = "mismatch";
        /** Nothing read a date out of this file, so there is nothing to compare. */
        public static final String VERDICT_UNVERIFIED = "unverified";

        public boolean isMismatch() {
            return VERDICT_MISMATCH.equals(verdict);
        }
    }

    /**
     * Compare what the file says about itself against the visit it is about to
     * be filed under.
     *
     * <p>Only a date whose provenance is {@code file} is compared. An operator
     * -supplied date cannot disagree with the visit in any interesting way:
     * the upload workbench's date box is the key the visit list was searched
     * by, so the stored value equals the chosen visit's date by construction.
     * Comparing those two would pass on every upload and catch nothing, while
     * looking for all the world like a check. A file with no trustworthy date
     * is {@link DateCheck#VERDICT_UNVERIFIED} — reported, never blocked,
     * because plenty of legitimate files carry no date at all.
     *
     * <p>Never throws: a reconciliation must not become impossible because the
     * check could not be run. A failure reads as unverified.
     */
    public DateCheck checkVisitDate(long ingestItemId, Integer studyEventId) {
        if (studyEventId == null) {
            return new DateCheck(DateCheck.VERDICT_UNVERIFIED, null, null, null);
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT i.acquisition_date, i.acquisition_date_source, date(se.date_start) AS visit_date "
                             + "  FROM ingest_item i "
                             + "  CROSS JOIN study_event se "
                             // Only a file that is about to be bound. A row
                             // somebody already reconciled must fail on its
                             // state, not on its date — otherwise the operator
                             // is asked about two dates, says yes, and only
                             // then learns the file was never bindable.
                             + " WHERE i.ingest_item_id = ? AND i.status = 'UNBOUND' "
                             + "   AND se.study_event_id = ?")) {
            ps.setLong(1, ingestItemId);
            ps.setInt(2, studyEventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new DateCheck(DateCheck.VERDICT_UNVERIFIED, null, null, null);
                }
                java.sql.Date acq = rs.getDate("acquisition_date");
                java.sql.Date visit = rs.getDate("visit_date");
                String source = rs.getString("acquisition_date_source");
                LocalDate fileDate = acq == null ? null : acq.toLocalDate();
                LocalDate visitDate = visit == null ? null : visit.toLocalDate();

                boolean trustworthy = IngestItemRepository.ACQ_SOURCE_FILE.equals(source);
                if (!trustworthy || fileDate == null || visitDate == null) {
                    return new DateCheck(DateCheck.VERDICT_UNVERIFIED, trustworthy ? fileDate : null,
                            visitDate, source);
                }
                return new DateCheck(
                        fileDate.equals(visitDate) ? DateCheck.VERDICT_AGREES : DateCheck.VERDICT_MISMATCH,
                        fileDate, visitDate, source);
            }
        } catch (SQLException e) {
            LOG.warn("could not check the acquisition date of ingest_item {} against study_event {}: {}",
                    ingestItemId, studyEventId, e.getMessage());
            return new DateCheck(DateCheck.VERDICT_UNVERIFIED, null, null, null);
        }
    }

    /** Which ingress a file came through, which device sent it, and for which eye. */
    private record Device(String sourceKind, String deviceKey, String laterality) {}

    /**
     * Falls back to the DICOM calling AE title for rows written before
     * {@code device} existed, so files already sitting in the inbox still tick
     * the right box when they are reconciled.
     */
    private static Device readDevice(Connection c, long ingestItemId) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_kind, COALESCE(device, source_ae_title) AS device_key, laterality "
                        + "  FROM ingest_item WHERE ingest_item_id = ?")) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Device(rs.getString("source_kind"), rs.getString("device_key"),
                        rs.getString("laterality"));
            }
        } catch (SQLException e) {
            LOG.warn("could not read the device of ingest_item {}: {}", ingestItemId, e.getMessage());
            return null;
        }
    }

    private int systemActorId() {
        Integer id = PerformedItemAutoTicker.systemUserId(dataSource);
        return id == null ? 0 : id;
    }

    /**
     * A bind by a person goes through the standard audit writer; one with no
     * person behind it uses the user-less idiom the portals already use, and
     * packs the policy into the new value so "who bound this" stays answerable.
     */
    private void writeBindAudit(long ingestItemId, Integer studyEventId,
                                String matchPolicy, Actor actor, DateCheck dateCheck) {
        if (actor.isSystem()) {
            ImageIngestBinding.writeSystemBindAudit(dataSource, ingestItemId, matchPolicy,
                    studyEventId == null ? 0 : studyEventId);
            return;
        }
        StringBuilder newValue = new StringBuilder("BOUND;match_policy=").append(matchPolicy);
        if (dateCheck != null) {
            newValue.append(";acquisition_date=").append(dateCheck.verdict());
            if (dateCheck.isMismatch()) {
                // Both dates, so the trail answers "what did they accept?"
                // without a reader having to reconstruct it from two tables.
                newValue.append(";file_date=").append(dateCheck.fileDate())
                        .append(";visit_date=").append(dateCheck.visitDate());
            }
        }
        writeAudit(AuditTypeIds.IMAGE_BIND, ingestItemId, actor,
                "ingested file bound", "UNBOUND", newValue.toString());
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
