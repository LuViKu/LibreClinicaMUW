/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.Set;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResolutionStatus;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;

/**
 * Phase E A1 — role-gated state machine for discrepancy-note status
 * transitions. Mirrors the rules encoded in
 * {@link at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ResolveDiscrepancyServlet}
 * + {@code CreateOneDiscrepancyNoteServlet}, lifted out of the JSP
 * layer so the {@code DiscrepancyApiController} can enforce them
 * verbatim and the SPA-side helpers in {@code note.ts} can mirror
 * the same matrix for client-side button visibility.
 *
 * <p>SPA-side status strings ({@code 'new' | 'updated' |
 * 'resolution-proposed' | 'closed' | 'not-applicable'}) map 1:1 to
 * {@link ResolutionStatus} ids 1–5. The legacy
 * {@code ResolutionStatus.RESOLVED} constant (id 3) is named
 * "Resolution_Proposed" in the DB; the SPA exposes it under that
 * label.
 *
 * <p>Authoritative role × transition table:
 * <pre>
 *   Current → Next                       Permitted roles
 *   -----------------------------------------------------------------
 *   new      → updated                   Investigator, CRC (coord),
 *                                        Data Manager (director),
 *                                        Administrator
 *   new      → not-applicable            Data Manager, Administrator
 *   updated  → updated                   any USER role on the study
 *   updated  → resolution-proposed       Investigator, CRC
 *   updated  → not-applicable            Data Manager, Administrator
 *   resolved → closed                    Monitor, Data Manager,
 *                                        Administrator
 *   resolved → updated  (reopen)         Monitor, Data Manager,
 *                                        Administrator
 *   closed   → (terminal — only legacy
 *               admin path can reopen)   —
 * </pre>
 *
 * Where "resolved" above is shorthand for the {@code
 * 'resolution-proposed'} SPA status (id 3).
 *
 * <p>The matrix is consulted before any DB write — the controller
 * throws {@code IllegalStateException} (handled by
 * {@link ApiExceptionHandler} → 400) for an invalid current/next
 * pair, and a custom {@code ForbiddenTransitionException} (mapped
 * to 403) for a permitted pair the caller's role can't perform.
 */
final class NoteTransitionMatrix {

    private NoteTransitionMatrix() {}

    /**
     * Result of a transition check: {@link #OK} when the caller may
     * proceed, otherwise an error describing whether the transition
     * itself is illegal or whether the caller's role is the
     * blocker.
     */
    enum Decision {
        OK,
        ILLEGAL_TRANSITION,
        FORBIDDEN_FOR_ROLE
    }

    /**
     * Check whether a caller bearing {@code roleId} can transition a
     * note from {@code currentStatusId} to {@code newStatusId}. Both
     * arguments are {@link ResolutionStatus} ids (1..5).
     *
     * @param currentStatusId current note status id (1..5)
     * @param newStatusId     intended new status id (1..5)
     * @param roleId          legacy {@link Role} id (1..7); pass 0 for
     *                        no role (treated as forbidden).
     */
    static Decision check(int currentStatusId, int newStatusId, int roleId) {
        if (currentStatusId == newStatusId) {
            // same-status replies — only allowed for the 'updated'
            // self-transition (additional reply in a thread).
            if (currentStatusId == ResolutionStatus.UPDATED.getId()) {
                return anyUserRole(roleId) ? Decision.OK : Decision.FORBIDDEN_FOR_ROLE;
            }
            return Decision.ILLEGAL_TRANSITION;
        }
        if (newStatusId == ResolutionStatus.OPEN.getId()) {
            // back to 'new' is never permitted via this endpoint
            return Decision.ILLEGAL_TRANSITION;
        }

        // OPEN ('new') -> ...
        if (currentStatusId == ResolutionStatus.OPEN.getId()) {
            if (newStatusId == ResolutionStatus.UPDATED.getId()) {
                return rolesInvestigatorCrcDmAdmin(roleId)
                        ? Decision.OK : Decision.FORBIDDEN_FOR_ROLE;
            }
            if (newStatusId == ResolutionStatus.NOT_APPLICABLE.getId()) {
                return rolesDmAdmin(roleId)
                        ? Decision.OK : Decision.FORBIDDEN_FOR_ROLE;
            }
            return Decision.ILLEGAL_TRANSITION;
        }

        // UPDATED -> ...
        if (currentStatusId == ResolutionStatus.UPDATED.getId()) {
            if (newStatusId == ResolutionStatus.RESOLVED.getId()) {
                return rolesInvestigatorCrc(roleId)
                        ? Decision.OK : Decision.FORBIDDEN_FOR_ROLE;
            }
            if (newStatusId == ResolutionStatus.NOT_APPLICABLE.getId()) {
                return rolesDmAdmin(roleId)
                        ? Decision.OK : Decision.FORBIDDEN_FOR_ROLE;
            }
            return Decision.ILLEGAL_TRANSITION;
        }

        // RESOLVED ('resolution-proposed') -> ...
        if (currentStatusId == ResolutionStatus.RESOLVED.getId()) {
            if (newStatusId == ResolutionStatus.CLOSED.getId()
                    || newStatusId == ResolutionStatus.UPDATED.getId()) {
                return rolesMonitorDmAdmin(roleId)
                        ? Decision.OK : Decision.FORBIDDEN_FOR_ROLE;
            }
            return Decision.ILLEGAL_TRANSITION;
        }

        // CLOSED / NOT_APPLICABLE are terminal for this endpoint.
        return Decision.ILLEGAL_TRANSITION;
    }

    /** Map a SPA-side status string to the legacy {@link ResolutionStatus} id. */
    static int statusIdForSpaName(String name) {
        if (name == null) return 0;
        return switch (name) {
            case "new" -> ResolutionStatus.OPEN.getId();
            case "updated" -> ResolutionStatus.UPDATED.getId();
            case "resolution-proposed" -> ResolutionStatus.RESOLVED.getId();
            case "closed" -> ResolutionStatus.CLOSED.getId();
            case "not-applicable" -> ResolutionStatus.NOT_APPLICABLE.getId();
            default -> 0;
        };
    }

    /* --------------------------------------------------------------- */
    /* Role predicates — keep verbatim with the legacy servlet checks. */
    /* --------------------------------------------------------------- */

    private static boolean rolesInvestigatorCrcDmAdmin(int roleId) {
        return roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.COORDINATOR.getId()
                || roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.ADMIN.getId();
    }

    private static boolean rolesInvestigatorCrc(int roleId) {
        return roleId == Role.INVESTIGATOR.getId()
                || roleId == Role.COORDINATOR.getId();
    }

    private static boolean rolesDmAdmin(int roleId) {
        return roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.ADMIN.getId();
    }

    private static boolean rolesMonitorDmAdmin(int roleId) {
        return roleId == Role.MONITOR.getId()
                || roleId == Role.STUDYDIRECTOR.getId()
                || roleId == Role.ADMIN.getId();
    }

    private static boolean anyUserRole(int roleId) {
        // Any legacy role with a non-zero id and a non-INVALID
        // identity. ra / ra2 are deliberately excluded — they
        // shouldn't participate in note state changes per the
        // legacy permission model.
        return Set.of(Role.ADMIN.getId(),
                      Role.COORDINATOR.getId(),
                      Role.STUDYDIRECTOR.getId(),
                      Role.INVESTIGATOR.getId(),
                      Role.MONITOR.getId())
                .contains(roleId);
    }
}
