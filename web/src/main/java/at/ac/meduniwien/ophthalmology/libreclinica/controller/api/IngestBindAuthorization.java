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
 * DR-025 / P3.2 — authorization gate for the ingest reconciliation inbox
 * ({@code IngestInboxApiController}: bind / unbind / dismiss an ingest_item row).
 *
 * <p>Reconciliation is a data-management task — saying which patient's visit an
 * inbound file belongs to.
 *
 * <p>P3.2 — this now gates OCT volumes as well as fundus photographs. The
 * alternative was the retinal queue's rule, which was sysadmin-only; that is
 * the wrong direction, because the inbox already enforces a role AND per-target
 * site visibility, and requiring a sysadmin to file a study nurse's scan is how
 * a queue silts up. Permitted roles mirror {@link CrfReopenAuthorization}:
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
public final class IngestBindAuthorization {

    private IngestBindAuthorization() {}

    /** @param roleId legacy {@link Role} id from the session {@code userRole}; 0 = none. */
    public static boolean roleMayReconcile(int roleId) {
        return roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.COORDINATOR.getId()
                || roleId == Role.ADMIN.getId();
    }
}
