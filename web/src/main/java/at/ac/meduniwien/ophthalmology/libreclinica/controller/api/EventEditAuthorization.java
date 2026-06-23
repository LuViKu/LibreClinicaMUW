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
 * <p>Two predicates that since the 2026-06-21 user-feedback batch
 * share the same role set:
 *
 * <ul>
 *   <li>{@link #roleMayEdit(int)} — anyone who writes clinical
 *       data may edit an event's date / location / status
 *       (Investigator, CRC, DM, Admin). Mirrors the legacy
 *       {@code CreateNewStudyEventServlet#mayProceed} which is
 *       also used for edits via the same form.</li>
 *   <li>{@link #roleMayCancel(int)} — same writer set as edit.
 *       Originally restricted to DM/Admin, but the MUW workflow
 *       (small site, paper-first DDE, physician + CRC schedule
 *       and cancel visits routinely) made the DM-only gate read
 *       as a process bug. The cancel still requires a structured
 *       reason code (Wave 1A) so the audit trail captures the
 *       same accountability the DM-escalation was meant to add.</li>
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
