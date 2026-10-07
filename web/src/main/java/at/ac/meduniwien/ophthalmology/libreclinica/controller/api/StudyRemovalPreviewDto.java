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
 * What removing a study would take with it: returned by
 * {@code GET /api/v1/studies/{oid}/removal-preview} so the SPA can name it
 * in the confirmation before {@code POST /studies/{oid}/disable}, as the
 * legacy {@code RemoveStudyServlet} confirmation page lists the sites,
 * users, subjects and event definitions.
 *
 * <p>Each count is the number of live rows the removal would auto-remove.
 * Rows already removed on their own are not counted, because the removal
 * leaves them as they are.
 */
@Schema(name = "StudyRemovalPreviewDto")
public record StudyRemovalPreviewDto(
        String oid,
        String name,
        /** Names of the live sites, sorted. */
        List<String> siteNames,
        int roleBindings,
        int subjects,
        int groupClasses,
        int eventDefinitions,
        int events,
        int eventCrfs,
        int itemData,
        int datasets
) {}
