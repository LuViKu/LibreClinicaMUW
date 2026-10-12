/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * DR-040 — which locations a user works at, and which one a file or a new
 * study gets when nobody says.
 *
 * <p>A location is a global place (a clinic, a department, a partner
 * hospital). Study data stays scoped by study and site, exactly as before;
 * location only scopes what has no study yet, today the unfiled files of the
 * ingest inbox ({@code IngestItemVisibility}).
 *
 * <h2>A user's locations</h2>
 *
 * The union of
 * <ul>
 *   <li>the location of every study or site on which the user holds a live
 *       role (an AVAILABLE {@code study_user_role}; the study not removed);</li>
 *   <li>for a role on a parent study, the locations of all its sites too, so
 *       a coordinator of a multicenter study reaches every centre's devices;</li>
 *   <li>the explicit {@code user_location} rows, for staff without a role on
 *       a site there (technicians, photographers).</li>
 * </ul>
 * A system administrator has every location: {@link Visible#all()}, never a
 * list. Only AVAILABLE locations count; a removed one grants nothing, and what
 * it holds waits for an administrator.
 *
 * <p>The answer depends on the user, not on the study bound to the session
 * (unlike {@link SiteVisibilityFilter}): a user with a role on site A and on
 * site B at two places has both. It is computed per call from three small
 * lookups, as the visible-study set is, not cached in the session: a role
 * granted or an assignment added applies to the next request.
 *
 * <h2>The default location</h2>
 *
 * {@link #defaultLocationId()} depends on the deployment's {@link LocationMode},
 * never on how many locations exist. In single mode it is the one AVAILABLE
 * location: everything is at it, and a study without a location of its own
 * reads as being there, so a study created by a path that does not know about
 * locations (a data fix, an import) cannot take its users' files away. In
 * multi mode it is always null: what has no place of its own belongs to none
 * and waits for an administrator.
 */
@Component
public class LocationScope {

    private static final Logger LOG = LoggerFactory.getLogger(LocationScope.class);

    /** {@code location.status_id} / {@code study.status_id} as the rest of the schema has them. */
    public static final int STATUS_AVAILABLE = 1;
    public static final int STATUS_REMOVED = 5;
    public static final int STATUS_AUTO_REMOVED = 7;

    private final DataSource dataSource;

    @Autowired
    public LocationScope(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /* ------------------------------------------------------------------ */
    /* The locations a user may see                                        */
    /* ------------------------------------------------------------------ */

    /**
     * What the user may see of the study-independent data of each location.
     * Nobody (no user, an unsaved user, a lookup that failed) sees nothing:
     * the answer fails closed.
     */
    public Visible visibleLocations(UserAccountBean user) {
        if (user == null) return Visible.none();
        if (user.isSysAdmin()) return Visible.all();
        if (user.getId() <= 0 || user.getName() == null || user.getName().isBlank()) return Visible.none();
        try (Connection c = dataSource.getConnection()) {
            return visibleLocations(c, user.getId(), user.getName());
        } catch (SQLException | RuntimeException e) {
            LOG.warn("location scope lookup failed for user {}: {}", user.getName(), e.getMessage());
            return Visible.none();
        }
    }

    /**
     * The locations of a user who is not a system administrator.
     *
     * <p>One statement: the studies the user has a role on, plus the sites
     * under those, plus the explicit assignments, each narrowed to AVAILABLE
     * locations. A study with no location of its own counts for the default
     * location (see the class comment).
     */
    public static Visible visibleLocations(Connection c, int userId, String userName) throws SQLException {
        String sql = "SELECT DISTINCT l.location_id FROM location l WHERE l.status_id = ? AND ("
                + " l.location_id IN ("
                + "   SELECT COALESCE(s.location_id, CAST(? AS INTEGER)) FROM study s"
                + "    WHERE COALESCE(s.status_id, ?) NOT IN (?, ?)"
                + "      AND (s.study_id IN (SELECT r.study_id FROM study_user_role r"
                + "                           WHERE r.user_name = ? AND r.status_id = ?)"
                + "        OR s.parent_study_id IN (SELECT r.study_id FROM study_user_role r"
                + "                                  WHERE r.user_name = ? AND r.status_id = ?)))"
                + " OR l.location_id IN (SELECT ul.location_id FROM user_location ul"
                + "                       WHERE ul.user_account_id = ?))";
        Integer defaultId = defaultLocationId(c);
        Set<Integer> ids = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, STATUS_AVAILABLE);
            if (defaultId == null) ps.setNull(i++, Types.INTEGER); else ps.setInt(i++, defaultId);
            ps.setInt(i++, STATUS_AVAILABLE);
            ps.setInt(i++, STATUS_REMOVED);
            ps.setInt(i++, STATUS_AUTO_REMOVED);
            ps.setString(i++, userName);
            ps.setInt(i++, STATUS_AVAILABLE);
            ps.setString(i++, userName);
            ps.setInt(i++, STATUS_AVAILABLE);
            ps.setInt(i, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return Visible.of(ids);
    }

    /* ------------------------------------------------------------------ */
    /* Defaults                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Where something without a place of its own belongs: in single mode the
     * one AVAILABLE location; in multi mode, and when there is none, null. Null
     * is the right answer for "which place does this device belong to" on a
     * multi-location deployment: a file nobody has assigned waits for an
     * administrator.
     */
    public Integer defaultLocationId() {
        try (Connection c = dataSource.getConnection()) {
            return defaultLocationId(c);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("default location lookup failed: {}", e.getMessage());
            return null;
        }
    }

    public static Integer defaultLocationId(Connection c) throws SQLException {
        if (LocationMode.current() == LocationMode.MULTI) return null;
        // Single mode has one active location (the startup refuses a database with
        // more); several would still read as none rather than as an arbitrary one.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT location_id FROM location WHERE status_id = ? ORDER BY location_id LIMIT 2")) {
            ps.setInt(1, STATUS_AVAILABLE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int first = rs.getInt(1);
                return rs.next() ? null : first;
            }
        }
    }

    /** A location's row, as the forms and the facility address of a site need it. */
    public record Place(int id, String code, String name, String street, String city, String zip, String country,
                        int statusId) {
        public boolean available() {
            return statusId == STATUS_AVAILABLE;
        }
    }

    private static final String PLACE_COLUMNS =
            "location_id, code, name, street, city, zip, country, status_id";

    private static Place placeOf(ResultSet rs) throws SQLException {
        return new Place(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getInt(8));
    }

    /** The location with this id, removed or not; null when there is none. */
    public static Place place(Connection c, int locationId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + PLACE_COLUMNS + " FROM location WHERE location_id = ?")) {
            ps.setInt(1, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? placeOf(rs) : null;
            }
        }
    }

    /** Every location, removed ones included, by id: for labelling many rows with one read. */
    public static java.util.Map<Integer, Place> places(Connection c) throws SQLException {
        java.util.Map<Integer, Place> out = new java.util.HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + PLACE_COLUMNS + " FROM location");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Place p = placeOf(rs);
                out.put(p.id(), p);
            }
        }
        return out;
    }

    /** True when the location exists and is AVAILABLE. */
    public boolean isAvailable(int locationId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM location WHERE location_id = ? AND status_id = ?")) {
            ps.setInt(1, locationId);
            ps.setInt(2, STATUS_AVAILABLE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException | RuntimeException e) {
            LOG.warn("location lookup failed for {}: {}", locationId, e.getMessage());
            return false;
        }
    }

    /** How many AVAILABLE locations there are; 0 when the lookup fails. */
    public int availableCount() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM location WHERE status_id = ?")) {
            ps.setInt(1, STATUS_AVAILABLE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException | RuntimeException e) {
            LOG.warn("location count failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * The location the study or site is at, or null when it has none (or does
     * not exist). For a parent study with sites this is its coordinating
     * location, which may be null.
     */
    public Integer locationOfStudy(int studyId) {
        try (Connection c = dataSource.getConnection()) {
            return locationOfStudy(c, studyId);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("location lookup failed for study {}: {}", studyId, e.getMessage());
            return null;
        }
    }

    public static Integer locationOfStudy(Connection c, int studyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT location_id FROM study WHERE study_id = ?")) {
            ps.setInt(1, studyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int id = rs.getInt(1);
                return rs.wasNull() ? null : id;
            }
        }
    }

    /**
     * The studies and sites that are at a location: every {@code study} row that
     * is not removed and sits at {@code locationId}. The device endpoints use it
     * to answer a request that carries an uploader token only for the visits of
     * that token's place (DR-040 phase 2); intersected with the scope the
     * deployment configured, it is the whole of what such a request may reach.
     *
     * <p>A study with no location of its own reads as being at the default
     * location, the same rule {@link #visibleLocations} applies, so a study
     * created by a path that does not know about locations does not drop out of
     * its only clinic's lookups. In multi mode there is no default and such a
     * study is at none.
     *
     * <p>Both the ids of sites and of the studies without sites are returned;
     * a parent study that has sites is in the set only when its coordinating
     * location is {@code locationId}. A subject is enrolled at exactly one of
     * these rows ({@code study_subject.study_id}), which is what the lookups
     * compare against.
     */
    public static Set<Integer> studyIdsAtLocation(Connection c, int locationId) throws SQLException {
        Integer defaultId = defaultLocationId(c);
        String sql = "SELECT s.study_id FROM study s"
                + " WHERE COALESCE(s.status_id, ?) NOT IN (?, ?)"
                + "   AND COALESCE(s.location_id, CAST(? AS INTEGER)) = ?"
                + " ORDER BY s.study_id";
        Set<Integer> ids = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setInt(i++, STATUS_AVAILABLE);
            ps.setInt(i++, STATUS_REMOVED);
            ps.setInt(i++, STATUS_AUTO_REMOVED);
            if (defaultId == null) ps.setNull(i++, Types.INTEGER); else ps.setInt(i++, defaultId);
            ps.setInt(i, locationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    /**
     * The origin location of a file a signed-in user uploads while working in
     * the study: that study's location, else the default one, else null (an
     * administrator places it).
     */
    public Integer originForStaffUpload(Integer sessionStudyId) {
        try (Connection c = dataSource.getConnection()) {
            return originForStaffUpload(c, sessionStudyId);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("origin location lookup failed for study {}: {}", sessionStudyId, e.getMessage());
            return null;
        }
    }

    public static Integer originForStaffUpload(Connection c, Integer sessionStudyId) throws SQLException {
        if (sessionStudyId != null && sessionStudyId > 0) {
            Integer own = locationOfStudy(c, sessionStudyId);
            if (own != null) return own;
        }
        return defaultLocationId(c);
    }

    /* ------------------------------------------------------------------ */
    /* The answer                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Every location (a system administrator), or the listed ones. Immutable.
     */
    public static final class Visible {

        private static final Visible ALL = new Visible(true, Set.of());
        private static final Visible NONE = new Visible(false, Set.of());

        private final boolean all;
        private final Set<Integer> ids;

        private Visible(boolean all, Set<Integer> ids) {
            this.all = all;
            this.ids = ids;
        }

        public static Visible all() {
            return ALL;
        }

        public static Visible none() {
            return NONE;
        }

        public static Visible of(Set<Integer> ids) {
            return ids == null || ids.isEmpty() ? NONE : new Visible(false, Collections.unmodifiableSet(new LinkedHashSet<>(ids)));
        }

        public boolean isAll() {
            return all;
        }

        /** The listed locations; empty for "every location" and for none. */
        public Set<Integer> ids() {
            return ids;
        }

        /** True for a listed location, and for any location (a missing one included) when all are visible. */
        public boolean contains(Integer locationId) {
            if (all) return true;
            return locationId != null && ids.contains(locationId);
        }

        /**
         * A SQL boolean over {@code column}: {@code TRUE} for every location,
         * {@code FALSE} for none, else {@code column IN (...)}. The ids are
         * integers out of the database, inlined as literals; a NULL column is
         * never in the list.
         */
        public String sql(String column) {
            if (all) return "TRUE";
            if (ids.isEmpty()) return "FALSE";
            return column + " IN (" + ids.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
        }

        @Override
        public String toString() {
            return all ? "all locations" : "locations " + ids;
        }
    }
}
