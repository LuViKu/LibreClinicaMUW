/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;

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
     * Multi-binding variant — when the caller has more than one
     * {@link StudyUserRoleBean} on the same study (e.g. both
     * {@code Investigator} for data entry AND {@code STUDYDIRECTOR}
     * for study config), the session-attribute {@code userRole}
     * non-deterministically picks one of them. Authorization decisions
     * must walk the full binding set + admit the operation when ANY of
     * the user's active bindings is authorized — not just the one that
     * happens to be in the session attribute.
     *
     * <p>Each binding is checked through
     * {@link #roleMayEditStudy(UserAccountBean, StudyUserRoleBean, StudyBean)}
     * so the single-role gate stays the source of truth for which
     * legacy {@link Role} ids count as study-config editors. Bindings
     * whose {@code status} is not {@link Status#AVAILABLE} are
     * filtered out so disabled/deleted role rows can't grant access.
     *
     * @param me            the authenticated user (sysadmin shortcut
     *                      applies)
     * @param myBindings    every active {@link StudyUserRoleBean} for
     *                      {@code me}, typically from
     *                      {@code UserAccountDAO.findAllRolesByUserName}
     * @param target        the study being mutated
     * @return {@code true} when any active binding admits the edit
     */
    static boolean userMayEditStudy(UserAccountBean me,
                                    List<StudyUserRoleBean> myBindings,
                                    StudyBean target) {
        if (me == null) return false;
        if (me.isSysAdmin()) return true;
        if (myBindings == null || myBindings.isEmpty()) return false;
        if (target == null) return false;
        for (StudyUserRoleBean binding : myBindings) {
            if (binding == null || binding.getRole() == null) continue;
            if (binding.getStatus() == null
                    || binding.getStatus().getId() != Status.AVAILABLE.getId()) {
                continue;
            }
            if (roleMayEditStudy(me, binding, target)) return true;
        }
        return false;
    }

    /**
     * Study-independent variant — admits when the caller holds an
     * AVAILABLE {@link Role#STUDYDIRECTOR} or {@link Role#COORDINATOR}
     * binding on ANY study. Used for resources that are not scoped to
     * a single study (CRF library, response sets) but still want the
     * same Director/Coordinator-level gate.
     *
     * <p>Sysadmin shortcuts as always. RA / RA2 / Investigator /
     * Monitor bindings don't count — same role taxonomy as
     * {@link #roleMayEditStudy}, just without the target check.
     */
    static boolean userMayManageCrfLibrary(UserAccountBean me, DataSource dataSource) {
        if (me == null) return false;
        if (me.isSysAdmin()) return true;
        if (dataSource == null) return false;
        ArrayList<StudyUserRoleBean> bindings;
        try {
            UserAccountDAO dao = new UserAccountDAO(dataSource);
            bindings = dao.findAllRolesByUserName(me.getName());
        } catch (RuntimeException e) {
            return false;
        }
        if (bindings == null) return false;
        for (StudyUserRoleBean b : bindings) {
            if (b == null || b.getRole() == null) continue;
            if (b.getStatus() == null
                    || b.getStatus().getId() != Status.AVAILABLE.getId()) continue;
            Role r = b.getRole();
            if (r == Role.STUDYDIRECTOR || r == Role.COORDINATOR) return true;
        }
        return false;
    }

    /**
     * DAO-aware overload — loads the caller's full binding set via
     * {@link UserAccountDAO#findAllRolesByUserName(String)} and
     * delegates to {@link #userMayEditStudy(UserAccountBean, List, StudyBean)}.
     * The single-line entry point most controllers want when they
     * already have a {@link DataSource} in scope.
     */
    static boolean userMayEditStudy(UserAccountBean me,
                                    StudyBean target,
                                    DataSource dataSource) {
        if (me == null) return false;
        if (me.isSysAdmin()) return true;
        if (target == null || dataSource == null) return false;
        ArrayList<StudyUserRoleBean> bindings;
        try {
            UserAccountDAO dao = new UserAccountDAO(dataSource);
            bindings = dao.findAllRolesByUserName(me.getName());
        } catch (RuntimeException e) {
            // DAO unavailable (mocked DataSource in unit tests, or a
            // genuine DB outage in production). Either way, fail
            // closed: deny the write rather than risk granting it on
            // partial information. Production wiring surfaces the
            // underlying error via the controller's audit + log path
            // upstream of this gate.
            return false;
        }
        return userMayEditStudy(me, bindings, target);
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
