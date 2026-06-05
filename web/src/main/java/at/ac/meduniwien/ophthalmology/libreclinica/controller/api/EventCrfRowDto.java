/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase E.6 — one row of {@link EventDetailDto#crfs()}.
 *
 * <p>Each row corresponds to an {@code event_definition_crf} row
 * (one CRF the event definition wires in). When a matching
 * {@code event_crf} row already exists for this study_event, its
 * id is surfaced so the SPA can deep-link straight to the CRF
 * entry view; otherwise {@code eventCrfId} / {@code eventCrfOid}
 * are {@code null} and the SPA renders a "start data entry"
 * affordance.
 *
 * @param eventCrfId            event_crf.event_crf_id when row
 *                              exists; null when the slot is
 *                              unstarted
 * @param eventCrfOid           opaque identifier the SPA uses to
 *                              key the CrfEntry route — currently
 *                              the stringified event_crf id (no
 *                              real OID column in the legacy
 *                              event_crf table)
 * @param crfName               CRF.name from the event_definition_crf
 *                              row
 * @param crfVersionName        CRFVersion.name of the default version
 * @param crfVersionOid         CRFVersion.oid of the default version
 * @param eventDefinitionCrfId  event_definition_crf.event_definition_crf_id
 *                              — useful for "Start data entry" POSTs
 * @param status                CRF state — {@code not-started},
 *                              {@code data-entry-started},
 *                              {@code completed}, {@code stopped},
 *                              {@code signed}
 * @param required              event_definition_crf.required_crf
 * @param passwordRequired      event_definition_crf.electronic_signature
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "EventCrfRowDto")
public record EventCrfRowDto(
        Integer eventCrfId,
        String eventCrfOid,
        String crfName,
        String crfVersionName,
        String crfVersionOid,
        int eventDefinitionCrfId,
        String status,
        boolean required,
        boolean passwordRequired
) {}
