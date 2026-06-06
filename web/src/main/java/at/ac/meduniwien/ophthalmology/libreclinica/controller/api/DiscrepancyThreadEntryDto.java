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
 * Phase E.6 {@code discrepancy-full} — single thread-entry projection
 * returned inside {@link DiscrepancyNoteDto#thread()}.
 *
 * <p>One row per child note ({@code parent_dn_id != 0}) plus the
 * parent itself as the first entry. The SPA renders these in
 * insertion order to draw the legacy "thread panel" affordance from
 * the JSP-era {@code resolveDiscrepancy.jsp}.
 *
 * @param id            child discrepancy_note id as a string
 * @param status        one of: {@code new | updated |
 *                      resolution-proposed | closed | not-applicable}
 * @param description   free-text body
 * @param author        username of the note owner, or empty string
 *                      when the owner has been deleted
 * @param createdAt     ISO-8601 of {@code date_created}
 */
@Schema(name = "DiscrepancyThreadEntryDto")
public record DiscrepancyThreadEntryDto(
        String id,
        String status,
        String description,
        String author,
        String createdAt
) {}
