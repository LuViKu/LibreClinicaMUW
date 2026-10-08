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
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.EventCrfWriteRules;

/**
 * The one refusal for a clinical write into a record that is closed to data
 * entry: a study that is locked, frozen or removed, a subject that is locked
 * or removed, a visit that is removed, and an event CRF that is removed,
 * locked or signed (itself, or through its visit, subject or study).
 *
 * <p>{@link ClinicalWriteAuthorization} says which roles may enter data;
 * this class says where. Each data-entry endpoint calls {@link #refuseIfClosed}
 * once, after its role and site-visibility checks, so a caller who may not
 * see the record learns nothing about its state. The legacy data-entry
 * servlets refuse a locked or frozen study
 * ({@code SecureController.checkStudyLocked} and {@code checkStudyFrozen}),
 * and {@link SubjectLockGuard} states that a locked subject takes no CRF
 * data; the event CRF rules are {@link EventCrfWriteRules#refusal}.
 *
 * <p>The study's status is read from the database, not from the session:
 * the session's study bean dates from the moment the study was bound, and
 * a study locked since then is locked. The subject's own study (a site)
 * and that study's parent count as well as the active study.
 *
 * <p><b>Every refusal is a 409 with a {@code code} and a {@code message}.</b>
 * The request is well-formed and the caller may make it, but not in this
 * state. The first of these that applies is reported, widest scope first,
 * and within a scope removal before a lock before a signature:
 * <ol>
 *   <li>{@code STUDY_LOCKED}, {@code STUDY_FROZEN}, {@code STUDY_REMOVED}</li>
 *   <li>{@code SUBJECT_LOCKED}, {@code SUBJECT_REMOVED}</li>
 *   <li>{@code EVENT_REMOVED}</li>
 *   <li>{@code EVENT_CRF_REMOVED}, {@code EVENT_CRF_LOCKED},
 *       {@code EVENT_CRF_SIGNED}: the event CRF's own status and the
 *       remainder of {@link EventCrfWriteRules#refusal} (a locked or signed
 *       visit, a signed subject)</li>
 * </ol>
 */
final class ClinicalRecordGuard {

    private ClinicalRecordGuard() {}

    /**
     * @param activeStudy the session's study
     * @param ss          the subject the write goes to; {@code null} skips
     *                    the subject and its study
     * @param event       the visit, where the endpoint has it; may be {@code null}
     * @param ecb         the event CRF, where the write goes to one; may be {@code null}
     * @param operation   what is refused, as in "saving CRF data"
     * @return a 409 naming what is closed, or {@code null} when the write may go ahead
     */
    static ResponseEntity<?> refuseIfClosed(DataSource dataSource, StudyBean activeStudy,
                                            StudySubjectBean ss, StudyEventBean event,
                                            EventCRFBean ecb, String operation) {
        String closedStudy = closedStudy(dataSource, activeStudy, ss);
        if (closedStudy != null) {
            return conflict("STUDY_" + closedStudy.toUpperCase(Locale.ROOT),
                    "The study is " + closedStudy + "; " + operation + " is refused.");
        }
        ResponseEntity<?> locked = SubjectLockGuard.refuseIfLocked(ss, operation);
        if (locked != null) {
            return locked;
        }
        if (ss != null && isRemoved(ss.getStatus())) {
            return conflict("SUBJECT_REMOVED", "Subject is removed; " + operation
                    + " is refused until the subject is restored.");
        }
        if (event == null && ecb != null && ecb.getStudyEventId() > 0) {
            event = (StudyEventBean) new StudyEventDAO(dataSource).findByPK(ecb.getStudyEventId());
        }
        if (event != null && isRemoved(event.getStatus())) {
            return conflict("EVENT_REMOVED", "The visit is removed; " + operation
                    + " is refused until the visit is restored.");
        }
        if (ecb != null) {
            if (isRemoved(ecb.getStatus())) {
                return conflict("EVENT_CRF_REMOVED", "The CRF is removed; " + operation
                        + " is refused until the CRF is restored.");
            }
            return refuseUnlessWritable(dataSource, ecb.getId(), operation);
        }
        return null;
    }

    /**
     * Only the event CRF's own rules ({@link EventCrfWriteRules#refusal}),
     * for a caller that has no study or subject bean. Prefer
     * {@link #refuseIfClosed}, which includes this check.
     *
     * @return a 409 when the event CRF's values may not change, else {@code null}
     */
    static ResponseEntity<?> refuseUnlessWritable(DataSource dataSource, int eventCrfId, String operation) {
        EventCrfWriteRules.Refusal refusal;
        try (Connection c = dataSource.getConnection()) {
            refusal = EventCrfWriteRules.refusal(c, eventCrfId);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the state of event_crf " + eventCrfId, e);
        }
        if (refusal == null) {
            return null;
        }
        return conflict("EVENT_CRF_" + refusal.name(),
                "event_crf " + eventCrfId + " " + refusal.reason() + "; " + operation + " is refused.");
    }

    /**
     * The state of the first study, of the active study, the subject's
     * study and that study's parent, that does not accept data entry
     * ({@link StudyAdminAuthorization#studyAcceptsWrites}), or {@code null}.
     */
    private static String closedStudy(DataSource dataSource, StudyBean activeStudy,
                                      StudySubjectBean ss) {
        StudyDAO studyDao = new StudyDAO(dataSource);
        StudyBean active = load(studyDao, activeStudy == null ? 0 : activeStudy.getId());
        String state = stateIfClosed(active);
        if (state != null || ss == null || ss.getStudyId() <= 0) {
            return state;
        }
        StudyBean owner = ss.getStudyId() == active.getId() ? active : load(studyDao, ss.getStudyId());
        state = stateIfClosed(owner);
        if (state != null || owner.getParentStudyId() <= 0 || owner.getParentStudyId() == active.getId()) {
            return state;
        }
        return stateIfClosed(load(studyDao, owner.getParentStudyId()));
    }

    private static StudyBean load(StudyDAO studyDao, int studyId) {
        StudyBean study = studyId > 0 ? (StudyBean) studyDao.findByPK(studyId) : null;
        return study != null ? study : new StudyBean();
    }

    /** "locked", "frozen" or "removed" for a study that takes no data; else {@code null}. */
    private static String stateIfClosed(StudyBean study) {
        if (study.getId() <= 0 || StudyAdminAuthorization.studyAcceptsWrites(study)) {
            return null;
        }
        Status status = study.getStatus();
        if (Status.LOCKED.equals(status)) {
            return "locked";
        }
        if (Status.FROZEN.equals(status)) {
            return "frozen";
        }
        return "removed";
    }

    private static boolean isRemoved(Status status) {
        return Status.DELETED.equals(status) || Status.AUTO_DELETED.equals(status);
    }

    static ResponseEntity<?> conflict(String code, String message) {
        return ResponseEntity.status(409).body(Map.of("code", code, "message", message));
    }
}
