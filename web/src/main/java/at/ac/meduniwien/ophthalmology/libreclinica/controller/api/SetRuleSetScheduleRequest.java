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
 * Phase E RX.7 — PUT {@code /api/v1/rule-sets/{id}/schedule} request body.
 *
 * <p>Surfaces the existing {@code rule_set.run_schedule} +
 * {@code rule_set.run_time} columns to the SPA. These are read by the
 * Quartz {@code RuleSetBulkRuleRunner} on its scheduled tick — turning
 * {@code runSchedule} on (with a {@code runTime} the runner can match
 * against the current hour) enrols the rule_set into the nightly
 * batch.
 *
 * <p>Field contract:
 * <ul>
 *   <li>{@code runSchedule} — required. When {@code true} the rule_set
 *       runs on the Quartz schedule.</li>
 *   <li>{@code runTime} — required only when {@code runSchedule} is
 *       {@code true}. Format {@code HH:mm} (24-hour, 00:00–23:59).
 *       Ignored / accepted as-is when {@code runSchedule} is
 *       {@code false} (the column is read solely under the
 *       {@code runSchedule == true} branch in the runner).</li>
 * </ul>
 */
@Schema(name = "SetRuleSetScheduleRequest")
public record SetRuleSetScheduleRequest(
        Boolean runSchedule,
        String runTime
) {}
