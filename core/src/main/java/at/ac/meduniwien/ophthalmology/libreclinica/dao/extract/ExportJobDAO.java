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
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase E.6 — Data Export Phase 4.
 *
 * <p>Thin JDBC DAO over the {@code export_job} table. Kept JDBC-only
 * (no Hibernate mapping) because:
 *
 * <ul>
 *   <li>The runner needs {@code SELECT … FOR UPDATE SKIP LOCKED} to
 *       be multi-instance safe — Hibernate's pessimistic lock + that
 *       clause is awkward and Postgres-specific, so a hand-rolled
 *       prepared statement is clearer.</li>
 *   <li>The shape is intentionally tiny + denormalised; we don't need
 *       any of the EntityBean machinery (audit fields, status enums,
 *       bean lifecycle hooks) the legacy DAOs carry.</li>
 * </ul>
 *
 * <p>Connection lifecycle: every method opens a fresh
 * {@link Connection} from the injected {@link DataSource} and closes
 * it in a try-with-resources. Caller-managed transactions are not
 * needed — each query is its own atomic statement.
 */
public class ExportJobDAO {

    private static final Logger LOG = LoggerFactory.getLogger(ExportJobDAO.class);

    /** Terminal states the SPA stops polling on. */
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    private final DataSource dataSource;

    public ExportJobDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Insert a fresh {@code queued} row. Returns the generated id, or
     * {@code -1} if the insert failed (logged + caller can map to a
     * 500 response).
     */
    public long insertQueued(int datasetId, String format, int submittedByUserId) {
        String sql = "INSERT INTO export_job "
                + "(dataset_id, format, status, submitted_by) "
                + "VALUES (?, ?, '" + STATUS_QUEUED + "', ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, datasetId);
            ps.setString(2, format);
            ps.setInt(3, submittedByUserId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.error("insertQueued failed for dataset_id={} format={}: {}",
                    datasetId, format, e.getMessage(), e);
        }
        return -1L;
    }

