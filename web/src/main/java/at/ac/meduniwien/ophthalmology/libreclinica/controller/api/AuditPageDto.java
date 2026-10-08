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
 * One page of an audit log ({@code GET /pages/api/v1/audit},
 * {@code GET /pages/api/v1/audit/system}): the rows that match the
 * filters, newest first, and how many match in all.
 *
 * <p>Mirrors {@code AuditPage} in {@code web/src/spa/src/types/audit.ts}.
 *
 * @param totalCount rows matching the filters, across every page
 * @param page       0-based page number of {@code events}
 * @param pageSize   rows per page
 * @param events     the page's rows, newest first
 */
@Schema(name = "AuditPageDto")
public record AuditPageDto(
        long totalCount,
        int page,
        int pageSize,
        List<AuditEventDto> events
) {}
