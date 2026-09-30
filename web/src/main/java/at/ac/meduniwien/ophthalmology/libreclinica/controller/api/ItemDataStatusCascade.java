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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A CRF's values following the CRF when it is removed or restored:
 * auto-removed with it, available again with it.
 *
 * <p>Only the status and the updater change. {@code ItemDataDAO.update}
 * rewrites the whole row and clears the provenance columns
 * ({@code source_kind}, {@code source_retinal_job_id},
 * {@code source_ingest_item_id}; see its query in {@code itemdata_dao.xml}),
 * because it is the path a person takes to change a value. A removal or a
 * restore changes no value, so a value the platform wrote has to say so
 * afterwards as well. The value is not rewritten, so the {@code item_data}
 * trigger records nothing, as it records nothing for these status changes
 * in legacy either.
 */
final class ItemDataStatusCascade {

    private ItemDataStatusCascade() {}

    /**
     * Marks the CRF's values auto-removed, except those removed on their
     * own, as legacy {@code RemoveEventCRFServlet} does.
     *
     * @return the ids of the values marked
     */
    static List<Integer> autoRemove(Connection c, int eventCrfId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE item_data SET status_id = 7, update_id = ?, date_updated = now() "
                        + "WHERE event_crf_id = ? AND status_id IS DISTINCT FROM 5 "
                        + "RETURNING item_data_id")) {
            ps.setInt(1, userId);
            ps.setInt(2, eventCrfId);
            List<Integer> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
            return ids;
        }
    }

    /**
     * Makes the CRF's auto-removed values available again. A value removed
     * on its own stays removed.
     *
     * @return the number of values restored
     */
    static int restore(Connection c, int eventCrfId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE item_data SET status_id = 1, update_id = ?, date_updated = now() "
                        + "WHERE event_crf_id = ? AND status_id = 7")) {
            ps.setInt(1, userId);
            ps.setInt(2, eventCrfId);
            return ps.executeUpdate();
        }
    }
}
