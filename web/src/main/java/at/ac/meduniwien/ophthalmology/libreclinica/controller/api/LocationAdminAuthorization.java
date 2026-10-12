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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.LocationScope;

/**
 * DR-040 phase 2 — who may manage the uploaders of a location.
 *
 * <p>Two kinds of caller may:
 * <ul>
 *   <li>a <strong>system administrator</strong>, for every location;</li>
 *   <li>a <strong>Standort-Admin</strong>: a user with
 *       {@code user_location.can_manage_sources} at the location. A system
 *       administrator grants the right per person and location
 *       ({@code PUT /api/v1/admin/locations/{id}/users/{username}}). A study
 *       role does not include it: a token lets its holder put scans into a
 *       location's inbox and fetch its worklist, so issuing one is a
 *       deliberate grant.</li>
 * </ul>
 * The right counts at an <em>available</em> location only, as every other
 * location scope does ({@link LocationScope}): a disabled place grants nothing.
 *
 * <h2>The three answers</h2>
 * <ul>
 *   <li><b>401</b> when nobody is signed in;</li>
 *   <li><b>403</b> when the caller may manage no location at all (a
 *       Standort-Admin of nowhere, or an ordinary user);</li>
 *   <li><b>404</b> when the caller may manage <em>some</em> location but names
 *       a resource of another one, or one that does not exist. The controller
 *       does that with {@link Caller#mayManage}: a Standort-Admin gets the
 *       same answer for another place's uploader as for one that is not
 *       there, so the answer is no oracle for what exists elsewhere.</li>
 * </ul>
 *
 * <p>The answer depends on the user and on the database, not on the study
 * bound to the session, and is read per call: a right granted or taken away
 * applies to the next request. A lookup that fails answers "nothing",
 * so the caller is refused.
 */
public final class LocationAdminAuthorization {

    private static final Logger LOG = LoggerFactory.getLogger(LocationAdminAuthorization.class);

    private static final int AVAILABLE = LocationScope.STATUS_AVAILABLE;

    private final DataSource dataSource;

    public LocationAdminAuthorization(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** The signed-in user and what they may manage. */
    public record Caller(UserAccountBean user, boolean sysAdmin, Set<Integer> locationIds) {

        public int userId() {
            return user.getId();
        }

        /** A system administrator may manage any location; a Standort-Admin the ones they hold the right at. */
        public boolean mayManage(Integer locationId) {
            return locationId != null && (sysAdmin || locationIds.contains(locationId));
        }

        /**
         * May the caller manage a source at this location? As {@link #mayManage}, and a system
         * administrator also reaches a source that has no location.
         */
        public boolean mayManageSourceAt(Integer locationId) {
            return sysAdmin || mayManage(locationId);
        }
    }

    /** Either the caller, or the refusal to answer with. Exactly one is set. */
    public record Outcome(Caller caller, ResponseEntity<?> refusal) {}

    /**
     * 401 without a signed-in user, 403 for a user who may manage no
     * location, else the caller.
     */
    public Outcome authorize(HttpSession session) {
        UserAccountBean user = session == null ? null : (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() <= 0) {
            return new Outcome(null, unauthenticated());
        }
        if (user.isSysAdmin()) {
            return new Outcome(new Caller(user, true, Collections.emptySet()), null);
        }
        Set<Integer> managed = managedLocationIds(user.getId());
        if (managed.isEmpty()) {
            return new Outcome(null, ResponseEntity.status(403)
                    .body(Map.of("message", "Standort administrator privilege required.")));
        }
        return new Outcome(new Caller(user, false, managed), null);
    }

    /**
     * Null when the session belongs to a system administrator; 401 with nobody
     * signed in, 403 for anybody else. For the screens that are not delegated
     * to a Standort-Admin.
     */
    static ResponseEntity<?> requireSysadmin(HttpSession session) {
        UserAccountBean user = session == null ? null : (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() <= 0) return unauthenticated();
        if (!user.isSysAdmin()) {
            return ResponseEntity.status(403).body(Map.of("message", "Sysadmin privilege required."));
        }
        return null;
    }

    private static ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("message", "Authentication required."));
    }

    /** The available locations the user holds the Standort-Admin right at. */
    Set<Integer> managedLocationIds(int userId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT ul.location_id FROM user_location ul JOIN location l ON l.location_id = ul.location_id "
                             + "WHERE ul.user_account_id = ? AND ul.can_manage_sources AND l.status_id = ?")) {
            ps.setInt(1, userId);
            ps.setInt(2, AVAILABLE);
            Set<Integer> ids = new LinkedHashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
            return Collections.unmodifiableSet(ids);
        } catch (SQLException | RuntimeException e) {
            LOG.warn("location administrator lookup failed for user id {}: {}", userId,
                    e instanceof SQLException s ? s.getSQLState() : e.getClass().getSimpleName());
            return Collections.emptySet();
        }
    }

    /**
     * The available locations whose uploaders the user may manage, by name:
     * every one for a system administrator, the ones they hold the right at
     * for a Standort-Admin, none for anybody else. {@code GET /me} carries
     * this so the SPA knows whether to offer the uploaders page. A lookup that
     * fails answers none.
     */
    public List<MeDto.ManageableLocationDto> manageableLocations(UserAccountBean user) {
        if (user == null || user.getId() <= 0) return List.of();
        boolean all = user.isSysAdmin();
        String sql = "SELECT l.location_id, l.code, l.name FROM location l WHERE l.status_id = ? "
                + (all ? "" : "AND l.location_id IN (SELECT ul.location_id FROM user_location ul "
                        + "WHERE ul.user_account_id = ? AND ul.can_manage_sources) ")
                + "ORDER BY lower(l.name), l.location_id";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, AVAILABLE);
            if (!all) ps.setInt(2, user.getId());
            List<MeDto.ManageableLocationDto> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MeDto.ManageableLocationDto(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
            return out;
        } catch (SQLException | RuntimeException e) {
            LOG.warn("manageable locations lookup failed for user id {}: {}", user.getId(),
                    e instanceof SQLException s ? s.getSQLState() : e.getClass().getSimpleName());
            return List.of();
        }
    }
}
