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

/**
 * Phase E A3 — role gate for the subject lifecycle endpoints:
 * {@code POST /api/v1/subjects/{oid}/remove} (soft-delete) and
 * {@code POST /api/v1/subjects/{oid}/restore}.
 *
 * <p>Mirrors the legacy {@code RemoveSubjectServlet#mayProceed} +
 * {@code RestoreSubjectServlet#mayProceed} authorization (both
 * inherit from {@code SecureController} and check
 * {@code currentRole.getRole().equals(Role.STUDYDIRECTOR)} or
 * {@code Role.ADMIN}). Investigator / CRC / RA roles cannot soft-
 * delete or restore subjects — they would need to log a query and
 * escalate to a Data Manager.
 *
 * <p>Permitted:
 * <ul>
 *   <li>{@link Role#STUDYDIRECTOR} (Data Manager)</li>
 *   <li>{@link Role#ADMIN}</li>
 * </ul>
 *
 * <p>Forbidden: every other role.
 */
final class SubjectLifecycleAuthorization {

    private SubjectLifecycleAuthorization() {}

    /**
     * @param roleId  legacy {@link Role} id from the session-bound
     *                {@code StudyUserRoleBean}; pass 0 for no role.
     * @return true when the caller's role may remove or restore
     *         subjects.
     */
    static boolean roleMayManageLifecycle(int roleId) {
        return roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.ADMIN.getId();
    }
}
