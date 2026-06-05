/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.extract;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>JDBC DAO for the {@code export_schedule} table. Pairs with
 * {@link ExportJobDAO} — when a schedule fires the registrar inserts
 * a fresh export_job row + stamps the schedule's
 * {@code last_run_at} / {@code last_run_job_id}, then the runner
 * picks it up like a manual trigger.
 */
public class ExportScheduleDAO {

    private static final Logger LOG = LoggerFactory.getLogger(ExportScheduleDAO.class);

    private final DataSource dataSource;

    public ExportScheduleDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public long create(int datasetId, String format, String cronExpression,
                       int createdByUserId, Instant nextRunAt) {
        String sql = "INSERT INTO export_schedule "
                + "(dataset_id, format, cron_expression, active, created_by, next_run_at) "
                + "VALUES (?, ?, ?, TRUE, ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, datasetId);
            ps.setString(2, format);
            ps.setString(3, cronExpression);
            ps.setInt(4, createdByUserId);
            if (nextRunAt == null) ps.setNull(5, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(5, Timestamp.from(nextRunAt));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.error("create schedule failed for dataset_id={} cron={}: {}",
                    datasetId, cronExpression, e.getMessage(), e);
        }
        return -1L;
    }

    /** Soft-delete: flip active to false. Idempotent. */
    public boolean deactivate(long scheduleId) {
        String sql = "UPDATE export_schedule SET active = FALSE WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, scheduleId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.error("deactivate failed for schedule_id={}: {}", scheduleId, e.getMessage(), e);
            return false;
        }
    }

    public Row findById(long scheduleId) {
        String sql = "SELECT id, dataset_id, format, cron_expression, active, "
                + "created_by, created_at, next_run_at, last_run_at, last_run_job_id "
                + "FROM export_schedule WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, scheduleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRow(rs);
            }
        } catch (SQLException e) {
            LOG.error("findById failed for schedule_id={}: {}", scheduleId, e.getMessage(), e);
        }
        return null;
    }

    public List<Row> findByDataset(int datasetId, boolean includeInactive) {
        String sql = "SELECT id, dataset_id, format, cron_expression, active, "
                + "created_by, created_at, next_run_at, last_run_at, last_run_job_id "
                + "FROM export_schedule WHERE dataset_id = ? "
                + (includeInactive ? "" : "AND active = TRUE ")
                + "ORDER BY active DESC, created_at DESC";
        List<Row> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, datasetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRow(rs));
            }
        } catch (SQLException e) {
            LOG.error("findByDataset failed for dataset_id={}: {}", datasetId, e.getMessage(), e);
        }
        return out;
    }

    /** All active schedules across the system — used by the Quartz registrar at boot. */
    public List<Row> findAllActive() {
        String sql = "SELECT id, dataset_id, format, cron_expression, active, "
                + "created_by, created_at, next_run_at, last_run_at, last_run_job_id "
                + "FROM export_schedule WHERE active = TRUE";
        List<Row> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapRow(rs));
        } catch (SQLException e) {
            LOG.error("findAllActive failed: {}", e.getMessage(), e);
        }
        return out;
    }

    /**
     * Stamp last_run_at / next_run_at / last_run_job_id after the
     * registrar fires a tick. {@code lastRunJobId} may be {@code -1}
     * if the queued-row insert failed; we still stamp last_run_at so
     * the next pass picks up correctly.
     */
    public void stampRun(long scheduleId, long lastRunJobId, Instant lastRunAt, Instant nextRunAt) {
        String sql = "UPDATE export_schedule SET last_run_at = ?, next_run_at = ?, "
                + "last_run_job_id = ? WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(lastRunAt));
            if (nextRunAt == null) ps.setNull(2, java.sql.Types.TIMESTAMP);
            else ps.setTimestamp(2, Timestamp.from(nextRunAt));
            if (lastRunJobId <= 0) ps.setNull(3, java.sql.Types.BIGINT);
            else ps.setLong(3, lastRunJobId);
            ps.setLong(4, scheduleId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("stampRun failed for schedule_id={}: {}", scheduleId, e.getMessage(), e);
        }
    }

    /* --------------------------------------------------------------- */

    private static Row mapRow(ResultSet rs) throws SQLException {
        Row r = new Row();
        r.id = rs.getLong("id");
        r.datasetId = rs.getInt("dataset_id");
        r.format = rs.getString("format");
        r.cronExpression = rs.getString("cron_expression");
        r.active = rs.getBoolean("active");
        r.createdBy = rs.getInt("created_by");
        r.createdAt = toInstant(rs.getTimestamp("created_at"));
        r.nextRunAt = toInstant(rs.getTimestamp("next_run_at"));
        r.lastRunAt = toInstant(rs.getTimestamp("last_run_at"));
        long lastJob = rs.getLong("last_run_job_id");
        r.lastRunJobId = rs.wasNull() ? null : lastJob;
        return r;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public static final class Row {
        public long id;
        public int datasetId;
        public String format;
        public String cronExpression;
        public boolean active;
        public int createdBy;
        public Instant createdAt;
        public Instant nextRunAt;
        public Instant lastRunAt;
        public Long lastRunJobId;
    }
}
