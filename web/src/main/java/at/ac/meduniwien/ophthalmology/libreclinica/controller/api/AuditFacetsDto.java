/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The values an audit log's actor and subject filters offer
 * ({@code GET /pages/api/v1/audit/facets}, {@code .../audit/system/facets}):
 * taken from the whole log, since a page holds only some of its rows.
 *
 * @param actors   every actor name in the log's rows, {@code system} for
 *                 rows without a named user
 * @param subjects the labels of the subjects in scope
 */
@Schema(name = "AuditFacetsDto")
public record AuditFacetsDto(
        List<String> actors,
        List<String> subjects
) {}
