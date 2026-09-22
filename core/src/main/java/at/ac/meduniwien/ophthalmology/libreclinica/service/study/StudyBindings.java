/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.study;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P3.5 — which CRF item a study means by a role the shared code knows.
 *
 * <p>Shared controllers carry literal OIDs: {@code F_NAMD_VISIT} is the form
 * the decision panel writes to, {@code I_NAMD_OD_IRF_MM3} is where an
 * inference metric lands, and both are spelled out in code every study runs.
 * A second study doing the same work with its own CRF cannot have it without
 * editing code that every other study shares — which is exactly the shape that
 * makes a third study expensive.
 *
 * <p>So the code asks for a <em>role</em> — "the visit CRF", "where IRF for
 * the right eye goes" — and a study answers with its own OID.
 *
 * <p><strong>The literal stays as the fallback.</strong> Passing it in means
 * this ships without a data migration and without changing behaviour: a study
 * with no row keeps using exactly the OID the code always used. Deleting the
 * literals is the next step, once every study that needs one has a row, and
 * P3.9's guard-rail test is what will force that.
 *
 * <p>Resolution is site → parent → the caller's fallback, so a site inherits
 * its study rather than restating every binding.
 */
public class StudyBindings {

    private static final Logger LOG = LoggerFactory.getLogger(StudyBindings.class);

    /* ------------------------------------------------------------------ */
    /* The roles the shared code asks for                                  */
    /* ------------------------------------------------------------------ */

    /** The form a study's visit-level writes go to. */
    public static final String VISIT_CRF = "visit.crf";

    /** Where an inference metric lands, per eye. */
    public static final String RETINAL_FLUID_IRF_OD = "retinal.fluid.irf.od";
    public static final String RETINAL_FLUID_IRF_OS = "retinal.fluid.irf.os";
    public static final String RETINAL_FLUID_SRF_OD = "retinal.fluid.srf.od";
    public static final String RETINAL_FLUID_SRF_OS = "retinal.fluid.srf.os";
    public static final String RETINAL_FLUID_PED_OD = "retinal.fluid.ped.od";
    public static final String RETINAL_FLUID_PED_OS = "retinal.fluid.ped.os";
    public static final String RETINAL_FLUID_TOTAL_OD = "retinal.fluid.total.od";
    public static final String RETINAL_FLUID_TOTAL_OS = "retinal.fluid.total.os";
    public static final String RETINAL_CRT_OD = "retinal.crt.od";
    public static final String RETINAL_CRT_OS = "retinal.crt.os";

    /** The per-eye clinical flags the decision panel records. */
    public static final String FLAG_HEMORRHAGE_OD = "namd.flags.hemorrhage.od";
    public static final String FLAG_HEMORRHAGE_OS = "namd.flags.hemorrhage.os";
    public static final String FLAG_BCVA_LOSS_OD = "namd.flags.bcvaLoss.od";
    public static final String FLAG_BCVA_LOSS_OS = "namd.flags.bcvaLoss.os";

    private final DataSource dataSource;

    public StudyBindings(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The OID this study means by a role.
     *
     * @param fallback the literal the shared code used before this existed;
     *                 returned when the study has said nothing, so behaviour
     *                 is unchanged until it does
     */
    public String oidFor(int studyId, String bindingKey, String fallback) {
        String stored = stored(studyId, bindingKey);
        return stored != null ? stored : fallback;
    }

    /** Every binding a study has, its parent's included. */
    public Map<String, String> forStudy(int studyId) {
        Map<String, String> out = new LinkedHashMap<>();
        Integer parent = parentOf(studyId);
        // Parent first, so the site's own rows overwrite.
        if (parent != null) out.putAll(rowsFor(parent));
        out.putAll(rowsFor(studyId));
        return out;
    }

    /** Set or clear one binding. Null clears, restoring the code's fallback. */
    public void put(int studyId, String bindingKey, String itemOid, int actorUserId)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            if (itemOid == null || itemOid.isBlank()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM study_item_binding WHERE study_id = ? AND binding_key = ?")) {
                    ps.setInt(1, studyId);
                    ps.setString(2, bindingKey);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO study_item_binding (study_id, binding_key, item_oid, updated_by_user_id) "
                            + "VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (study_id, binding_key) DO UPDATE "
                            + "   SET item_oid = EXCLUDED.item_oid, date_updated = NOW(), "
                            + "       updated_by_user_id = EXCLUDED.updated_by_user_id")) {
                ps.setInt(1, studyId);
                ps.setString(2, bindingKey);
                ps.setString(3, itemOid.trim());
                ps.setInt(4, actorUserId);
                ps.executeUpdate();
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private String stored(int studyId, String bindingKey) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT b.item_oid "
                             + "  FROM study_item_binding b "
                             + " WHERE b.binding_key = ? "
                             + "   AND b.study_id IN (?, COALESCE((SELECT parent_study_id FROM study "
                             + "                                    WHERE study_id = ?), -1)) "
                             + " ORDER BY CASE WHEN b.study_id = ? THEN 0 ELSE 1 END LIMIT 1")) {
            ps.setString(1, bindingKey);
            ps.setInt(2, studyId);
            ps.setInt(3, studyId);
            ps.setInt(4, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            // Falling back to the caller's literal is the safe failure: it is
            // what the code did before this table existed.
            LOG.warn("study_item_binding lookup failed for {}/{}: {}",
                    studyId, bindingKey, e.getMessage());
            return null;
        }
    }

    private Map<String, String> rowsFor(int studyId) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT binding_key, item_oid FROM study_item_binding WHERE study_id = ? "
                             + "ORDER BY binding_key")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            LOG.warn("study_item_binding listing failed for {}: {}", studyId, e.getMessage());
        }
        return out;
    }

    private Integer parentOf(int studyId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT parent_study_id FROM study WHERE study_id = ?")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int p = rs.getInt(1);
                return rs.wasNull() || p == 0 ? null : p;
            }
        } catch (SQLException e) {
            return null;
        }
    }
}
