/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E A1 — body of {@code POST /pages/api/v1/discrepancies/{parentId}/thread}.
 *
 * <p>Appends a child note (legacy {@code parent_dn_id != 0}) to an
 * existing parent and transitions the parent's status. Mirrors the
 * legacy {@link
 * at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ResolveDiscrepancyServlet}
 * + {@code CreateOneDiscrepancyNoteServlet} pair which together
 * encoded the OpenClinica discrepancy-note state-machine in JSP.
 *
 * @param newStatus  required. One of the SPA-side status strings:
 *                   {@code 'updated' | 'resolution-proposed' |
 *                   'closed' | 'not-applicable'}. {@code 'new'} is
 *                   reserved for the initial parent insert and is
 *                   rejected here.
 * @param description free-text response / explanation. Required for
 *                   every transition EXCEPT {@code 'closed'} (closure
 *                   may be wordless per legacy convention — Monitor
 *                   just stamps the close).
 * @param assignedTo optional reassignment: user_name of the next
 *                   assignee. {@code null} preserves the parent's
 *                   current assignee.
 */
@Schema(name = "AddThreadEntryRequest")
public record AddThreadEntryRequest(
        String newStatus,
        String description,
        String assignedTo
) {}
