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
 * Phase E A6 — role gate for the
 * {@code POST /pages/api/v1/sdv/unverify} endpoint.
 *
 * <p>Un-verification rolls back a previously-stamped Monitor SDV.
 * In legacy {@code handleSDVRemove} the role guard was DM /
 * Monitor / Admin only — Investigators and CRC roles cannot undo
 * a Monitor's verification stamp without escalating to a DM (per
 * GCP separation of duties).
 *
 * <p>Permitted:
 * <ul>
 *   <li>{@link Role#MONITOR} — the role that originally stamped
 *       the SDV; may also un-stamp before passing to DM.</li>
 *   <li>{@link Role#STUDYDIRECTOR} (Data Manager) — break-glass
 *       on Monitor's behalf.</li>
 *   <li>{@link Role#ADMIN}.</li>
 * </ul>
 *
 * <p>Forbidden: Investigator, CRC (Coordinator), RA, RA2. They
 * would need to log a discrepancy note instead.
 */
final class SdvUnverifyAuthorization {

    private SdvUnverifyAuthorization() {}

    static boolean roleMayUnverify(int roleId) {
        return roleId == Role.MONITOR.getId()
                || roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.ADMIN.getId();
    }
}
