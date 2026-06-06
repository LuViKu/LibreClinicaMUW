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
 * Phase E.6 crf-entry-advanced — per-section TOC roll-up.
 *
 * <p>Returned (as a list) by
 * {@code GET /pages/api/v1/eventCrfs/{id}/section-status}. Drives the
 * SideRail's {@code SectionBadge} component: each section row shows
 * {@code filledCount/requiredCount}, an error icon when
 * {@code errorCount > 0}, and an amber query badge when
 * {@code openQueries > 0}.
 *
 * <p>The {@code sectionOid} matches {@code CrfSectionDto.oid} on
 * {@link CrfEntryDto} so the SPA can join the two without a separate
 * lookup. The {@code title} is denormalised for tooltips so the SPA
 * doesn't need to re-walk the CRF schema for hover text.
 *
 * @param sectionOid    matches {@code CrfSectionDto.oid}
 * @param title         section title (denormalised for tooltips)
 * @param requiredCount items in the section that have
 *                      {@code item_form_metadata.required = true}
 * @param filledCount   subset of {@code requiredCount} with a
 *                      non-blank persisted value
 * @param errorCount    items whose persisted value fails server-side
 *                      validation (typed range / numeric coercion).
 *                      The SPA still runs its own client-side
 *                      validation; this is the server's view.
 * @param openQueries   discrepancy notes attached to items in this
 *                      section whose {@code resolution_status} is
 *                      {@code new} / {@code updated} /
 *                      {@code resolution-proposed}.
 */
@Schema(name = "SectionStatusDto")
public record SectionStatusDto(
        String sectionOid,
        String title,
        int requiredCount,
        int filledCount,
        int errorCount,
        int openQueries
) {}
