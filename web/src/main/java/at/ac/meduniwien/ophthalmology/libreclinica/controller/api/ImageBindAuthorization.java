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
 * DR-025 — authorization gate for the fundus-image reconciliation inbox
 * ({@code ImageIngestApiController}: bind / dismiss an UNBOUND image_ingest row).
 *
 * <p>Reconciliation is a data-management task — linking an inbound image to the
 * correct subject/visit. Permitted roles mirror {@link CrfReopenAuthorization}:
 * <ul>
 *   <li>{@link Role#STUDYDIRECTOR} (Data Manager) — primary owner.</li>
 *   <li>{@link Role#INVESTIGATOR} — reconciles their own subjects' images.</li>
 *   <li>{@link Role#COORDINATOR} (CRC) — same data-handling scope.</li>
 *   <li>{@link Role#ADMIN} (System Administrator) — break-glass.</li>
 * </ul>
 * Monitors (verify-only) and pure data-entry assistant roles are NOT permitted.
 * Site/study visibility on the chosen bind target is enforced separately at the
 * controller layer.
 */
final class ImageBindAuthorization {

    private ImageBindAuthorization() {}

    /** @param roleId legacy {@link Role} id from the session {@code userRole}; 0 = none. */
    static boolean roleMayReconcile(int roleId) {
        return roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.COORDINATOR.getId()
                || roleId == Role.ADMIN.getId();
    }
}
