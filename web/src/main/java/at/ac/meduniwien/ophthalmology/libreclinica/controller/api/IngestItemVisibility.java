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
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

/**
 * Which {@code ingest_item} rows a session may see or change.
 *
 * <p>The ingest inbox is a pool: an unbound file belongs to no subject, so the
 * subject's study cannot say who may see it. The rule, in one place for the
 * list queries, the single-item reads and every state change:
 *
 * <ul>
 *   <li><strong>Bound</strong> (names a study subject): visible when that
 *       subject's study is in the session's visible set (SiteVisibilityFilter).</li>
 *   <li><strong>Unbound or dismissed with {@code origin_study_id}</strong>
 *       (uploaded by signed-in staff): visible when the origin study is in the
 *       visible set.</li>
 *   <li><strong>Unbound or dismissed with no origin</strong> (anonymous portal
 *       or device ingress, which only the internal deployment accepts): visible
 *       to every reconciler, the inbox's historical cross-study behaviour.</li>
 * </ul>
 *
 * A system administrator sees everything, as in
 * {@link StudyResourceAccess#guardStudyVisibility}.
 */
public final class IngestItemVisibility {

    private static final Logger LOG = LoggerFactory.getLogger(IngestItemVisibility.class);

    private final DataSource dataSource;
    private final StudyResourceAccess access;

    public IngestItemVisibility(DataSource dataSource, StudyResourceAccess access) {
        this.dataSource = dataSource;
        this.access = access;
    }

    /**
     * A SQL boolean expression over {@code alias} (a row of {@code ingest_item})
     * that is true for the rows the session may see. Study ids are integers
     * from the visibility filter, inlined as literals.
     */
    public String predicate(String alias, HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user != null && user.isSysAdmin()) return "TRUE";
        Set<Integer> visible = access.visibleStudyIds(session);
        String ids = visible.isEmpty() ? "-1"
                : visible.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "((" + alias + ".bound_study_subject_id IS NOT NULL AND EXISTS ("
                + "SELECT 1 FROM study_subject vis_ss WHERE vis_ss.study_subject_id = "
                + alias + ".bound_study_subject_id AND vis_ss.study_id IN (" + ids + "))) "
                + "OR (" + alias + ".bound_study_subject_id IS NULL AND ("
                + alias + ".origin_study_id IS NULL OR " + alias + ".origin_study_id IN (" + ids + "))))";
    }

    /**
     * For taking back an OCT upload job: the job's visit (through its CRF, or
     * the planned visit it is bound to) is in a visible study; or, for a job
     * with no visit yet, its ingest item is visible; or the caller filed it.
     * A job with no visit and no ingest item (legacy, anonymous) is open to
     * every reconciler, like an unbound item with no origin.
     */
    public boolean canSeeJob(long jobId, HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        String ids = "-1";
        boolean sysAdmin = user != null && user.isSysAdmin();
        if (!sysAdmin) {
            Set<Integer> visible = access.visibleStudyIds(session);
            if (!visible.isEmpty()) ids = visible.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        int userId = user == null ? -1 : user.getId();
        String sql = "SELECT 1 FROM retinal_inference_job j WHERE j.job_id = ? AND (" + sysAdmin
                + " OR EXISTS (SELECT 1 FROM event_crf ec JOIN study_subject ss ON ss.study_subject_id = ec.study_subject_id"
                + "             WHERE ec.event_crf_id = j.event_crf_id AND ss.study_id IN (" + ids + "))"
                + " OR (j.event_crf_id IS NULL AND EXISTS (SELECT 1 FROM study_event se"
                + "             JOIN study_subject ss ON ss.study_subject_id = se.study_subject_id"
                + "             WHERE se.study_event_id = j.study_event_id AND ss.study_id IN (" + ids + ")))"
                + " OR (j.event_crf_id IS NULL AND j.study_event_id IS NULL AND j.ingest_item_id IS NOT NULL"
                + "     AND EXISTS (SELECT 1 FROM ingest_item i WHERE i.ingest_item_id = j.ingest_item_id AND "
                + predicate("i", session) + "))"
                + " OR (j.event_crf_id IS NULL AND j.study_event_id IS NULL AND j.ingest_item_id IS NULL)"
                + " OR EXISTS (SELECT 1 FROM ingest_item f WHERE f.ingest_item_id = j.ingest_item_id"
                + "             AND f.bound_by_user_id = " + userId + "))";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("job visibility lookup failed for {}: {}", jobId, e.getMessage());
            return false; // fail closed
        }
    }

    /** {@link #canSee} as a predicate, for the upload service's duplicate checks. */
    public java.util.function.LongPredicate visibleTo(HttpSession session) {
        return id -> canSee(id, session);
    }

    /**
     * For taking back an upload: the item is visible to the session, or the
     * caller is the user who filed it ({@code bound_by_user_id}).
     */
    public boolean canSeeOrIsFiler(long ingestItemId, HttpSession session) {
        if (canSee(ingestItemId, session)) return true;
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() <= 0) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM ingest_item WHERE ingest_item_id = ? AND bound_by_user_id = ?")) {
            ps.setLong(1, ingestItemId);
            ps.setInt(2, user.getId());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("ingest filer lookup failed for {}: {}", ingestItemId, e.getMessage());
            return false;
        }
    }

    /** True when the item exists and the session may see it; false for a missing item too (no existence oracle). */
    public boolean canSee(long ingestItemId, HttpSession session) {
        String sql = "SELECT 1 FROM ingest_item i WHERE i.ingest_item_id = ? AND " + predicate("i", session);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, ingestItemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LOG.warn("ingest visibility lookup failed for {}: {}", ingestItemId, e.getMessage());
            return false; // fail closed
        }
    }
}
