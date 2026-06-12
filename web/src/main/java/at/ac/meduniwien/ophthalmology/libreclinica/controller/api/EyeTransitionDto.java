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
 * Phase E.6 — per-eye cohort transition history DTO.
 *
 * <p>Each row reflects ONE direction of an eye_cohort_transition row.
 * A single transition writes one row in {@code eye_cohort_transition}
 * but surfaces TWO rows on the GET endpoint — once with
 * {@code side='source'} when fetched against the iAMD subject (the
 * one that downgraded out), once with {@code side='target'} when
 * fetched against the GA subject (the one that received the eye).
 * The SPA's banner renders both directions identically; the
 * {@code side} field lets it pick the verb ("transitioned to" vs
 * "received from").
 *
 * <p>{@code partnerStudyOid} / {@code partnerStudyName} /
 * {@code partnerLabel} point at the OTHER end of the transition — when
 * {@code side='source'} they describe the target study; when
 * {@code side='target'} they describe the source. The SPA renders this
 * as a deep-link target for "jump to the {iAMD|GA} record".
 *
 * <p>{@code transitionedAt} is the ISO-8601 timestamp the transition
 * was committed (UTC); the SPA formats it with the user's locale.
 *
 * <p>{@code reason} is the operator-entered free-text justification
 * captured at transition time. Surfaced verbatim in the banner so
 * sponsors / monitors can read the clinical rationale without leaving
 * the SPA.
 */
@Schema(name = "EyeTransitionDto")
public record EyeTransitionDto(
        int transitionId,
        String eye,
        /**
         * One of {@code "source" | "target"}. {@code "source"} means the
         * row is rendered for the subject that GAVE this eye to the
         * partner (i.e. the iAMD record). {@code "target"} means the
         * row is rendered for the subject that RECEIVED this eye from
         * the partner (i.e. the GA record).
         */
        String side,
        String partnerStudyOid,
        String partnerStudyName,
        String partnerLabel,
        String transitionedAt,
        String reason
) {}
