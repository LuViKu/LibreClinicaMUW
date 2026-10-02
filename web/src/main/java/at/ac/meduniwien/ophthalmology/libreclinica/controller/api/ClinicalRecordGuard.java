/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

/**
 * Refuses a data-entry write into a record that is closed to data entry:
 * a study that is locked, frozen or removed, a subject that is locked or
 * removed, and a visit or an event CRF that is removed.
 *
 * <p>{@link ClinicalWriteAuthorization} says which roles may enter data;
 * this class says where. The legacy data-entry servlets refuse a locked or
 * frozen study ({@code SecureController.checkStudyLocked} and
 * {@code checkStudyFrozen}, called from {@code InitialDataEntryServlet}),
 * and {@link SubjectLockGuard} states that a locked subject takes no CRF
 * data. Each data-entry endpoint calls {@link #refuseIfClosed} after its
 * role and site-visibility checks, so a caller who may not see the record
 * learns nothing about its state.
 *
 * <p>The study's status is read from the database, not from the session:
 * the session's study bean dates from the moment the study was bound, and
 * a study locked since then is locked. The subject's own study (a site)
 * and that study's parent count as well as the active study.
 *
 * <p>The refusal is a 409, as {@link SubjectLockGuard}'s is: the request
 * is well-formed and the caller may make it, but not in this state. A
 * signed or locked event CRF is refused by each endpoint's own check.
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
            return conflict("The study is " + closedStudy + "; " + operation + " is refused.");
        }
        ResponseEntity<?> locked = SubjectLockGuard.refuseIfLocked(ss, operation);
        if (locked != null) {
            return locked;
        }
        if (ss != null && isRemoved(ss.getStatus())) {
            return conflict("Subject is removed; " + operation
                    + " is refused until the subject is restored.");
        }
        if (event != null && isRemoved(event.getStatus())) {
            return conflict("The visit is removed; " + operation
                    + " is refused until the visit is restored.");
        }
        if (ecb != null && isRemoved(ecb.getStatus())) {
            return conflict("The CRF is removed; " + operation
                    + " is refused until the CRF is restored.");
        }
        return null;
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

    private static ResponseEntity<?> conflict(String message) {
        return ResponseEntity.status(409).body(Map.of("message", message));
    }
}