    /**
     * Multi-instance-safe pickup. Atomically claims the oldest queued
     * row and flips it to {@code running}. Uses
     * {@code SELECT … FOR UPDATE SKIP LOCKED} so two ExportJobRunner
     * ticks (even on different app instances against the same DB)
     * never grab the same row.
     *
     * <p>Returns the claimed row, or {@code null} if the queue was
     * empty. Callers may receive a row whose {@code dataset_id}
     * dangles (concurrent delete) — that surfaces during
     * {@code runExport} and gets recorded as {@code failed}.
     */
    public Row claimNextQueued() {
        String select = "SELECT id, dataset_id, format, submitted_by "
                + "FROM export_job WHERE status = '" + STATUS_QUEUED + "' "
                + "ORDER BY submitted_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED";
        String update = "UPDATE export_job SET status = '" + STATUS_RUNNING + "', "
                + "started_at = now() WHERE id = ?";
        try (Connection c = dataSource.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Row picked = null;
                try (PreparedStatement ps = c.prepareStatement(select);
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        picked = new Row();
                        picked.id = rs.getLong(1);
                        picked.datasetId = rs.getInt(2);
                        picked.format = rs.getString(3);
                        picked.submittedBy = rs.getInt(4);
                        picked.status = STATUS_RUNNING;
                    }
                }
                if (picked == null) {
                    c.commit();
                    return null;
                }
                try (PreparedStatement ps = c.prepareStatement(update)) {
                    ps.setLong(1, picked.id);
                    ps.executeUpdate();
                }
                c.commit();
                return picked;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAuto);
            }
        } catch (SQLException e) {
            LOG.error("claimNextQueued failed: {}", e.getMessage(), e);
            return null;
        }
    }

    public void markDone(long jobId, int archivedDatasetFileId) {
        String sql = "UPDATE export_job SET status = '" + STATUS_DONE + "', "
                + "finished_at = now(), archived_dataset_file_id = ?, error_message = NULL "
                + "WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, archivedDatasetFileId);
            ps.setLong(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("markDone failed for job_id={}: {}", jobId, e.getMessage(), e);
        }
    }

    public void markFailed(long jobId, String errorMessage) {
        String sql = "UPDATE export_job SET status = '" + STATUS_FAILED + "', "
                + "finished_at = now(), error_message = ? WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, truncate(errorMessage));
            ps.setLong(2, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.error("markFailed failed for job_id={}: {}", jobId, e.getMessage(), e);
        }
    }

    /** Find by id. Returns null if not found. */
    public Row findById(long jobId) {
        String sql = "SELECT id, dataset_id, format, status, submitted_by, "
                + "submitted_at, started_at, finished_at, "
                + "archived_dataset_file_id, error_message "
                + "FROM export_job WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return fullRow(rs);
            }
        } catch (SQLException e) {
            LOG.error("findById failed for job_id={}: {}", jobId, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Paged + filtered list. {@code requestedByUserId == null} drops
     * the user filter (used by the sysadmin path). {@code status} /
     * {@code format} are optional substring-exact equals; null = no
     * filter on that column.
     *
     * <p>Result rows are ordered by {@code submitted_at DESC} so the
     * SPA's table renders newest-first.
     */
    public List<Row> findFiltered(Integer requestedByUserId, String status, String format,
                                  int page, int pageSize) {
        StringBuilder sql = new StringBuilder("SELECT id, dataset_id, format, status, submitted_by, ")
                .append("submitted_at, started_at, finished_at, ")
                .append("archived_dataset_file_id, error_message ")
                .append("FROM export_job WHERE 1=1 ");
        List<Object> params = new ArrayList<>(4);
        if (requestedByUserId != null) {
            sql.append("AND submitted_by = ? ");
            params.add(requestedByUserId);
        }
        if (status != null) {
            sql.append("AND status = ? ");
            params.add(status);
        }
        if (format != null) {
            sql.append("AND format = ? ");
            params.add(format);
        }
        sql.append("ORDER BY submitted_at DESC LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((long) page * (long) pageSize);

        List<Row> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindAll(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(fullRow(rs));
            }
        } catch (SQLException e) {
            LOG.error("findFiltered failed: {}", e.getMessage(), e);
        }
        return out;
    }

    /** Companion {@code COUNT(*)} for {@link #findFiltered}. */
    public long countFiltered(Integer requestedByUserId, String status, String format) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM export_job WHERE 1=1 ");
        List<Object> params = new ArrayList<>(3);
        if (requestedByUserId != null) {
            sql.append("AND submitted_by = ? ");
            params.add(requestedByUserId);
        }
        if (status != null) {
            sql.append("AND status = ? ");
            params.add(status);
        }
        if (format != null) {
            sql.append("AND format = ? ");
            params.add(format);
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindAll(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOG.error("countFiltered failed: {}", e.getMessage(), e);
        }
        return 0L;
    }

    private static void bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Integer ip) ps.setInt(i + 1, ip);
            else if (p instanceof Long lp) ps.setLong(i + 1, lp);
            else if (p instanceof String sp) ps.setString(i + 1, sp);
            else ps.setObject(i + 1, p);
        }
    }

    /**
     * Recent jobs for a study, ordered by submitted_at DESC. Joins
     * {@code dataset} on {@code study_id} so the SPA's
     * {@code /studies/{oid}/export-jobs} view scopes naturally. Hard
     * caps at 50 rows.
     */
    public List<Row> findRecentByStudy(int studyId) {
        String sql = "SELECT ej.id, ej.dataset_id, ej.format, ej.status, ej.submitted_by, "
                + "ej.submitted_at, ej.started_at, ej.finished_at, "
                + "ej.archived_dataset_file_id, ej.error_message "
                + "FROM export_job ej "
                + "JOIN dataset d ON d.dataset_id = ej.dataset_id "
                + "WHERE d.study_id = ? "
                + "ORDER BY ej.submitted_at DESC LIMIT 50";
        List<Row> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(fullRow(rs));
            }
        } catch (SQLException e) {
            LOG.error("findRecentByStudy failed for study_id={}: {}", studyId, e.getMessage(), e);
        }
        return out;
    }

    /* --------------------------------------------------------------- */
    /* Helpers                                                         */
    /* --------------------------------------------------------------- */

    private static Row fullRow(ResultSet rs) throws SQLException {
        Row r = new Row();
        r.id = rs.getLong("id");
        r.datasetId = rs.getInt("dataset_id");
        r.format = rs.getString("format");
        r.status = rs.getString("status");
        r.submittedBy = rs.getInt("submitted_by");
        r.submittedAt = toInstant(rs.getTimestamp("submitted_at"));
        r.startedAt = toInstant(rs.getTimestamp("started_at"));
        r.finishedAt = toInstant(rs.getTimestamp("finished_at"));
        int adfId = rs.getInt("archived_dataset_file_id");
        r.archivedDatasetFileId = rs.wasNull() ? null : adfId;
        r.errorMessage = rs.getString("error_message");
        return r;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String truncate(String s) {
        if (s == null) return null;
        // TEXT column has no hard cap but we don't want runaway stack
        // dumps in the DB — clamp at 8 KiB.
        return s.length() > 8192 ? s.substring(0, 8192) : s;
    }

    /** Plain data holder — kept package-public so the runner + controller can read it. */
    public static final class Row {
        public long id;
        public int datasetId;
        public String format;
        public String status;
        public int submittedBy;
        public Instant submittedAt;
        public Instant startedAt;
        public Instant finishedAt;
        public Integer archivedDatasetFileId;
        public String errorMessage;
    }

    /** Suppress the unused-import warning Eclipse-format flags for Types. */
    @SuppressWarnings("unused")
    private static final int UNUSED_TYPES_REF = Types.INTEGER;
}
