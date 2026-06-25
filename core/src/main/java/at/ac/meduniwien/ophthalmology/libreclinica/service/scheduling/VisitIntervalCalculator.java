/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.scheduling;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * nAMD treat-and-extend (2026-06-19) — apply a per-visit "scheduled
 * interval" to a freshly-created {@code study_event} row.
 *
 * <p>The legacy {@code StudyEventDAO.create()} writes the row with
 * the columns it has always written ({@code date_start},
 * {@code date_end}, etc.); this service runs a follow-up UPDATE to
 * populate the two new nullable columns added in changeset
 * {@code lc-muw-2026-06-19-event-scheduled-interval.xml}:
 *
 * <ul>
 *   <li>{@code scheduled_interval_days} — verbatim from the caller
 *       (the physician's "extend next visit by N weeks" decision at
 *       the end of the prior visit). NULL on non-T-and-E studies.</li>
 *   <li>{@code scheduled_for} — derived as
 *       {@code dateStarted + intervalDays}; only meaningful for the
 *       NEXT visit that the auto-scheduler is queuing, and the
 *       coordinator may override before that visit happens.</li>
 * </ul>
 *
 * <p>Kept as its own service so the legacy DAO + XML SQL stay
 * untouched and the new columns have a single ownership point.
 */
@Service
public class VisitIntervalCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(VisitIntervalCalculator.class);

    private final DataSource dataSource;

    public VisitIntervalCalculator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Persist the interval + derived scheduled_for date on
     * {@code study_event}. No-op when both inputs are null so the
     * existing (non-treat-and-extend) endpoint callers don't pay a
     * round trip.
     *
     * @param studyEventId  PK of the row to update.
     * @param dateStarted   the visit's start date — the anchor that
     *                      {@code scheduled_for} is computed from when
     *                      {@code intervalDays} is non-null.
     * @param intervalDays  interval in days from the prior visit;
     *                      null when the protocol doesn't track it.
     * @return the computed {@code scheduled_for} date, or null when
     *         the inputs left it unset.
     */
    public LocalDate applyInterval(int studyEventId, Date dateStarted, Integer intervalDays) {
        if (intervalDays == null) {
            return null;
        }
        if (intervalDays < 0) {
            throw new IllegalArgumentException(
                    "scheduledIntervalDays must be >= 0; got " + intervalDays);
        }
        if (dateStarted == null) {
            throw new IllegalArgumentException(
                    "dateStarted is required when scheduledIntervalDays is set");
        }

        // java.sql.Date and java.util.Date both flow in here (the
        // legacy events controller parses ISO_DATE to a util.Date; the
        // IT seeds via Date.valueOf which returns a sql.Date that
        // refuses toInstant). Go via getTime() → Instant which both
        // support uniformly.
        LocalDate startLocal = Instant.ofEpochMilli(dateStarted.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        LocalDate scheduledFor = startLocal.plusDays(intervalDays);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE study_event "
                             + "   SET scheduled_for = ?, "
                             + "       scheduled_interval_days = ? "
                             + " WHERE study_event_id = ?")) {
            ps.setObject(1, scheduledFor);
            ps.setInt(2, intervalDays);
            ps.setInt(3, studyEventId);
            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException(
                        "Expected to update exactly 1 study_event row (id="
                                + studyEventId + "); UPDATE affected " + updated);
            }
        } catch (SQLException sqlEx) {
            LOG.error("Failed to apply interval to study_event id={}: {}",
                    studyEventId, sqlEx.getMessage());
            throw new IllegalStateException(
                    "Failed to persist visit interval: " + sqlEx.getMessage(), sqlEx);
        }

        LOG.info("Applied scheduled_interval_days={} (scheduled_for={}) to study_event id={}",
                intervalDays, scheduledFor, studyEventId);
        return scheduledFor;
    }
}
