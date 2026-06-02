/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import java.util.Map;
import org.springframework.http.ResponseEntity;

/**
 * Phase E A3-lock follow-up — refuse write operations on subjects
 * whose status is {@link Status#LOCKED}.
 *
 * <p>{@link Status#LOCKED} is set via {@code POST /subjects/{oid}/lock}
 * (DM / Admin). While locked, no other actor may edit demographics,
 * schedule / edit / cancel events, save CRF items, or flip SDV state.
 * Unlocking is the only path forward; the legacy {@code study_subject_trigger}
 * already audits the status transitions on both sides.
 *
 * <p>Intentional non-scope:
 * <ul>
 *   <li><b>Discrepancy notes</b> — adding a query <em>about</em> a
 *   locked subject's data is legitimate Monitor work; LOCKED freezes
 *   the data, not the conversation about it.</li>
 *   <li><b>Reads</b> — list / detail / preflight endpoints stay open
 *   for monitoring and review.</li>
 * </ul>
 */
public final class SubjectLockGuard {

    private SubjectLockGuard() {}

    /**
     * @return a 409 {@code ResponseEntity} carrying a structured
     *         message if {@code ss} is locked, or {@code null} when
     *         the caller may proceed. {@code ss == null} also returns
     *         {@code null} — the caller is expected to have done the
     *         404 check earlier.
     */
    static ResponseEntity<?> refuseIfLocked(StudySubjectBean ss, String operation) {
        if (ss == null) {
            return null;
        }
        if (ss.getStatus() != null && ss.getStatus().equals(Status.LOCKED)) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "Subject is locked; " + operation
                            + " is refused until the Data Manager unlocks the subject."));
        }
        return null;
    }
}
