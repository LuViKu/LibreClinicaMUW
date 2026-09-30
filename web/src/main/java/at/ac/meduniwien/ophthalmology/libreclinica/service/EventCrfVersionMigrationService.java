/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.SubjectEventStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AuditTypeIds;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.HiddenItem;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.NotOffered;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.Options;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.Preview;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.Ref;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.Request;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.Result;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.Row;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.Skipped;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.EventCrfMigrationDto.VersionOption;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.dto.ValidationErrorBody;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.CRFVersionDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves existing event CRFs to another version of their CRF, across the
 * chosen sites and event definitions of one study. The SPA's replacement for
 * the batch CRF version migration ({@code BatchCRFMigrationServlet} and
 * {@code BatchCRFMigrationController}), and for the single event CRF version
 * change ({@code ChangeCRFVersionController}), which is the same move
 * narrowed to one subject or one event CRF.
 *
 * <p>What it keeps from the legacy migration:
 * <ul>
 *   <li><b>Who:</b> an active Data Manager (study director) or CRC
 *       (coordinator) binding on the study itself, as
 *       {@code runPreviewTest} required. A system administrator without one
 *       is refused, as there.</li>
 *   <li><b>What is valid:</b> an available study (a site resolves to its
 *       study); two different versions of the CRF, the target available;
 *       sites that are the study or its sites, event definitions of the
 *       study. With none named, the study and all its available sites, and
 *       all its available event definitions; named ones that are not
 *       available are left out.</li>
 *   <li><b>Which event CRFs:</b> those on the source version, of subjects in
 *       the chosen sites, in the chosen event definitions, where the event
 *       definition of the subject's site offers both versions
 *       ({@code selected_version_ids}); the selection of
 *       {@code findAllCRFMigrationReportList}.</li>
 *   <li><b>What changes:</b> the event CRF's version; its SDV flag, cleared;
 *       a signed subject and a signed event, back to the status the audit
 *       trail records from before signing ({@code AuditDAO.findLastStatus}).
 *       The {@code event_crf} triggers audit each version and SDV change.</li>
 * </ul>
 *
 * <p>Where it differs, and why:
 * <ul>
 *   <li><b>In the request, in one transaction.</b> The legacy run was a bare
 *       {@code Thread}: an exception left its transaction open and its
 *       session unclosed, and the only report was an e-mail. Here the
 *       response carries the result and the log, and a failure changes
 *       nothing.</li>
 *   <li><b>The run names the count its preview showed.</b> A selection that
 *       changed in between (new data, a concurrent run) is refused.</li>
 *   <li><b>Locked data is not moved:</b> an event CRF that is locked, in a
 *       locked event, or of a locked subject. A lock freezes the record and
 *       its SDV state (see {@code SubjectLockGuard}); the preview lists
 *       them.</li>
 *   <li><b>An event's status is reverted only when the event is signed.</b>
 *       The legacy code gave every event that had ever been signed its
 *       pre-signing status, which could undo a later lock.</li>
 *   <li><b>The moved event CRF's own signature goes too.</b> MUW's subject
 *       and event signing mark the event CRFs signed; left so, the moved CRF
 *       could not be completed on its new version. Audited
 *       ({@link AuditTypeIds#EVENT_CRF_SIGNATURE_REMOVED}), since the trigger
 *       does not record that transition.</li>
 *   <li><b>No audit history of the signing</b> (seeded or imported data):
 *       a subject goes back to available, an event to completed when all
 *       its CRFs are, else to data entry started. The legacy code left the
 *       subject signed.</li>
 *   <li><b>The source may be a locked version:</b> moving data off an
 *       archived version is the usual reason for the move. The legacy single
 *       change allowed it; the legacy batch did not.</li>
 *   <li><b>"Cannot migrate" is read from the rows the site restriction
 *       excludes.</b> The legacy {@code findAllCrfMigrationDoesNotPerform}
 *       tested {@code x != ANY(ids)}, which holds whenever the list names
 *       any other version, so it reported sites that offer both.</li>
 * </ul>
 */
@Service
public class EventCrfVersionMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(EventCrfVersionMigrationService.class);

    /** Event CRF rows a preview returns; its counts cover all of them. */
    public static final int PREVIEW_ROW_CAP = 500;

    /** Skipped (locked) rows a preview returns. */
    public static final int SKIPPED_ROW_CAP = 200;

    static final String SUBJECT_LOCKED = "subject-locked";
    static final String EVENT_LOCKED = "event-locked";
    static final String EVENT_CRF_LOCKED = "event-crf-locked";

    private static final int SIGNED = Status.SIGNED.getId();
    private static final int EVENT_SIGNED = SubjectEventStatus.SIGNED.getId();

    private final DataSource dataSource;

    @Autowired
    public EventCrfVersionMigrationService(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A request the service will not carry out: an HTTP status, a message, and field errors when there are any. */
    public static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final transient List<ValidationErrorBody.FieldError> errors;

        Refusal(int status, String message) {
            this(status, message, List.of());
        }

        Refusal(int status, String message, List<ValidationErrorBody.FieldError> errors) {
            super(message);
            this.status = status;
            this.errors = errors;
        }

        public int status() {
            return status;
        }

        public List<ValidationErrorBody.FieldError> errors() {
            return errors;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Options                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * The versions, sites and event definitions the screen offers for one
     * study. Refuses a caller who may not move data in it, so the screen can
     * say so before anything is chosen.
     */
    public Options options(CRFBean crf, String studyOid, UserAccountBean me) throws Refusal {
        try (Connection c = dataSource.getConnection()) {
            StudyRow study = resolveStudy(c, studyOid);
            requireMigrator(me, study);

            Map<Integer, Integer> countByVersion = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ec.crf_version_id, count(*) FROM event_crf ec"
                            + "  JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id"
                            + "  JOIN crf_version cv ON cv.crf_version_id = ec.crf_version_id"
                            + " WHERE cv.crf_id = ?"
                            + "   AND (ss.study_id = ? OR ss.study_id IN"
                            + "        (SELECT study_id FROM study WHERE parent_study_id = ?))"
                            + " GROUP BY ec.crf_version_id")) {
                ps.setInt(1, crf.getId());
                ps.setInt(2, study.id());
                ps.setInt(3, study.id());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) countByVersion.put(rs.getInt(1), rs.getInt(2));
                }
            }
            List<VersionOption> versions = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT crf_version_id, oc_oid, name, status_id FROM crf_version"
                            + " WHERE crf_id = ? ORDER BY date_created, crf_version_id")) {
                ps.setInt(1, crf.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        versions.add(new VersionOption(rs.getString(2), rs.getString(3),
                                Status.get(rs.getInt(4)).getName(),
                                countByVersion.getOrDefault(rs.getInt(1), 0)));
                    }
                }
            }
            List<Ref> sites = new ArrayList<>();
            sites.add(new Ref(study.oid(), study.name()));
            for (StudyRow site : availableSites(c, study)) sites.add(new Ref(site.oid(), site.name()));

            List<Ref> events = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sed.oc_oid, sed.name FROM study_event_definition sed"
                            + " WHERE sed.study_id = ? AND sed.status_id = ?"
                            + "   AND (EXISTS (SELECT 1 FROM event_definition_crf edc"
                            + "                 WHERE edc.study_event_definition_id = sed.study_event_definition_id"
                            + "                   AND edc.crf_id = ?)"
                            + "        OR EXISTS (SELECT 1 FROM study_event se"
                            + "                     JOIN event_crf ec ON ec.study_event_id = se.study_event_id"
                            + "                     JOIN crf_version cv ON cv.crf_version_id = ec.crf_version_id"
                            + "                    WHERE se.study_event_definition_id = sed.study_event_definition_id"
                            + "                      AND cv.crf_id = ?))"
                            + " ORDER BY sed.ordinal, sed.name")) {
                ps.setInt(1, study.id());
                ps.setInt(2, Status.AVAILABLE.getId());
                ps.setInt(3, crf.getId());
                ps.setInt(4, crf.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) events.add(new Ref(rs.getString(1), rs.getString(2)));
                }
            }
            return new Options(crf.getOid(), crf.getName(), new Ref(study.oid(), study.name()),
                    versions, sites, events);
        } catch (SQLException e) {
            throw new IllegalStateException("Loading the migration options failed", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Preview                                                            */
    /* ------------------------------------------------------------------ */

    /** What a run with the same request would move and clear. Writes nothing. */
    public Preview preview(CRFBean crf, Request request, UserAccountBean me) throws Refusal {
        try (Connection c = dataSource.getConnection()) {
            Scope scope = resolve(c, crf, request, me);
            List<Candidate> candidates = selectCandidates(c, crf, scope, false);
            List<Candidate> eligible = new ArrayList<>();
            List<Skipped> locked = new ArrayList<>();
            int lockedCount = 0;
            for (Candidate cand : candidates) {
                String reason = cand.lockReason();
                if (reason == null) {
                    eligible.add(cand);
                } else {
                    lockedCount++;
                    if (locked.size() < SKIPPED_ROW_CAP) locked.add(new Skipped(cand.row(), reason));
                }
            }
            Set<Integer> subjects = new LinkedHashSet<>();
            Set<Integer> signedSubjects = new LinkedHashSet<>();
            Set<Integer> signedEvents = new LinkedHashSet<>();
            int sdv = 0;
            int signedCrfs = 0;
            List<Row> rows = new ArrayList<>();
            List<Integer> ids = new ArrayList<>(eligible.size());
            for (Candidate cand : eligible) {
                ids.add(cand.eventCrfId());
                subjects.add(cand.studySubjectId());
                if (cand.subjectStatus() == SIGNED) signedSubjects.add(cand.studySubjectId());
                if (cand.eventStatus() == EVENT_SIGNED) signedEvents.add(cand.studyEventId());
                if (cand.sdv()) sdv++;
                if (cand.eventCrfStatus() == SIGNED) signedCrfs++;
                if (rows.size() < PREVIEW_ROW_CAP) rows.add(cand.row());
            }
            List<NotOffered> notOffered = selectNotOffered(c, crf, scope);
            List<HiddenItem> hidden = selectHiddenItems(c, ids, scope.target().getId());
            int hiddenValues = 0;
            for (HiddenItem h : hidden) hiddenValues += h.valueCount();
            LOG.debug("Event CRF migration preview: crf={} study={} {} -> {}: {} event CRF(s), {} locked",
                    crf.getOid(), scope.study().oid(), scope.source().getOid(), scope.target().getOid(),
                    eligible.size(), lockedCount);
            return new Preview(crf.getOid(), crf.getName(),
                    new Ref(scope.study().oid(), scope.study().name()),
                    versionRef(scope.source()), versionRef(scope.target()),
                    scope.siteRefs(), scope.eventRefs(), scope.subjectLabel(),
                    eligible.size(), subjects.size(), sdv, signedSubjects.size(), signedEvents.size(), signedCrfs,
                    rows, eligible.size() > rows.size(), locked, notOffered, hiddenValues, hidden);
        } catch (SQLException e) {
            throw new IllegalStateException("Previewing the event CRF migration failed", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Run                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Moves the event CRFs the request selects, in one transaction. Refused
     * (409, nothing written) when the number of event CRFs to move is not
     * {@code expectedEventCrfCount}.
     */
    public Result run(CRFBean crf, Request request, UserAccountBean me) throws Refusal {
        if (request == null || request.expectedEventCrfCount() == null || request.expectedEventCrfCount() < 0) {
            throw new Refusal(400, "Validation failed", List.of(new ValidationErrorBody.FieldError(
                    "expectedEventCrfCount", "The number of event CRFs the preview showed is required")));
        }
        try (Connection c = dataSource.getConnection()) {
            Scope scope = resolve(c, crf, request, me);
            c.setAutoCommit(false);
            try {
                List<Candidate> eligible = new ArrayList<>();
                for (Candidate cand : selectCandidates(c, crf, scope, true)) {
                    if (cand.lockReason() == null) eligible.add(cand);
                }
                int expected = request.expectedEventCrfCount();
                if (eligible.size() != expected) {
                    c.rollback();
                    throw new Refusal(409, "The selection changed since the preview: " + eligible.size()
                            + " event CRF(s) would move now, the preview showed " + expected
                            + ". Nothing was changed; preview again.");
                }
                Result result = apply(c, crf, scope, eligible, me);
                c.commit();
                LOG.info("Event CRF migration: crf={} study={} {} -> {} by user={}: {} event CRF(s) of {} subject(s);"
                                + " SDV cleared on {}; unsigned {} subject(s), {} event(s), {} event CRF(s)",
                        crf.getOid(), scope.study().oid(), scope.source().getOid(), scope.target().getOid(),
                        me.getName(), result.migratedEventCrfCount(), result.subjectCount(),
                        result.sdvClearedCount(), result.unsignedSubjectCount(), result.unsignedEventCount(),
                        result.unsignedEventCrfCount());
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The event CRF migration failed; nothing was changed", e);
        }
    }

    private Result apply(Connection c, CRFBean crf, Scope scope, List<Candidate> eligible, UserAccountBean me)
            throws SQLException {
        int userId = me.getId();
        int targetId = scope.target().getId();
        Set<Integer> subjects = new LinkedHashSet<>();
        Map<Integer, Boolean> unsignedSubject = new HashMap<>();
        Map<Integer, Boolean> unsignedEvent = new HashMap<>();
        int sdvCleared = 0;
        int crfsUnsigned = 0;
        List<Row> log = new ArrayList<>(eligible.size());

        try (PreparedStatement moveCrf = c.prepareStatement(
                "UPDATE event_crf SET crf_version_id = ?, sdv_status = false, sdv_update_id = ?,"
                        + "       update_id = ?, date_updated = now(),"
                        + "       electronic_signature_status = CASE WHEN status_id = ? THEN false"
                        + "                                     ELSE electronic_signature_status END,"
                        + "       status_id = CASE WHEN status_id = ? THEN ? ELSE status_id END"
                        + " WHERE event_crf_id = ?")) {
            for (Candidate cand : eligible) {
                moveCrf.setInt(1, targetId);
                moveCrf.setInt(2, userId);
                moveCrf.setInt(3, userId);
                moveCrf.setInt(4, SIGNED);
                moveCrf.setInt(5, SIGNED);
                moveCrf.setInt(6, Status.AVAILABLE.getId());
                moveCrf.setInt(7, cand.eventCrfId());
                moveCrf.executeUpdate();
                subjects.add(cand.studySubjectId());
                if (cand.sdv()) sdvCleared++;
                if (cand.eventCrfStatus() == SIGNED) {
                    crfsUnsigned++;
                    insertAudit(c, AuditTypeIds.EVENT_CRF_SIGNATURE_REMOVED, userId, "event_crf",
                            cand.eventCrfId(), "status_id", Status.SIGNED.getName(), Status.AVAILABLE.getName(),
                            cand.eventCrfId(), cand.studyEventId());
                }
                if (cand.subjectStatus() == SIGNED && !unsignedSubject.containsKey(cand.studySubjectId())) {
                    unsignedSubject.put(cand.studySubjectId(), unsignSubject(c, cand.studySubjectId(), userId));
                }
                if (cand.eventStatus() == EVENT_SIGNED && !unsignedEvent.containsKey(cand.studyEventId())) {
                    unsignedEvent.put(cand.studyEventId(), unsignEvent(c, cand.studyEventId(), userId));
                }
                log.add(cand.row());
            }
        }
        int subjectsUnsigned = count(unsignedSubject);
        int eventsUnsigned = count(unsignedEvent);
        if (!eligible.isEmpty()) {
            insertAudit(c, AuditTypeIds.EVENT_CRF_BATCH_MIGRATION, userId, "crf", crf.getId(), crf.getOid(),
                    scope.source().getOid(),
                    "to=" + scope.target().getOid()
                            + " study=" + scope.study().oid()
                            + " eventCrfs=" + eligible.size()
                            + " subjects=" + subjects.size()
                            + " sdvCleared=" + sdvCleared
                            + " subjectsUnsigned=" + subjectsUnsigned
                            + " eventsUnsigned=" + eventsUnsigned
                            + " eventCrfsUnsigned=" + crfsUnsigned,
                    null, null);
        }
        return new Result(crf.getOid(), crf.getName(),
                new Ref(scope.study().oid(), scope.study().name()),
                versionRef(scope.source()), versionRef(scope.target()),
                eligible.size(), subjects.size(), sdvCleared, subjectsUnsigned, eventsUnsigned, crfsUnsigned,
                log, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    /**
     * Takes a subject's signature away: back to the status the audit trail
     * records from before it was first signed, else available.
     */
    private static boolean unsignSubject(Connection c, int studySubjectId, int userId) throws SQLException {
        Integer before = statusBeforeSigning(c, "study_subject", studySubjectId);
        int restored = Status.AVAILABLE.getId();
        if (before != null) {
            Status s = Status.get(before);
            if (s != Status.INVALID && s != Status.SIGNED && !s.isDeleted()) restored = s.getId();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE study_subject SET status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE study_subject_id = ? AND status_id = ?")) {
            ps.setInt(1, restored);
            ps.setInt(2, userId);
            ps.setInt(3, studySubjectId);
            ps.setInt(4, SIGNED);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Takes an event's signature away: back to the status the audit trail
     * records from before it was first signed; without one, completed when
     * all its event CRFs are, else data entry started.
     */
    private static boolean unsignEvent(Connection c, int studyEventId, int userId) throws SQLException {
        Integer before = statusBeforeSigning(c, "study_event", studyEventId);
        int restored;
        if (before != null && before > 0 && before != EVENT_SIGNED
                && before <= SubjectEventStatus.LOCKED.getId()) {
            restored = before;
        } else {
            boolean allComplete = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT bool_and(date_completed IS NOT NULL) FROM event_crf"
                            + " WHERE study_event_id = ? AND status_id NOT IN (?, ?)")) {
                ps.setInt(1, studyEventId);
                ps.setInt(2, Status.DELETED.getId());
                ps.setInt(3, Status.AUTO_DELETED.getId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) allComplete = rs.getBoolean(1);
                }
            }
            restored = allComplete ? SubjectEventStatus.COMPLETED.getId()
                    : SubjectEventStatus.DATA_ENTRY_STARTED.getId();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE study_event SET subject_event_status_id = ?, update_id = ?, date_updated = now()"
                        + " WHERE study_event_id = ? AND subject_event_status_id = ?")) {
            ps.setInt(1, restored);
            ps.setInt(2, userId);
            ps.setInt(3, studyEventId);
            ps.setInt(4, EVENT_SIGNED);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * The status recorded before a row was first signed; the query of the
     * legacy {@code AuditDAO.findLastStatus(table, id, "8")}, on this
     * connection. Null when there is none or it is not a number.
     */
    private static Integer statusBeforeSigning(Connection c, String auditTable, int entityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT old_value FROM audit_log_event"
                        + " WHERE audit_table = ? AND entity_id = ? AND new_value = ?"
                        + " ORDER BY audit_date LIMIT 1")) {
            ps.setString(1, auditTable);
            ps.setInt(2, entityId);
            ps.setString(3, String.valueOf(SIGNED));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String v = rs.getString(1);
                try {
                    return v == null ? null : Integer.valueOf(v.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Selection                                                          */
    /* ------------------------------------------------------------------ */

    /** The request resolved against the database. */
    private record Scope(StudyRow study, CRFVersionBean source, CRFVersionBean target,
                         List<Ref> siteRefs, List<Ref> eventRefs,
                         String subjectLabel, List<Integer> eventCrfIds) {

        String[] siteOids() {
            return siteRefs.stream().map(Ref::oid).toArray(String[]::new);
        }

        String[] eventOids() {
            return eventRefs.stream().map(Ref::oid).toArray(String[]::new);
        }
    }

    private record StudyRow(int id, String oid, String name, int statusId, int parentStudyId) {}

    /** One event CRF the selection matched, with what the move needs to know about it. */
    private record Candidate(int eventCrfId, int studyEventId, int studySubjectId, int eventCrfStatus,
                             boolean sdv, String subjectLabel, int subjectStatus, String siteOid,
                             String siteName, int eventStatus, int ordinal, String sedOid, String sedName) {

        /** Why the event CRF is not moved, or null when it is. */
        String lockReason() {
            if (subjectStatus == Status.LOCKED.getId()) return SUBJECT_LOCKED;
            if (eventStatus == SubjectEventStatus.LOCKED.getId()) return EVENT_LOCKED;
            if (eventCrfStatus == Status.LOCKED.getId()) return EVENT_CRF_LOCKED;
            return null;
        }

        Row row() {
            return new Row(eventCrfId, subjectLabel, siteOid, siteName, sedOid, sedName, ordinal, sdv,
                    subjectStatus == SIGNED, eventStatus == EVENT_SIGNED, eventCrfStatus == SIGNED);
        }
    }

    private static final String FROM_WHERE =
            "  FROM event_crf ec"
                    + "  JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id"
                    + "  JOIN study_event se ON se.study_event_id = ec.study_event_id"
                    + "  JOIN study_event_definition sed"
                    + "       ON sed.study_event_definition_id = se.study_event_definition_id"
                    + "  JOIN study s ON s.study_id = ss.study_id"
                    + " WHERE ec.crf_version_id = ?"
                    + "   AND sed.oc_oid = ANY (?)"
                    + "   AND s.oc_oid = ANY (?)";

    /**
     * The event-definition CRF of the subject's site (or study) restricts the
     * versions and does not offer both. Legacy
     * {@code findAllCRFMigrationReportList} keeps a row when that row has no
     * {@code selected_version_ids}, or lists both versions.
     */
    private static final String RESTRICTED =
            "SELECT 1 FROM event_definition_crf edc"
                    + " WHERE edc.crf_id = ? AND edc.study_id = ss.study_id"
                    + "   AND edc.study_event_definition_id = se.study_event_definition_id"
                    + "   AND COALESCE(edc.selected_version_ids, '') <> ''"
                    + "   AND NOT (? = ANY (string_to_array(edc.selected_version_ids, ','))"
                    + "        AND ? = ANY (string_to_array(edc.selected_version_ids, ',')))";

    private static List<Candidate> selectCandidates(Connection c, CRFBean crf, Scope scope, boolean forUpdate)
            throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT ec.event_crf_id, ec.study_event_id, ec.study_subject_id, ec.status_id AS ec_status,"
                        + "       COALESCE(ec.sdv_status, false) AS sdv_status,"
                        + "       ss.label, ss.status_id AS ss_status, s.oc_oid AS site_oid, s.name AS site_name,"
                        + "       se.subject_event_status_id AS se_status, se.sample_ordinal,"
                        + "       sed.oc_oid AS sed_oid, sed.name AS sed_name")
                .append(FROM_WHERE)
                .append(" AND NOT EXISTS (").append(RESTRICTED).append(')');
        appendNarrowing(sql, scope);
        sql.append(" ORDER BY ec.event_crf_id");
        if (forUpdate) sql.append(" FOR UPDATE OF ec");
        List<Candidate> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindSelection(c, ps, crf, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Candidate(rs.getInt("event_crf_id"), rs.getInt("study_event_id"),
                            rs.getInt("study_subject_id"), rs.getInt("ec_status"), rs.getBoolean("sdv_status"),
                            rs.getString("label"), rs.getInt("ss_status"), rs.getString("site_oid"),
                            rs.getString("site_name"), rs.getInt("se_status"), rs.getInt("sample_ordinal"),
                            rs.getString("sed_oid"), rs.getString("sed_name")));
                }
            }
        }
        return out;
    }

    private static List<NotOffered> selectNotOffered(Connection c, CRFBean crf, Scope scope) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT s.oc_oid, s.name, sed.oc_oid, sed.name, count(*)")
                .append(FROM_WHERE)
                .append(" AND EXISTS (").append(RESTRICTED).append(')');
        appendNarrowing(sql, scope);
        sql.append(" GROUP BY s.oc_oid, s.name, sed.oc_oid, sed.name ORDER BY s.name, sed.name");
        List<NotOffered> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindSelection(c, ps, crf, scope);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new NotOffered(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5)));
                }
            }
        }
        return out;
    }

    /** Items with entered values in these event CRFs that the target version does not contain. */
    private static List<HiddenItem> selectHiddenItems(Connection c, List<Integer> eventCrfIds, int targetVersionId)
            throws SQLException {
        List<HiddenItem> out = new ArrayList<>();
        if (eventCrfIds.isEmpty()) return out;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT i.name, i.oc_oid, count(*) FROM item_data id JOIN item i ON i.item_id = id.item_id"
                        + " WHERE id.event_crf_id = ANY (?)"
                        + "   AND id.status_id NOT IN (?, ?)"
                        + "   AND COALESCE(id.value, '') <> ''"
                        + "   AND NOT EXISTS (SELECT 1 FROM item_form_metadata ifm"
                        + "                    WHERE ifm.item_id = id.item_id AND ifm.crf_version_id = ?)"
                        + " GROUP BY i.name, i.oc_oid ORDER BY i.name")) {
            ps.setArray(1, c.createArrayOf("integer", eventCrfIds.toArray()));
            ps.setInt(2, Status.DELETED.getId());
            ps.setInt(3, Status.AUTO_DELETED.getId());
            ps.setInt(4, targetVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new HiddenItem(rs.getString(1), rs.getString(2), rs.getInt(3)));
            }
        }
        return out;
    }

    private static void appendNarrowing(StringBuilder sql, Scope scope) {
        if (scope.subjectLabel() != null) sql.append(" AND ss.label = ?");
        if (scope.eventCrfIds() != null) sql.append(" AND ec.event_crf_id = ANY (?)");
    }

    private static void bindSelection(Connection c, PreparedStatement ps, CRFBean crf, Scope scope)
            throws SQLException {
        int i = 1;
        ps.setInt(i++, scope.source().getId());
        ps.setArray(i++, c.createArrayOf("text", scope.eventOids()));
        ps.setArray(i++, c.createArrayOf("text", scope.siteOids()));
        ps.setInt(i++, crf.getId());
        ps.setString(i++, String.valueOf(scope.source().getId()));
        ps.setString(i++, String.valueOf(scope.target().getId()));
        if (scope.subjectLabel() != null) ps.setString(i++, scope.subjectLabel());
        if (scope.eventCrfIds() != null) {
            ps.setArray(i, c.createArrayOf("integer", scope.eventCrfIds().toArray()));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Validation                                                         */
    /* ------------------------------------------------------------------ */

    private Scope resolve(Connection c, CRFBean crf, Request request, UserAccountBean me)
            throws Refusal, SQLException {
        if (request == null) {
            throw new Refusal(400, "Request body is required",
                    List.of(new ValidationErrorBody.FieldError("body", "missing")));
        }
        if (crf.getStatus() != null && crf.getStatus().isDeleted()) {
            throw new Refusal(409, "CRF '" + crf.getOid() + "' is removed — restore it first");
        }
        StudyRow study = resolveStudy(c, request.studyOid());
        requireMigrator(me, study);
        if (study.statusId() != Status.AVAILABLE.getId()) {
            throw new Refusal(409, "Study '" + study.name() + "' is " + Status.get(study.statusId()).getName()
                    + "; event CRFs can only be moved in an available study");
        }

        List<ValidationErrorBody.FieldError> errors = new ArrayList<>();
        CRFVersionDAO versionDao = new CRFVersionDAO(dataSource);
        CRFVersionBean source = version(versionDao, crf, request.sourceVersionOid(), "sourceVersionOid", errors);
        CRFVersionBean target = version(versionDao, crf, request.targetVersionOid(), "targetVersionOid", errors);
        if (source != null && target != null && source.getId() == target.getId()) {
            errors.add(new ValidationErrorBody.FieldError("targetVersionOid",
                    "The current and the new version must differ"));
        }
        if (source != null && source.getStatus() != null && source.getStatus().isDeleted()) {
            errors.add(new ValidationErrorBody.FieldError("sourceVersionOid",
                    "Version '" + source.getName() + "' is removed"));
        }
        if (target != null && (target.getStatus() == null || !target.getStatus().isAvailable())) {
            errors.add(new ValidationErrorBody.FieldError("targetVersionOid",
                    "Version '" + target.getName() + "' is not available"));
        }

        List<Ref> sites = resolveSites(c, study, request.siteOids(), errors);
        List<Ref> events = resolveEvents(c, study, request.eventDefinitionOids(), errors);

        String label = request.studySubjectLabel() == null ? null : request.studySubjectLabel().trim();
        if (label != null && label.isEmpty()) label = null;
        if (label != null && !subjectExists(c, study, label)) {
            errors.add(new ValidationErrorBody.FieldError("studySubjectLabel",
                    "No subject '" + label + "' in study '" + study.name() + "'"));
        }
        List<Integer> ids = null;
        if (request.eventCrfIds() != null) {
            ids = new ArrayList<>();
            for (Integer id : request.eventCrfIds()) if (id != null) ids.add(id);
            if (ids.isEmpty()) ids = null;
        }
        if (!errors.isEmpty()) throw new Refusal(400, "Validation failed", errors);
        return new Scope(study, source, target, sites, events, label, ids);
    }

    private static CRFVersionBean version(CRFVersionDAO versionDao, CRFBean crf, String oid, String field,
                                          List<ValidationErrorBody.FieldError> errors) {
        if (oid == null || oid.isBlank()) {
            errors.add(new ValidationErrorBody.FieldError(field, "Choose a version"));
            return null;
        }
        CRFVersionBean v = versionDao.findByOid(oid.trim());
        if (v == null || v.getId() == 0 || v.getCrfId() != crf.getId()) {
            errors.add(new ValidationErrorBody.FieldError(field,
                    "No version '" + oid.trim() + "' of CRF '" + crf.getName() + "'"));
            return null;
        }
        return v;
    }

    /** The study named by {@code studyOid}; a site resolves to its parent study. */
    private static StudyRow resolveStudy(Connection c, String studyOid) throws Refusal, SQLException {
        if (studyOid == null || studyOid.isBlank()) {
            throw new Refusal(400, "Validation failed",
                    List.of(new ValidationErrorBody.FieldError("studyOid", "A study is required")));
        }
        StudyRow row = studyByOid(c, studyOid.trim());
        if (row == null) {
            throw new Refusal(400, "Validation failed",
                    List.of(new ValidationErrorBody.FieldError("studyOid", "No study '" + studyOid.trim() + "'")));
        }
        if (row.parentStudyId() > 0) {
            StudyRow parent = studyById(c, row.parentStudyId());
            if (parent != null) return parent;
        }
        return row;
    }

    /**
     * An active Data Manager (study director) or CRC (coordinator) binding on
     * the study itself; legacy {@code runPreviewTest}. Site-level bindings do
     * not count: the move spans the study's sites.
     */
    private void requireMigrator(UserAccountBean me, StudyRow study) throws Refusal {
        List<StudyUserRoleBean> bindings;
        try {
            bindings = new UserAccountDAO(dataSource).findAllRolesByUserName(me.getName());
        } catch (RuntimeException e) {
            bindings = List.of();
        }
        for (StudyUserRoleBean b : bindings) {
            if (b == null || b.getRole() == null || b.getStudyId() != study.id()) continue;
            if (b.getStatus() == null || b.getStatus().getId() != Status.AVAILABLE.getId()) continue;
            if (b.getRole() == Role.STUDYDIRECTOR || b.getRole() == Role.COORDINATOR) return;
        }
        throw new Refusal(403, "Only a Data Manager or CRC of study '" + study.name()
                + "' may move event CRFs to another CRF version");
    }

    private static List<Ref> resolveSites(Connection c, StudyRow study, List<String> oids,
                                          List<ValidationErrorBody.FieldError> errors) throws SQLException {
        List<Ref> out = new ArrayList<>();
        if (oids == null || oids.stream().allMatch(o -> o == null || o.isBlank())) {
            out.add(new Ref(study.oid(), study.name()));
            for (StudyRow site : availableSites(c, study)) out.add(new Ref(site.oid(), site.name()));
            return out;
        }
        for (String raw : oids) {
            if (raw == null || raw.isBlank()) continue;
            StudyRow site = studyByOid(c, raw.trim());
            if (site == null || (site.id() != study.id() && site.parentStudyId() != study.id())) {
                errors.add(new ValidationErrorBody.FieldError("siteOids",
                        "No site '" + raw.trim() + "' in study '" + study.name() + "'"));
            } else if (site.statusId() == Status.AVAILABLE.getId()) {
                out.add(new Ref(site.oid(), site.name()));
            }
        }
        return out;
    }

    private static List<Ref> resolveEvents(Connection c, StudyRow study, List<String> oids,
                                           List<ValidationErrorBody.FieldError> errors) throws SQLException {
        List<Ref> out = new ArrayList<>();
        boolean all = oids == null || oids.stream().allMatch(o -> o == null || o.isBlank());
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT oc_oid, name, status_id FROM study_event_definition WHERE study_id = ?"
                        + " ORDER BY ordinal, name")) {
            ps.setInt(1, study.id());
            Map<String, Ref> available = new HashMap<>();
            Set<String> known = new LinkedHashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    known.add(rs.getString(1));
                    if (rs.getInt(3) == Status.AVAILABLE.getId()) {
                        Ref ref = new Ref(rs.getString(1), rs.getString(2));
                        available.put(ref.oid(), ref);
                        if (all) out.add(ref);
                    }
                }
            }
            if (all) return out;
            for (String raw : oids) {
                if (raw == null || raw.isBlank()) continue;
                String oid = raw.trim();
                if (!known.contains(oid)) {
                    errors.add(new ValidationErrorBody.FieldError("eventDefinitionOids",
                            "No event definition '" + oid + "' in study '" + study.name() + "'"));
                } else if (available.containsKey(oid)) {
                    out.add(available.get(oid));
                }
            }
        }
        return out;
    }

    private static boolean subjectExists(Connection c, StudyRow study, String label) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM study_subject WHERE label = ?"
                        + "   AND (study_id = ? OR study_id IN (SELECT study_id FROM study WHERE parent_study_id = ?))"
                        + " LIMIT 1")) {
            ps.setString(1, label);
            ps.setInt(2, study.id());
            ps.setInt(3, study.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static List<StudyRow> availableSites(Connection c, StudyRow study) throws SQLException {
        List<StudyRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_id, oc_oid, name, status_id, COALESCE(parent_study_id, 0) FROM study"
                        + " WHERE parent_study_id = ? AND status_id = ? ORDER BY name")) {
            ps.setInt(1, study.id());
            ps.setInt(2, Status.AVAILABLE.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(studyRow(rs));
            }
        }
        return out;
    }

    private static StudyRow studyByOid(Connection c, String oid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_id, oc_oid, name, status_id, COALESCE(parent_study_id, 0) FROM study WHERE oc_oid = ?")) {
            ps.setString(1, oid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? studyRow(rs) : null;
            }
        }
    }

    private static StudyRow studyById(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT study_id, oc_oid, name, status_id, COALESCE(parent_study_id, 0) FROM study WHERE study_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? studyRow(rs) : null;
            }
        }
    }

    private static StudyRow studyRow(ResultSet rs) throws SQLException {
        return new StudyRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5));
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private static Ref versionRef(CRFVersionBean v) {
        return new Ref(v.getOid(), v.getName());
    }

    private static int count(Map<Integer, Boolean> changed) {
        int n = 0;
        for (Boolean b : changed.values()) if (Boolean.TRUE.equals(b)) n++;
        return n;
    }

    private static void insertAudit(Connection c, int typeId, int userId, String auditTable, int entityId,
                                    String entityName, String oldValue, String newValue,
                                    Integer eventCrfId, Integer studyEventId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, audit_table,"
                        + " entity_id, entity_name, old_value, new_value, event_crf_id, study_event_id)"
                        + " VALUES (?, now(), ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, typeId);
            ps.setInt(2, userId);
            ps.setString(3, auditTable);
            ps.setInt(4, entityId);
            ps.setString(5, entityName == null ? "" : entityName);
            ps.setString(6, oldValue == null ? "" : oldValue);
            ps.setString(7, newValue == null ? "" : newValue);
            if (eventCrfId == null) ps.setNull(8, java.sql.Types.INTEGER);
            else ps.setInt(8, eventCrfId);
            if (studyEventId == null) ps.setNull(9, java.sql.Types.INTEGER);
            else ps.setInt(9, studyEventId);
            ps.executeUpdate();
        }
    }
}
