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
 * Phase E A8.2 — POST
 * {@code /api/v1/studies/{studyOid}/event-definitions/reorder}
 * request body.
 *
 * <p>Carries the new ordering as an explicit ordered list of OIDs.
 * The controller sets each definition's {@code ordinal} to its
 * 1-based position in the array.
 *
 * <p>The list must contain exactly the OIDs of the study's currently-
 * active (non-DELETED) event definitions — no additions, no removals,
 * no duplicates. Disable / restore are owned by their dedicated
 * endpoints; this surface is reorder-only.
 */
@Schema(name = "ReorderEventDefinitionsRequest")
public record ReorderEventDefinitionsRequest(List<String> orderedOids) {}
