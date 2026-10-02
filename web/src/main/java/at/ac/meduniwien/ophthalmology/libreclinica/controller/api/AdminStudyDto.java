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
 * One study, with its sites nested under it, in the system
 * administrator's list of every study: {@code GET /api/v1/admin/studies}.
 * The columns of the legacy {@code /ListStudy} page ("Administer
 * Studies").
 *
 * <p>{@code status} is a stable key, not the localised term:
 * {@code AVAILABLE}, {@code PENDING}, {@code FROZEN}, {@code LOCKED},
 * {@code REMOVED} (removed on purpose), {@code AUTO_REMOVED} (removed
 * with its parent), and the rarely used {@code UNAVAILABLE},
 * {@code PRIVATE} and {@code UNKNOWN}.
 */
@Schema(name = "AdminStudyDto")
public record AdminStudyDto(
        String oid,
        String name,
        /** {@code study.unique_identifier}, the unique protocol id. */
        String uniqueIdentifier,
        String principalInvestigator,
        /** ISO {@code yyyy-MM-dd}; null when unset. */
        String createdDate,
        String status,
        /** Null for a top-level study. */
        String parentOid,
        /** The study's sites, by name; always empty for a site. */
        List<AdminStudyDto> sites
) {}
