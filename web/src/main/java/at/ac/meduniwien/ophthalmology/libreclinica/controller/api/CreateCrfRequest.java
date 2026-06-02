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
 * Phase E A8.3 — POST {@code /api/v1/crfs} request body.
 *
 * <p>Creates the parent CRF "shell" — a {@code crf} row with name +
 * description but no versions yet. Versions are added separately via
 * the multipart upload endpoint
 * ({@code POST /api/v1/crfs/{crfOid}/versions}).
 *
 * <p>Required: {@code name} (unique across the CRF library, ≤255).
 * Optional: {@code description} (≤2000). The OID is server-generated
 * via {@code CrfOidGenerator} as {@code F_<UPPERCASE_NAME_12>}.
 */
@Schema(name = "CreateCrfRequest")
public record CreateCrfRequest(
        String name,
        String description
) {}
