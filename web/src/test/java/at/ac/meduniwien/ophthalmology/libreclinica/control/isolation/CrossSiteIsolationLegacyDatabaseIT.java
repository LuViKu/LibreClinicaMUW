/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ChangeStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.view.Page;

/**
 * Cross-site isolation of the heritage servlets and JSP pages, for what holds.
 *
 * <p>The external users of a multicenter study hold a role on one site only.
 * Every legacy servlet is reachable by any logged-in user ({@code
 * SecurityConfig}: {@code anyRequest().hasRole("USER")}); what keeps site A's
 * user out of site B's records is the check each servlet makes. This class
 * runs the servlets as site A's investigator, coordinator, monitor and
 * research assistant, aimed at site B's ids, and requires an answer without any
 * of B's data (label, subject ID, date of birth, item values, discrepancy
 * notes, dataset and file names, user names) and without a change to any row.
 * For each probe a request with site A's own ids is the positive control: it
 * must get past the access check, or the refusal above proves nothing.
 *
 * <p>The requests that do leak are in {@link CrossSiteIsolationLegacyLeaksDatabaseIT}.
 *
 * <h2>Inventory</h2>
 * 210 servlets are registered in {@code LegacyServletRegistry}
 * ({@code @Configuration}, {@code ServletContextInitializer}), 209 of them with
 * a URL; the {@code pages} DispatcherServlet serves the Spring MVC controllers
 * under {@code /pages}. {@code libreclinica.legacy.closedPaths}
 * ({@code application.yml}) answers 410 for 17 servlets and everything below
 * {@code /pages/auth} to anyone but a system administrator (Enterprise,
 * TechAdmin, AdminSystem, AuditDatabase, ListSubject, ListSubjectData,
 * ViewSubject, UpdateSubject, RemoveSubject, RestoreSubject, CreateJobImport,
 * UpdateJobImport, ViewImportJob, ViewLogMessage, PrintoutCertificate,
 * DeleteEventCRF, ConfigurePasswordRequirements, /pages/auth); those are out of
 * scope here. The rest either refuse non-administrators in {@code mayProceed()}
 * (user, role, job, CRF-library, study-creation and system screens) or are
 * reachable by the site roles, which is what the probes cover; see
 * {@link IsolationProbes}.
 *
 * <p>The session is built as a real login builds it, see {@link IsolationDriver}.
 *
 * <h2>Static review only (not driven by a test)</h2>
 * Paths in {@link IsolationProbes#NO_CONTROL} could not be run past their access check here, and other entry
 * points are not servlets of the table at all. Checked by reading, relative to
 * {@code web/src/main/java/.../}:
 * <ul>
 * <li><b>holds</b>: {@code control/managestudy/ViewNotesServlet}, {@code ViewNotesDataServlet},
 *     {@code control/extract/DiscrepancyNoteOutputServlet} (the note query is bound to the session's study,
 *     {@code ViewNotesDaoImpl} of the core module, 124-140, 228-230); {@code RemoveDatasetServlet} (69-77), {@code EditDatasetServlet}
 *     (58-66), {@code ExportDatasetServlet} (113-120), {@code AccessFileServlet} (62-66): a dataset must be the
 *     session study's or its child's; {@code RemoveSiteServlet} (96-100): the site must be a child of the session
 *     study; {@code ImportCRFDataServlet}: {@code ImportCRFDataService.validateStudyMetadata} (928) ties the ODM
 *     StudyOID to the session study and every lookup is {@code findByOidAndStudy}; {@code DownloadAttachedFileServlet}
 *     (65-100): the file is looked up under the session study's and its parent's OID directory only, by base
 *     name; rule servlets and {@code ChangeCRFVersionController}/{@code SDVController}: {@code StudyTreeScope}.</li>
 * <li><b>leaks, not driven</b>: {@code controller/ExtractController.processSubmit} ({@code POST /pages/extract},
 *     80-104 and 235-245) starts an export of any {@code datasetId} for a monitor, investigator, coordinator or
 *     director, with no check that the dataset is the session study's (the file stays unreachable through
 *     {@code AccessFileServlet}, the job and its output files are not);
 *     {@code controller/BatchCRFMigrationController.getLogFile} ({@code GET /pages/forms/migrate/{filename}/downloadLogFile},
 *     140-153) has no role or study check; {@code controller/RuleController.studyMetadata}
 *     ({@code GET /pages/rule/studies/{study}/metadata}, 233-246) returns the ODM metadata and AdminData of any study
 *     OID without the {@code mayProceed} the POST endpoints of that class call (329, 351, 389, 436);
 *     {@code control/managestudy/AssignUserToStudyServlet} ({@code findUsers}, 259-261) lists every available
 *     account of the installation to a site coordinator; {@code control/submit/CheckCRFLocked} answers the name of
 *     whoever holds the lock on any event CRF id.</li>
 * </ul>
 */
class CrossSiteIsolationLegacyDatabaseIT extends AbstractIsolationIT {

    // ---- the fixture is what the tests say it is ---------------------------------------------------

    @Test
    void theSiteUsersHoldARoleOnTheirSiteOnly() throws Exception {
        for (String role : IsolationFixture.SITE_ROLES) {
            String user = shared.a.userName(role);
            assertEquals(1, IsolationFixture.query(DATA_SOURCE,
                    "SELECT COUNT(*) FROM study_user_role WHERE user_name = '" + user + "'"));
            assertEquals(shared.a.studyId, IsolationFixture.query(DATA_SOURCE,
                    "SELECT study_id FROM study_user_role WHERE user_name = '" + user + "'"));
            assertEquals(shared.a.studyId, IsolationFixture.query(DATA_SOURCE,
                    "SELECT active_study FROM user_account WHERE user_name = '" + user + "'"));
        }
        assertEquals(1, IsolationFixture.query(DATA_SOURCE,
                "SELECT parent_study_id FROM study WHERE study_id = " + shared.a.studyId));
        assertEquals(1, IsolationFixture.query(DATA_SOURCE,
                "SELECT parent_study_id FROM study WHERE study_id = " + shared.b.studyId));
    }

