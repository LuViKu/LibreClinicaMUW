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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/**
 * Phase E A7 — user-administration authorization.
 *
 * <p>Gates create / edit / disable / restore / reset-password /
 * role-assignment endpoints to <b>sysadmin only</b>, mirroring the
 * legacy admin servlets exactly:
 * <ul>
 *   <li>{@code CreateUserAccountServlet:80} — {@code if (!ub.isSysAdmin()) throw …}</li>
 *   <li>{@code EditUserAccountServlet:78}, {@code DeleteUserServlet:51},
 *       {@code SetUserRoleServlet:52}, {@code EditStudyUserRoleServlet:48},
 *       {@code DeleteStudyUserRoleServlet:40} — same gate.</li>
 * </ul>
 *
 * <p>The SPA's "Data Manager" role concept does <em>not</em> grant
 * user administration in the legacy model. We preserve that invariant
 * here for GCP audit fidelity — any decision to widen the gate would
 * need a documented compliance review, not a quiet code change.
 *
 * <p>Site-level role legality is a separate concern: roles
 * {@code COORDINATOR}(2) and {@code STUDYDIRECTOR}(3) cannot be granted
 * at site level (only at the parent study). Mirrors
 * {@code SetUserRoleServlet:152–156} and the create-time validation in
 * {@code CreateUserAccountServlet:125–170}.
 */
public final class UserAdminAuthorization {

    private UserAdminAuthorization() {}

    /**
     * @return {@code true} when {@code ub} is a sysadmin and may perform
     *         any user-administration write. Returns {@code false} for
     *         a null bean or a non-sysadmin user.
     */
    static boolean roleMayAdministerUsers(UserAccountBean ub) {
        return ub != null && ub.isSysAdmin();
    }

    /**
     * Site-level role legality — Coordinator (2) and Study Director (3)
     * cannot be granted on a site (a study with non-zero parent).
     *
     * @return {@code true} when the {@code (role, study)} combination is
     *         legal under the legacy model.
     */
    static boolean roleAssignmentIsLegal(Role role, StudyBean study) {
        if (role == null || study == null) return false;
        boolean isSite = study.getParentStudyId() > 0;
        if (!isSite) return true;
        return role != Role.COORDINATOR && role != Role.STUDYDIRECTOR;
    }
}
