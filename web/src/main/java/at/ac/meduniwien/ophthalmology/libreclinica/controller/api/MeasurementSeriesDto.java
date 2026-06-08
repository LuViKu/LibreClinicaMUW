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
 * Phase E.6 study-nurse polish — full measurement series for a
 * (patient, modality, eye) tuple.
 *
 * <p>The SPA renders this on the patient overview page as a sparkline
 * + tabular history. {@code numericValue} is parsed from
 * {@code item_data.value} when {@code dataType="numeric"} and the
 * value parses cleanly; otherwise it's {@code null} so the SPA's
 * sparkline branch can skip the row without re-parsing.
 *
 * <p>Series is chronological by {@code date}.
 */
@Schema(name = "MeasurementSeriesDto")
public record MeasurementSeriesDto(
        String modalityCode,
        String dataType,
        String unit,
        List<Observation> series
) {

    /**
     * Single observation. {@code value} is the raw
     * {@code item_data.value} string (verbatim from the casebook),
     * {@code numericValue} is the parsed decimal when applicable.
     * {@code eventName} carries the {@code study_event_definition.name}
     * so the SPA can render the visit label inline ("Baseline visit",
     * "12-month follow-up").
     */
    @Schema(name = "MeasurementObservationDto")
    public record Observation(
            String date,
            String value,
            Double numericValue,
            String studyOid,
            String studyName,
            int eventCrfId,
            String eventName
    ) {}
}
