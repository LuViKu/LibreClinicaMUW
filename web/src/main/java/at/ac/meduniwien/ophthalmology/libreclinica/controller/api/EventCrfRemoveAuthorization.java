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
 * Authorization gate for {@code POST /pages/api/v1/eventCrfs/{id}/remove}.
 *
 * <p>Mirrors legacy {@code RemoveEventCRFServlet#mayProceed}: a system
 * administrator, or the study director (Data Manager) or study coordinator
 * in the current study role. Investigators, monitors and data entry persons
 * may not remove a CRF: removing it takes the subject's entered values out
 * of the data. {@link EventCrfRestoreAuthorization} is the gate of the
 * inverse.
 */
final class EventCrfRemoveAuthorization {

    private EventCrfRemoveAuthorization() {}

    /**
     * @param ub     authenticated user; a system administrator always passes
     * @param roleId legacy {@link Role} id of the session's study role; 0 for none
     */
    static boolean roleMayRemove(UserAccountBean ub, int roleId) {
        if (ub != null && ub.isSysAdmin()) return true;
        return roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.COORDINATOR.getId();
    }
}
