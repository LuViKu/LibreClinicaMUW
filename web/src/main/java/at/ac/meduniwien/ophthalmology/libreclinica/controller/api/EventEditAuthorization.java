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
 * Phase E A4 — role gates for the study-event edit + cancel
 * endpoints.
 *
 * <p>Two predicates because edit + cancel have different
 * permission profiles in the legacy UI:
 *
 * <ul>
 *   <li>{@link #roleMayEdit(int)} — anyone who writes clinical
 *       data may edit an event's date / location / status
 *       (Investigator, CRC, DM, Admin). Mirrors the legacy
 *       {@code CreateNewStudyEventServlet#mayProceed} which is
 *       also used for edits via the same form.</li>
 *   <li>{@link #roleMayCancel(int)} — soft-delete is restricted
 *       to DM / Admin. Investigators who scheduled the visit
 *       cannot cancel it without escalating to a DM. Mirrors the
 *       legacy {@code RemoveStudyEventServlet#mayProceed} which
 *       checks the same role set as Subject remove (A3).</li>
 * </ul>
 *
 * <p>Monitor / RA / RA2 cannot perform either operation
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
        return roleId == Role.ADMIN.getId()
                || roleId == Role.STUDYDIRECTOR.getId();
    }
}
