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

/**
 * Phase E.6 restore-quickwins — authorization gate for the
 * {@code POST /pages/api/v1/eventCrfs/{id}/restore} endpoint.
 *
 * <p>Mirrors the legacy {@code RestoreEventCRFServlet#mayProceed}:
 * sysadmin OR study director (Data Manager) OR study coordinator
 * (CRC). Investigators / monitors / research assistants are not
 * permitted — restoring a soft-deleted CRF unwinds prior data hygiene
 * work and stays a DM/Admin operation.
 *
 * <p>State guards (parent removed / not currently removed) live on
 * the controller because they need the {@code event_crf} +
 * {@code study_subject} rows, which a role-only matrix doesn't see.
 */
final class EventCrfRestoreAuthorization {

    private EventCrfRestoreAuthorization() {}

    /**
     * @param ub     authenticated user. Sysadmin bypasses the role
     *               matrix entirely (matches legacy
     *               {@code ub.isSysAdmin()} short-circuit).
     * @param roleId legacy {@link Role} id from the session-bound
     *               {@code StudyUserRoleBean}; pass 0 for no role.
     * @return true when the caller's role may restore a soft-deleted
     *         event_crf.
     */
    static boolean roleMayRestore(UserAccountBean ub, int roleId) {
        if (ub != null && ub.isSysAdmin()) return true;
        return roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.ADMIN.getId()
                || roleId == Role.COORDINATOR.getId();
    }
}
