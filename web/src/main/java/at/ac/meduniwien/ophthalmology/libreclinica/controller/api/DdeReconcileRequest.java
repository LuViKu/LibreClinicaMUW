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
 * Phase E.6 dde — body of
 * {@code POST /api/v1/eventCrfs/{id}/dde-conflicts/{itemOid}/resolve}.
 *
 * <p>DM/Admin picks the canonical value for one conflicting item.
 * The {@code reasonForChange} is mandatory per GCP — it lands in the
 * audit_event packed actionMessage and (optionally) spawns a
 * REASON_FOR_CHANGE note (the existing E.6 audit / RFC plumbing).
 *
 * @param winner          "ide" | "dde" | "manual"
 * @param value           required when {@code winner=="manual"};
 *                        ignored (server uses the IDE/DDE value)
 *                        otherwise
 * @param reasonForChange short justification (required)
 */
@Schema(name = "DdeReconcileRequest")
public record DdeReconcileRequest(
        String winner,
        String value,
        String reasonForChange
) {}
