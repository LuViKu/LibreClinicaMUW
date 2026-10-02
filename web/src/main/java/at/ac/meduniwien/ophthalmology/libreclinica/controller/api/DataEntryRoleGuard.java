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

import jakarta.servlet.http.HttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;

import org.springframework.http.ResponseEntity;

/**
 * Refuses clinical data writes (enrolling a subject, scheduling an event,
 * starting, saving or completing a CRF) to a session that holds no role
 * in its study.
 *
 * <p>That session role is the legacy "invalid" one: a system
 * administrator who opened a study they are not bound to
 * ({@code POST /me/activeStudy}), or any account whose study was removed
 * under it. The legacy servlets refuse it through
 * {@code SubmitDataServlet.maySubmitData}, which has no system
 * administrator bypass. An administrator who needs to enter data grants
 * themselves a role first, which leaves a role binding and its audit row.
 *
 * <p>A session without any role attribute is left to the endpoint's own
 * checks: login and the study picker always set one.
 */
final class DataEntryRoleGuard {

    private DataEntryRoleGuard() {}

    /** A 403 when the session's study role is the legacy "invalid" one, otherwise {@code null}. */
    static ResponseEntity<?> refuseWithoutStudyRole(HttpSession session) {
        Object attribute = session.getAttribute("userRole");
        if (attribute instanceof StudyUserRoleBean role && Role.INVALID.equals(role.getRole())) {
            return ResponseEntity.status(403).body(Map.of("message",
                    "You hold no role in this study. Data entry needs a study role; "
                            + "an administrator can grant one under Users."));
        }
        return null;
    }
}
