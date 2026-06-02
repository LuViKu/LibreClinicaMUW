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
 * Phase E RX.5 — response for POST {@code /api/v1/rules}.
 */
@Schema(name = "CreateRuleResponse")
public record CreateRuleResponse(
        int id,
        String oid,
        String name,
        String description,
        String expression
) {}
