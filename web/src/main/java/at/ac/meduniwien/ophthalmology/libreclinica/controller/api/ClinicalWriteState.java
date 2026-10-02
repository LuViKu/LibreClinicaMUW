/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata.EventCrfWriteRules;

import org.springframework.http.ResponseEntity;

/**
 * The state an event CRF must be in for the SPA's clinical writes to change
 * it; the other half of {@link ClinicalWriteAuthorization}, which says who
 * may.
 *
 * <p>An event CRF whose own row, visit, subject or study is removed, locked
 * or signed takes no writes: not its item values or repeating rows, not its
 * files, and neither completion nor reopening
 * ({@link EventCrfWriteRules#refusal}). Legacy keeps removed data out of
 * data entry by hiding it, which left a removed CRF writable through any
 * path that did not hide it. Restoring a removed CRF is the way back, and
 * stays open.
 *
 * <p>A refusal is a 409 carrying the reason and comes before anything is
 * written.
 */
final class ClinicalWriteState {

    private ClinicalWriteState() {}

    /**
     * A 409 when the event CRF's values may not change, else {@code null}.
     *
     * @param operation what the caller was about to do, for the message
     */
    static ResponseEntity<?> refuseUnlessWritable(DataSource dataSource, int eventCrfId, String operation) {
        EventCrfWriteRules.Refusal refusal;
        try (Connection c = dataSource.getConnection()) {
            refusal = EventCrfWriteRules.refusal(c, eventCrfId);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the state of event_crf " + eventCrfId, e);
        }
        if (refusal == null) {
            return null;
        }
        return ResponseEntity.status(409).body(Map.of(
                "message", "event_crf " + eventCrfId + " " + refusal.reason() + "; " + operation + " is refused.",
                "code", "EVENT_CRF_" + refusal.name()));
    }
}
