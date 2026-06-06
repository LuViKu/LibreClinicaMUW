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
 * Phase E.6 {@code crf-library} cluster — response of
 * {@code POST /api/v1/crfs/{crfOid}/versions/{from}/migrate-to/{to}}.
 *
 * <p>Returned for both {@code dryRun=true} and {@code dryRun=false}
 * calls; the SPA can preview a migration by inspecting {@code perSed}
 * and {@code totalMigrated} and then re-issue with {@code dryRun=false}
 * once the operator confirms.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code fromVersionOid} / {@code toVersionOid} — echo of the
 *       request path so the SPA can correlate result vs. UI state when
 *       multiple migrate calls fly in flight.</li>
 *   <li>{@code dryRun} — echo of the request body so the SPA can branch
 *       commit-vs-preview UI on the response alone.</li>
 *   <li>{@code totalMigrated} — number of {@code event_definition_crf}
 *       rows that were (or would be) updated. On dryRun this is the
 *       planned count; on commit this is the actually-written count.</li>
 *   <li>{@code perSed} — one {@link SedMigrationRow} per event
 *       definition the request touched. {@code migrated=false} means
 *       the SED was named in {@code sedOids} but did not actually
 *       default to {@code fromVersion} — left untouched.</li>
 * </ul>
 */
@Schema(name = "CrfMigrateVersionResult",
        description = "Result of an event_definition_crf default version migration.")
public record MigrateVersionResult(
        String crfOid,
        String fromVersionOid,
        String toVersionOid,
        boolean dryRun,
        int totalMigrated,
        List<SedMigrationRow> perSed
) {

    /**
     * One row per event definition the migration touched. Populated for
     * both dry-run and commit; {@code migrated=true} means the
     * {@code default_version_id} was (or would be) re-pointed.
     */
    @Schema(name = "CrfMigrateVersionSedRow")
    public record SedMigrationRow(
            String studyOid,
            String sedOid,
            String sedName,
            boolean migrated,
            String reasonSkipped
    ) {}
}
