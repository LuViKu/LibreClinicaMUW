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
 * Phase E A2 — role gate for
 * {@code PUT /api/v1/subjects/{oid}} (demographics edit).
 *
 * <p>The legacy {@code UpdateSubjectServlet#mayProceed} short-circuits
 * to "sysadmin-only", but the spirit of the operation (correcting
 * subject identifying data) is performed routinely by Investigator
 * and CRC during data entry — the JSP UI exposes the edit form
 * via {@code AdministrativeEditingServlet} which gates on
 * {@code currentRole.getRole().getId() <= INVESTIGATOR}. We mirror
 * that broader set here because the create endpoint
 * ({@code POST /subjects}) already grants the same roles permission
 * to write subject identifying fields.
 *
 * <p>Permitted:
 * <ul>
 *   <li>{@link Role#ADMIN}</li>
 *   <li>{@link Role#STUDYDIRECTOR} (Data Manager)</li>
 *   <li>{@link Role#INVESTIGATOR}</li>
 *   <li>{@link Role#COORDINATOR} (CRC)</li>
 * </ul>
 *
 * <p>Forbidden: Monitor (read-only verification role), RA/RA2.
 */
final class SubjectEditAuthorization {

    private SubjectEditAuthorization() {}

    static boolean roleMayEdit(int roleId) {
        return roleId == Role.ADMIN.getId()
                || roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.COORDINATOR.getId();
    }
}
