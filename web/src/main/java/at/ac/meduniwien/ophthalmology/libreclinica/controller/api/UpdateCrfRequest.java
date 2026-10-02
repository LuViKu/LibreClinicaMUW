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
 * Body of {@code PUT /api/v1/crfs/{crfOid}}: a CRF's name and description.
 * Legacy parity: {@code UpdateCRFServlet} (name required, at most 255
 * characters and unique among CRFs; description at most 2048).
 */
@Schema(name = "UpdateCrfRequest")
public record UpdateCrfRequest(
        String name,
        String description
) {}
