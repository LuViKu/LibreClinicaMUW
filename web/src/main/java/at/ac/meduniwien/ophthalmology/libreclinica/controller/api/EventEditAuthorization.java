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
 * Role gates for the study-event edit + cancel endpoints.
 *
 * <p>Both predicates share the same writer role set:
 *
 * <ul>
 *   <li>{@link #roleMayEdit(int)} — anyone who writes clinical
 *       data may edit an event's date / location / status
 *       (Investigator, CRC, DM, Admin). Mirrors the legacy
 *       {@code CreateNewStudyEventServlet#mayProceed} which is
 *       also used for edits via the same form.</li>
 *   <li>{@link #roleMayCancel(int)} — same writer set as edit;
 *       widened from the original DM/Admin-only gate for the MUW
 *       workflow. The cancel still requires a structured reason
 *       code so the audit trail preserves accountability.</li>
 * </ul>
 *
 * <p>Monitor / RA / RA2 still cannot perform either operation
 * (Monitor verifies, RA enters; neither corrects).
 */
final class EventEditAuthorization {

    private EventEditAuthorization() {}

    static boolean roleMayEdit(int roleId) {
        return roleId == Role.ADMIN.getId()
                || roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.COORDINATOR.getId();
    }

    static boolean roleMayCancel(int roleId) {
        return roleMayEdit(roleId);
    }
}