    @Test
    void theFirstRequestOfASiteUserBindsTheSiteWithTheSiteRole() throws Exception {
        for (String role : IsolationFixture.SITE_ROLES) {
            MockHttpServletRequest req = driver.request("GET", "/ListStudySubjects", shared.a.userName(role));
            driver.run(new at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ListStudySubjectsServlet(), req);
            StudyBean study = (StudyBean) req.getSession().getAttribute("study");
            assertEquals(shared.a.studyId, study.getId(), role);
            at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean bound =
                    (at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean) req.getSession()
                            .getAttribute("userRole");
            assertTrue(switch (role) {
                case IsolationFixture.INVESTIGATOR -> bound.isInvestigator();
                case IsolationFixture.COORDINATOR -> bound.isCoordinator();
                case IsolationFixture.MONITOR -> bound.isMonitor();
                default -> bound.isResearchAssistant();
            }, role + " is bound as " + bound.getRole());
        }
    }

    // ---- positive controls: site A's own ids get past the access check ------------------------------

    static Stream<Arguments> controls() {
        return IsolationProbes.all().stream().filter(p -> !IsolationProbes.NO_CONTROL.contains(p.name()))
                .map(p -> Arguments.of(p.name()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("controls")
    void aSiteUserGetsPastTheAccessCheckWithTheirOwnIds(String name) throws Exception {
        IsolationProbes.Probe probe = probe(name);
        boolean served = false;
        StringBuilder trail = new StringBuilder();
        for (String role : IsolationFixture.SITE_ROLES) {
            IsolationFixture fx = probe.writes() ? fresh() : shared;
            Verdict v = run(probe, fx, role, fx.a);
            trail.append('\n').append(role).append(": ").append(v.describe());
            served |= gotPastTheAccessCheck(v);
        }
        assertTrue(served, name + ": no site role got past the access check with its own ids" + trail);
    }

    // ---- site A's user, site B's ids -----------------------------------------------------------------

    static Stream<Arguments> safeAttacks() {
        return attacks(false);
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("safeAttacks")
    void aSiteUserCannotReadOrChangeAnotherSitesRecords(String name, String role) throws Exception {
        IsolationProbes.Probe probe = probe(name);
        IsolationFixture fx = probe.writes() ? fresh() : shared;
        Verdict v = run(probe, fx, role, fx.b);
        assertTrue(v.clean(), name + " as " + role + " reached site B: " + v.describe());
    }

    // ---- ChangeStudy ---------------------------------------------------------------------------------

    /**
     * {@code UserAccountDAO.findStudyByUser} (lines 738-802) lists, for a user with a role on a site only, the
     * parent study and every sibling site as well, with the role INVALID. Confirming one of them therefore
     * prepares a switch whose role carries no privilege; that the following "submit" does not stay within that
     * role is the leak in {@link CrossSiteIsolationLegacyLeaksDatabaseIT}.
     */
    @Test
    void changeStudyConfirmOffersOtherStudiesOnlyWithoutARole() throws Exception {
        IsolationFixture fx = fresh();
        for (int other : new int[] { fx.b.studyId, fx.parentStudyId }) {
            MockHttpServletRequest req = driver.request("POST", "/ChangeStudy", fx.a.userName(IsolationFixture.INVESTIGATOR),
                    "action", "confirm", "studyId", String.valueOf(other));
            driver.run(new ChangeStudyServlet(), req);
            at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean offered =
                    (at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean) req.getSession()
                            .getAttribute("studyWithRole");
            assertTrue(offered == null || offered.isInvalid(), "the offer for study " + other + " carries a role: "
                    + (offered == null ? null : offered.getRole()));
        }
        // A study the user has nothing to do with is not offered at all.
        MockHttpServletRequest req = driver.request("POST", "/ChangeStudy", fx.a.userName(IsolationFixture.INVESTIGATOR),
                "action", "confirm", "studyId", "102");
        IsolationDriver.Outcome out = driver.run(new ChangeStudyServlet(), req);
        assertNull(req.getSession().getAttribute("studyWithRole"), "no switch prepared to study 102");
        assertTrue(!Page.CHANGE_STUDY_CONFIRM.getFileName().equals(out.firstForward()), out.toString());
    }

    @Test
    void changeStudyToTheUsersOwnSiteIsAccepted() throws Exception {
        IsolationFixture fx = fresh();
        String user = fx.a.userName(IsolationFixture.INVESTIGATOR);
        MockHttpServletRequest first = driver.request("POST", "/ChangeStudy", user, "action", "confirm", "studyId",
                String.valueOf(fx.a.studyId));
        driver.run(new ChangeStudyServlet(), first);
        assertTrue(first.getSession().getAttribute("studyWithRole") != null, "the switch to the own site is prepared");
        MockHttpServletRequest second = driver.continued(first, "POST", "/ChangeStudy", "action", "submit", "studyId",
                String.valueOf(fx.a.studyId));
        driver.run(new ChangeStudyServlet(), second);
        assertEquals(fx.a.studyId, ((StudyBean) first.getSession().getAttribute("study")).getId());
    }
}
