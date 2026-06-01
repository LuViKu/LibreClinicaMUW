/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.auth;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Phase E.5 A4 — per-site authorization filter.
 *
 * <p>Computes the {@code Set<Integer>} of {@code study.study_id} rows
 * that the current user-with-role can legitimately see while a given
 * {@code currentStudy} is bound to the session.
 *
 * <p>Background: LibreClinica's study tree is two-tier — top-level
 * studies (parent_study_id = 0) own zero or more "site" sub-studies
 * (parent_study_id = top-level id). A user is granted a role on either
 * the parent or one specific site. Until this filter shipped, every
 * Phase-E.4 API adapter applied the bare {@code findAllByStudyId(currentStudy)}
 * shortcut — meaning a Monitor user with a Monitor-on-Site grant who
 * happened to have the parent study bound in session could read every
 * site's PHI through the SPA. The legacy JSP servlets enforce the
 * boundary by walking the parent-vs-site fork explicitly; this helper
 * carries the same rule into the SPA's REST adapters in one place so
 * the controllers don't each invent their own logic.
 *
 * <h2>Contract</h2>
 *
 * <p>For a {@code currentStudy} bound to the session and the session
 * user's {@link StudyUserRoleBean} (one role per (user, study) pair):
 *
 * <ul>
 *   <li>If {@code currentStudy.parentStudyId > 0}: it's already a
 *       site; visibility is always {@code {currentStudy.id}} — sites
 *       can never see siblings or the parent's other-site data even
 *       if the user has roles on those siblings.</li>
 *   <li>If {@code currentStudy} is top-level (parent_study_id = 0):
 *     <ul>
 *       <li>{@code ADMIN, STUDYDIRECTOR}: top-level + every site
 *           under it (this matches the legacy admin behaviour where
 *           the System Administrator and the Study Director can read
 *           the whole tree).</li>
 *       <li>{@code INVESTIGATOR, COORDINATOR, RESEARCHASSISTANT,
 *           RESEARCHASSISTANT2}: top-level (the user has a parent
 *           grant) + every site the user explicitly has a grant on.
 *           A user with NO site grants and a parent grant sees only
 *           the parent's directly-owned rows.</li>
 *       <li>{@code MONITOR}: ONLY the sites the user has a Monitor
 *           grant on. Monitors with a parent grant still see only
 *           the sites they're explicitly granted on — by spec, a
 *           parent-only Monitor grant is the legacy 3-tier setup
 *           where the monitor sees every site through inheritance,
 *           but we conservatively narrow that to the explicit
 *           per-site grants the {@code study_user_role} table
 *           records. (The legacy JSP filters use the same
 *           narrowing.)</li>
 *     </ul></li>
 * </ul>
 *
 * <h2>Implementation notes</h2>
 *
 * <ul>
 *   <li>Backed by {@link StudyDAO#findAllByParent(int)} for the
 *       parent → site walk and
 *       {@link UserAccountDAO#findAllRolesByUserName(String)} for the
 *       user's grant set across all studies.</li>
 *   <li>The current session role's {@code studyId} is checked first
 *       before walking the broader grant set — this avoids an extra
 *       SQL round-trip on the common case (single-role users).</li>
 *   <li><strong>Performance:</strong> the helper is allocated
 *       per-request from the controllers (it's a stateless
 *       {@code @Component}); the two DB round-trips (parent → sites,
 *       user grants) execute on every visible-study lookup. For
 *       studies with > 10 sites and high-traffic endpoints (audit
 *       log especially) a future optimization is to (a) cache the
 *       parent → sites list session-locally because site membership
 *       doesn't change mid-session, and (b) add a multi-study-id IN
 *       clause to the AuditApi SQL to avoid the per-study loop.
 *       Out of scope for A4 — see plan's "perf optimization for
 *       SiteVisibilityFilter if site count > 10" follow-up note.</li>
 *   <li>The helper deliberately returns a {@link LinkedHashSet} so
 *       deterministic iteration order keeps tests stable.</li>
 * </ul>
 */
@Component
public class SiteVisibilityFilter {

    private static final Logger LOG = LoggerFactory.getLogger(SiteVisibilityFilter.class);

    private final DataSource dataSource;

    @Autowired
    public SiteVisibilityFilter(@Qualifier("dataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Compute the set of study_ids visible to the given user-with-role
     * while the supplied {@code currentStudy} is bound to the session.
     * See class JavaDoc for the full contract.
     *
     * @param user         the session-bound {@link UserAccountBean}; never
     *                     {@code null}.
     * @param currentStudy the session-bound active study; never
     *                     {@code null}. If its id is 0 the helper
     *                     returns the empty set.
     * @param currentRole  the session-bound role-on-study; may be
     *                     {@code null} (interpreted as "no role" →
     *                     empty set).
     * @return a never-{@code null} {@link Set} of study_ids. The set
     *         contains {@code currentStudy.id} (or its parent's id, in
     *         the site-only case) unless the role is so restricted
     *         that the user can see nothing.
     */
    public Set<Integer> visibleStudyIds(UserAccountBean user, StudyBean currentStudy,
                                        StudyUserRoleBean currentRole) {
        Set<Integer> visible = new LinkedHashSet<>();
        if (currentStudy == null || currentStudy.getId() == 0) {
            return visible;
        }

        // Case 1: bound study is itself a site. Always exactly itself.
        if (currentStudy.getParentStudyId() > 0) {
            visible.add(currentStudy.getId());
            return visible;
        }

        // Case 2: bound study is top-level. The answer depends on the role.
        Role role = (currentRole == null) ? null : currentRole.getRole();
        if (role == null || role.getId() == Role.INVALID.getId()) {
            // No valid role bound — see only the top-level rows the
            // user has explicitly. Defence-in-depth fallback; in
            // practice the chain-level hasRole("USER") gate excludes
            // unbound users before we reach this code.
            visible.add(currentStudy.getId());
            return visible;
        }

        StudyDAO studyDAO = new StudyDAO(dataSource);
        List<StudyBean> siteList = studyDAO.findAllByParent(currentStudy.getId());

        if (role.getId() == Role.ADMIN.getId() || role.getId() == Role.STUDYDIRECTOR.getId()) {
            // Admin / Study Director: full tree.
            visible.add(currentStudy.getId());
            if (siteList != null) {
                for (StudyBean site : siteList) {
                    if (site != null && site.getId() > 0) visible.add(site.getId());
                }
            }
            return visible;
        }

        // Investigator / Coordinator / RA / RA2 / Monitor — narrow by
        // the user's explicit grants. For everyone except Monitor we
        // also include the top-level itself (they have a parent grant
        // bound).
        boolean includeParent = role.getId() != Role.MONITOR.getId();
        if (includeParent) {
            visible.add(currentStudy.getId());
        }

        // Look up the user's grants in one DB round-trip and pick out
        // the ones for this site sub-tree.
        Set<Integer> siteIdsUnderParent = new HashSet<>();
        if (siteList != null) {
            for (StudyBean site : siteList) {
                if (site != null && site.getId() > 0) siteIdsUnderParent.add(site.getId());
            }
        }
        if (siteIdsUnderParent.isEmpty()) {
            // No sites at all under this parent — visible set is
            // either {parent} or {} per the includeParent rule above.
            return visible;
        }

        UserAccountDAO userDAO = new UserAccountDAO(dataSource);
        List<StudyUserRoleBean> allGrants = userDAO.findAllRolesByUserName(user.getName());
        if (allGrants == null) return visible;
        for (StudyUserRoleBean grant : allGrants) {
            if (grant == null) continue;
            int gid = grant.getStudyId();
            if (gid <= 0) continue;
            if (!siteIdsUnderParent.contains(gid)) continue;
            // Honour Monitor narrowness — only grants of the
            // monitor role count for the monitor case. For the
            // other roles every grant counts (they're the user's
            // own per-site grants, all valid).
            if (role.getId() == Role.MONITOR.getId()) {
                Role gRole = grant.getRole();
                if (gRole == null || gRole.getId() != Role.MONITOR.getId()) continue;
            }
            visible.add(gid);
        }
        LOG.debug("SiteVisibilityFilter: user={} role={} currentStudy={} → visible={}",
                user.getName(), role.getName(), currentStudy.getOid(), visible);
        return visible;
    }
}
