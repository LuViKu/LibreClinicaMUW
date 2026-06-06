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
 * Phase E.6 crf-entry-advanced — soft-lock heartbeat probe.
 *
 * <p>The CRF entry view polls
 * {@code GET /pages/api/v1/eventCrfs/{id}/lock-status} on mount and
 * {@code POST /pages/api/v1/eventCrfs/{id}/heartbeat} every 30s.
 * Both endpoints return this DTO.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code sameUser == true} → the caller is the only active
 *       editor (or holds the freshest probe).</li>
 *   <li>{@code sameUser == false} → another session is actively
 *       editing; the SPA shows {@link
 *       at.ac.meduniwien.ophthalmology.libreclinica.controller.api}
 *       ConcurrentEditBanner with {@code lastEditorName} +
 *       {@code lastSeenAt}.</li>
 *   <li>{@code lastSeenAt == null} → no active editor was found
 *       (cold probe); the SPA suppresses the banner.</li>
 * </ul>
 *
 * <p>The probe is best-effort + in-memory only (see
 * {@code EventCrfPresenceRegistry}); a server restart clears all
 * presence entries. There is no hard lock — concurrent saves are
 * still permitted at the DB level; this is purely a UX hint.
 *
 * @param eventCrfOid the path-param id echoed back as a string
 * @param sameUser    {@code true} if the caller is the freshest editor
 * @param lastEditorName the username of the freshest editor, or
 *                       {@code null} on a cold probe
 * @param lastSeenAt  ISO-8601 instant of the freshest probe, or
 *                    {@code null} on a cold probe
 * @param ttlSeconds  presence-entry TTL (seconds) so the SPA can
 *                    schedule its heartbeat interval
 */
@Schema(name = "EventCrfLockProbeDto")
public record EventCrfLockProbeDto(
        String eventCrfOid,
        boolean sameUser,
        String lastEditorName,
        String lastSeenAt,
        int ttlSeconds
) {}
