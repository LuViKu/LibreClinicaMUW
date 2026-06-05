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
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase E.6 Data Export — DAO for the
 * {@code dataset_filter_map} association table. Each row links a
 * persisted {@code dataset.dataset_id} to a {@code filter.filter_id}
 * with an {@code ordinal} that fixes the order filter predicates are
 * concatenated into the legacy WHERE-clause builder.
 *
 * <p>This DAO sits next to {@link FilterDAO} (which manages the
 * {@code filter} row itself); a single {@link FilterBean} carries the
 * SQL fragment, the human-readable description, and the audit
 * columns, and {@code dataset_filter_map} carries the link + ordering
 * back to the dataset that uses it. The Phase 3 wire shape
 * (one {@code DatasetFilterDto} per row in the create-dataset payload)
 * persists in two steps:
 *
 * <ol>
 *   <li>Per-DTO {@link FilterDAO#create} → returns a new
 *       {@code filter_id}.</li>
 *   <li>{@link #insertLink} writes the
 *       {@code (dataset_id, filter_id, ordinal)} tuple here.</li>
 * </ol>
 *
 * <p>Reads are oriented around {@link #findFilterIdsByDataset} —
 * Phase 3 only needs to enumerate the predicates attached to a
 * dataset, in their authoring order, so the {@code :test-filter}
 * count query and the wizard's preview pane stay in sync.
 *
 * <p>Implementation note: the legacy code never grew a
 * {@code DatasetFilterMapDAO} — the
 * {@code dataset_filter_map} table was written exclusively by
 * inline {@code INSERT}s in {@code CreateDatasetServlet}. This is the
 * first centralized accessor; new code paths should reuse it.
 */
public class DatasetFilterMapDAO {

    private static final Logger LOG = LoggerFactory.getLogger(DatasetFilterMapDAO.class);

    private static final String SQL_INSERT_LINK =
            "INSERT INTO dataset_filter_map (dataset_id, filter_id, ordinal) VALUES (?, ?, ?)";

    private static final String SQL_DELETE_LINKS_FOR_DATASET =
            "DELETE FROM dataset_filter_map WHERE dataset_id = ?";

    private static final String SQL_SELECT_BY_DATASET =
            "SELECT filter_id, ordinal FROM dataset_filter_map WHERE dataset_id = ? ORDER BY ordinal ASC";

    private final DataSource ds;

    public DatasetFilterMapDAO(DataSource ds) {
        this.ds = ds;
    }

    /**
     * Insert a single {@code (dataset_id, filter_id, ordinal)} row.
     * Throws a {@link RuntimeException} wrapper around
     * {@link SQLException} so calling code can use it inside a
     * stream / iteration without per-row try/catch.
     */
    public void insertLink(int datasetId, int filterId, int ordinal) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT_LINK)) {
            ps.setInt(1, datasetId);
            ps.setInt(2, filterId);
            ps.setInt(3, ordinal);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to INSERT dataset_filter_map link " +
                    "(datasetId={}, filterId={}, ordinal={})", datasetId, filterId, ordinal, e);
            throw new RuntimeException("dataset_filter_map INSERT failed", e);
        }
    }

    /**
     * Remove every {@code dataset_filter_map} row for a dataset. Used
     * when the operator edits a dataset and resubmits the filter list
     * — we drop + re-insert rather than diff against the existing
     * rows (the table is small and the audit signal sits on
     * {@code filter} updates, not link updates).
     */
    public void deleteLinksForDataset(int datasetId) {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE_LINKS_FOR_DATASET)) {
            ps.setInt(1, datasetId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to DELETE dataset_filter_map rows for datasetId={}", datasetId, e);
            throw new RuntimeException("dataset_filter_map DELETE failed", e);
        }
    }

    /**
     * Returns the filter_ids attached to a dataset, ordered by
     * {@code ordinal} ASC (i.e. authoring order).
     */
    public List<Integer> findFilterIdsByDataset(int datasetId) {
        List<Integer> out = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SELECT_BY_DATASET)) {
            ps.setInt(1, datasetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
        } catch (SQLException e) {
            LOG.warn("Failed to SELECT dataset_filter_map for datasetId={}", datasetId, e);
            throw new RuntimeException("dataset_filter_map SELECT failed", e);
        }
        return out;
    }
}
