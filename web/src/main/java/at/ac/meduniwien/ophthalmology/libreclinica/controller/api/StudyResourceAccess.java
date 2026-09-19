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
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

/**
 * P3.0 — "are you logged in, and may you see this study's things?"
 *
 * <p>The retinal controller and the image inbox each answered that themselves.
 * Both are about to be rebuilt — the retinal one splits into four controllers
 * (P3.6), the inbox becomes the unified one (P3.2) — and four copies of an
 * access check is how a surface ends up unguarded.
 *
 * <p>The plan called this {@code RetinalJobAccess}. The name widened because
 * nothing in it is retinal: the same two questions guard ingested images, and
 * will guard the unified inbox.
 *
 * <p><strong>The two copies were not identical, and the difference is kept.</strong>
 * The retinal surface relaxes visibility for a deep link — a job in a study
 * that is not the active one is reachable when the user holds a live role on
 * that study, because a link to a job lands wherever the user's session happens
 * to be pointing. The image inbox does not relax, deliberately: {@code
 * b6feaa974} hardened its preview endpoint against exactly that, and an
 * integration test pins it. Folding one into the other would have widened
 * access to patient photographs as a side effect of tidying code, so the
 * relaxation is a separate method whose name says what it does.
 */
public final class StudyResourceAccess {

    private static final Logger LOG = LoggerFactory.getLogger(StudyResourceAccess.class);

    private final DataSource dataSource;
    private final SiteVisibilityFilter siteVisibilityFilter;

    public StudyResourceAccess(DataSource dataSource, SiteVisibilityFilter siteVisibilityFilter) {
        this.dataSource = dataSource;
        this.siteVisibilityFilter = siteVisibilityFilter;
    }

    /**
     * 401 without an authenticated user, 400 without an active study.
     *
     * @return the refusal to return to the client, or null to carry on
     */
    public ResponseEntity<?> guardSession(HttpSession session) {
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user == null || user.getId() == 0) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        StudyBean study = (StudyBean) session.getAttribute("study");
        if (study == null || study.getId() == 0) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "No active study bound to the session — POST /pages/api/v1/me/activeStudy first."));
        }
        return null;
    }

    /**
     * 403 when the study is outside what this session may see. Sysadmins pass.
     *
     * <p>A null study id is refused rather than waved through: it means the
     * caller could not establish which study owns the resource, and that is not
     * a reason to show it.
     */
    public ResponseEntity<?> guardStudyVisibility(Integer studyId, HttpSession session, String denyMessage) {
        if (studyId == null) {
            return ResponseEntity.status(403).body(Map.of("message", denyMessage));
        }
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (user != null && user.isSysAdmin()) return null;
        if (visibleStudyIds(session).contains(studyId)) return null;
        return ResponseEntity.status(403).body(Map.of("message", denyMessage));
    }

    /**
     * As {@link #guardStudyVisibility}, and additionally allows a resource in a
     * study the user holds a live role on but has not made active.
     *
     * <p>For surfaces reached by link rather than by navigation: a URL pointing
     * at a job arrives with whatever study the session was last pointed at, and
     * refusing it would mean telling a user they cannot see their own study's
     * data until they switch context by hand.
     *
     * <p>Use the strict form for anything that streams patient imagery.
     */
    public ResponseEntity<?> guardStudyVisibilityAllowingDeepLink(
            Integer studyId, HttpSession session, String denyMessage) {
        if (studyId == null) {
            return ResponseEntity.status(403).body(Map.of("message", denyMessage));
        }
        UserAccountBean user = (UserAccountBean) session.getAttribute("userBean");
        if (visibleStudyIds(session).contains(studyId)) return null;
        if (user != null && user.isSysAdmin()) return null;
        if (user != null && hasLiveRoleOn(user, studyId)) return null;
        return ResponseEntity.status(403).body(Map.of("message", denyMessage));
    }

    /**
     * Which study a subject belongs to, or null when there is no such subject.
     *
     * <p>Every per-subject endpoint needs this immediately before a visibility
     * guard, and the two answers are deliberately separate: "no such subject"
     * is a 404 and "not yours to see" is a 403, and collapsing them would
     * either leak the existence of subjects in other studies or hide a genuine
     * typo behind a permissions error.
     */
    public Integer studyIdForStudySubject(int studySubjectId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT study_id FROM study_subject WHERE study_subject_id = ?")) {
            ps.setInt(1, studySubjectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int sid = rs.getInt(1);
                return rs.wasNull() ? null : sid;
            }
        } catch (SQLException e) {
            LOG.warn("study lookup failed for study_subject {}: {}", studySubjectId, e.getMessage());
            return null;
        }
    }

    /** What the session may see, per the site-visibility rules. */
    public Set<Integer> visibleStudyIds(HttpSession session) {
        return siteVisibilityFilter.visibleStudyIds(
                (UserAccountBean) session.getAttribute("userBean"),
                (StudyBean) session.getAttribute("study"),
                (StudyUserRoleBean) session.getAttribute("userRole"));
    }

    /** True when the user holds an AVAILABLE role on the named study. */
    private boolean hasLiveRoleOn(UserAccountBean user, Integer studyId) {
        if (user == null || studyId == null) return false;
        try {
            List<StudyUserRoleBean> grants =
                    new UserAccountDAO(dataSource).findAllRolesByUserName(user.getName());
            for (StudyUserRoleBean g : grants) {
                if (g == null || g.getStudyId() != studyId) continue;
                if (g.getStatus() != null && g.getStatus().getId() == Status.AVAILABLE.getId()) {
                    return true;
                }
            }
        } catch (Exception lookupFailed) {
            // Fail closed: an unanswerable question about access is a no.
            LOG.warn("role lookup failed for user={} study={}: {}",
                    user.getName(), studyId, lookupFailed.getMessage());
        }
        return false;
    }
}
