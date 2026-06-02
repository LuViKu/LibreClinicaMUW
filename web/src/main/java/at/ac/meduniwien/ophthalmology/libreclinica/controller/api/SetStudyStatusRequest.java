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
 * Phase E A8.5 — POST {@code /api/v1/studies/{studyOid}/status}
 * request body.
 *
 * <p>{@code targetStatus} must be one of:
 * {@code AVAILABLE} / {@code PENDING} / {@code LOCKED} /
 * {@code FROZEN}. {@code DELETED} is reached via A8.1's
 * {@code /disable} endpoint (kept separate so the disable / restore
 * pair stays distinct from the operational LOCKED / FROZEN
 * transitions).
 *
 * <p>{@code reason} is REQUIRED for the GCP-sensitive transitions
 * {@code AVAILABLE → LOCKED} and {@code AVAILABLE → FROZEN} — the
 * change goes into the audit log alongside the status flip. For
 * other transitions the reason is optional.
 */
@Schema(name = "SetStudyStatusRequest")
public record SetStudyStatusRequest(
        String targetStatus,
        String reason
) {}
