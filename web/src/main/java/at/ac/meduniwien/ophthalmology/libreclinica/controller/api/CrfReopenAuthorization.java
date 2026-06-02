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
 * Phase E A5 — authorization gate for the
 * {@code POST /pages/api/v1/eventCrfs/{id}/markIncomplete} endpoint.
 *
 * <p>Reopening a completed CRF is a GCP-significant operation — the
 * subject's signed-off data becomes editable again, which can
 * invalidate downstream verification + sign-off if not done with
 * care. Mirrors the legacy {@code ResetEventCrfServlet} role check.
 *
 * <p>Permitted roles:
 * <ul>
 *   <li>{@link Role#STUDYDIRECTOR} (Data Manager) — primary owner
 *       of the data-correction workflow.</li>
 *   <li>{@link Role#ADMIN} (System Administrator) — break-glass.</li>
 *   <li>{@link Role#INVESTIGATOR} — may reopen their own subject's
 *       CRFs while sign-off has not yet occurred.</li>
 *   <li>{@link Role#COORDINATOR} (CRC) — same scope as Investigator.</li>
 * </ul>
 *
 * <p>NOT permitted:
 * <ul>
 *   <li>{@link Role#MONITOR} — monitors verify, they do not edit data.</li>
 *   <li>{@link Role#RESEARCHASSISTANT} / {@link Role#RESEARCHASSISTANT2}
 *       — data entry roles, not data-correction roles.</li>
 * </ul>
 *
 * <p>The state guards (locked / signed / already incomplete) are
 * checked separately at the controller layer because they need the
 * {@code event_crf} row, which the role-only matrix doesn't see.
 */
final class CrfReopenAuthorization {

    private CrfReopenAuthorization() {}

    /**
     * @param roleId  legacy {@link Role} id from the session-bound
     *                {@link at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean};
     *                pass 0 for no role (always forbidden).
     * @return true when the caller's role may reopen a completed CRF.
     */
    static boolean roleMayReopen(int roleId) {
        return roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.ADMIN.getId()
                || roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.COORDINATOR.getId();
    }
}
