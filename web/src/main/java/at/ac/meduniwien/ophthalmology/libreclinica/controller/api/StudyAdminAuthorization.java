/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/**
 * Phase E A8 — study-administration authorization.
 *
 * <p>Mirror of {@link UserAdminAuthorization} for the study-config
 * surface. Concentrates the legacy {@code mayProceed()} gates that
 * recur across every {@code CreateStudy* / UpdateStudy* /
 * RemoveStudy*} servlet.
 *
 * <p>Legacy precedents:
 * <ul>
 *   <li>{@code CreateStudyServlet:261–269} — sysadmin only</li>
 *   <li>{@code UpdateStudyServletNew:51–61} — sysadmin OR Director /
 *       Coordinator bound to the target</li>
 *   <li>{@code RemoveStudyServlet:58–66} — sysadmin only</li>
 *   <li>{@code RestoreStudyServlet:60–66} — sysadmin only</li>
 *   <li>{@code SecureController.checkStudyLocked} —
 *       refuse writes when the study is LOCKED / FROZEN</li>
 * </ul>
 *
 * <p>The MUW interpretation for status-transition writes (A8.5) defaults
 * to <b>sysadmin-only</b>: the legacy path accepts director/coordinator
 * too, but transitions to LOCKED / FROZEN / DELETED carry audit-of-
 * record implications and warrant the tighter gate. If compliance
 * review demands legacy parity, widen via configuration rather than a
 * silent code change.
 */
public final class StudyAdminAuthorization {

    private StudyAdminAuthorization() {}

    /**
     * @return {@code true} when {@code me} may create a new top-level
     *         study. Sysadmin only — legacy
     *         {@code CreateStudyServlet:261–269}.
     */
    static boolean roleMayCreateStudy(UserAccountBean me) {
        return me != null && me.isSysAdmin();
    }

    /**
     * @return {@code true} when {@code me} may edit identity /
     *         protocol / metadata fields on {@code target}. Sysadmin
     *         always passes; {@link Role#STUDYDIRECTOR} and
     *         {@link Role#COORDINATOR} pass when their current session
     *         role is bound to {@code target} (parent or site).
     *         Mirrors {@code UpdateStudyServletNew:51–61}.
     */
    static boolean roleMayEditStudy(UserAccountBean me,
                                    StudyUserRoleBean currentRole,
                                    StudyBean target) {
        if (me == null) return false;
        if (me.isSysAdmin()) return true;
        if (currentRole == null || currentRole.getRole() == null) return false;
        if (target == null) return false;
        Role r = currentRole.getRole();
        if (r != Role.STUDYDIRECTOR && r != Role.COORDINATOR) return false;
        // Role must be bound to the target study itself OR (if the
        // target is a site) to its parent.
        int boundStudyId = currentRole.getStudyId();
        if (boundStudyId == target.getId()) return true;
        return target.getParentStudyId() > 0 && boundStudyId == target.getParentStudyId();
    }

    /**
     * @return {@code true} when {@code me} may transition study
     *         status (LOCK / FROZEN / DELETE / restore). Sysadmin only
     *         under the MUW interpretation; widen if compliance review
     *         demands legacy parity.
     */
    static boolean roleMayLifecycleStudy(UserAccountBean me) {
        return me != null && me.isSysAdmin();
    }

    /**
     * @return {@code true} when the study currently accepts writes —
     *         i.e. its status is not in a terminal state. Mirrors
     *         {@code SecureController.checkStudyLocked} +
     *         {@code checkStudyFrozen}. Returns {@code false} for null,
     *         LOCKED, FROZEN, DELETED, AUTO_DELETED.
     */
    static boolean studyAcceptsWrites(StudyBean target) {
        if (target == null || target.getStatus() == null) return false;
        Status s = target.getStatus();
        return s != Status.LOCKED
                && s != Status.FROZEN
                && s != Status.DELETED
                && s != Status.AUTO_DELETED;
    }
}
