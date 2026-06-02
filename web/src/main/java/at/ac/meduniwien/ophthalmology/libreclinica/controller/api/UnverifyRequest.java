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
 * Phase E A6 — body of {@code POST /pages/api/v1/sdv/unverify}.
 *
 * <p>Mirrors {@code VerifyRequest}'s shape: a list of {@code
 * event_crf_id} strings to flip back from {@code sdv_status = TRUE}
 * to {@code FALSE}. Adds a {@code reason} field — un-verify is
 * GCP-significant (rolls back a Monitor's stamp on source data)
 * and the legacy {@code handleSDVRemove} servlet always captures
 * a free-text reason for the audit trail. Reason is required;
 * empty strings are rejected.
 *
 * @param eventCrfOids list of event_crf ids (as strings) to unverify
 * @param reason       free-text justification, recorded in the
 *                     {@code audit_log_event.action_message} column
 */
@Schema(name = "UnverifyRequest")
public record UnverifyRequest(
        List<String> eventCrfOids,
        String reason
) {}
