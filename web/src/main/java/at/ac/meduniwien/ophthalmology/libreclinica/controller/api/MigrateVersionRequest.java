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
import java.util.List;

/**
 * Phase E.6 {@code crf-library} cluster — body of
 * {@code POST /api/v1/crfs/{crfOid}/versions/{from}/migrate-to/{to}}.
 *
 * <p>Re-points every selected {@code event_definition_crf} row that
 * currently names {@code from} as its default to point at {@code to}
 * instead. The two versions must belong to the same CRF.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code sedOids} — restrict migration to event definitions with
 *       these OIDs. When {@code null} or empty, every event definition
 *       that currently defaults to {@code from} is migrated. The
 *       SPA's batch dialog renders the full candidate list and ships
 *       the operator's selection here.</li>
 *   <li>{@code dryRun} — when {@code true}, the server computes the
 *       migration plan and returns the per-SED rows without writing.
 *       The SPA uses this in the dialog's preview pane. When
 *       {@code false}, the same plan is applied + an audit row is
 *       written per affected SED.</li>
 * </ul>
 *
 * <p>The wire shape carries OIDs (not numeric IDs) so the SPA never
 * needs to leak DB-internal keys.
 */
@Schema(name = "CrfMigrateVersionRequest",
        description = "Batch reassign event_definition_crf default_version_id.")
public record MigrateVersionRequest(
        List<String> sedOids,
        boolean dryRun
) {}
