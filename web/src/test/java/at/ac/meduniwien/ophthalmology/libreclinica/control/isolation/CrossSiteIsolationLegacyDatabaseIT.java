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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ChangeStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ReassignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.FindSubjectsDataServlet;
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
 * <p>Every probe of {@link IsolationProbes#all()} is asserted here, and so are the ChangeStudy switch and the
 * multi-step flows below. The record-level check is {@code SecureController.assertRecordInScope} (the study
 * tree rule of {@code StudyTreeScope}): a record is in scope when its study subject sits in the session's
 * study or in one of its sites.
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
 * <li><b>fixed, tested below</b>: {@code controller/ExtractController.processSubmit} (the dataset must be the session
 *     study's tree), {@code BatchCRFMigrationController.getLogFile} (Study Director or Data Manager, log file names
 *     only), {@code RuleController.studyMetadata} (Study Director or Data Manager of that study),
 *     {@code AssignUserToStudyServlet} (a site coordinator is offered the parent study's team only) and
 *     {@code CheckCRFLocked} (the event CRF must be the session study's).</li>
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
        return attacks();
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

    /** The switch is meant to be confirmed against the user's own roles first; "submit" alone is not. */
    @Test
    void changeStudySubmitWithoutConfirmDoesNotBindAStudyTheUserHasNoRoleIn() throws Exception {
        IsolationFixture fx = fresh();
        String user = fx.a.userName(IsolationFixture.INVESTIGATOR);
        MockHttpServletRequest req = driver.request("POST", "/ChangeStudy", user, "action", "submit", "studyId",
                String.valueOf(fx.b.studyId));
        driver.run(new ChangeStudyServlet(), req);

        Object bound = req.getSession().getAttribute("study");
        assertTrue(bound == null || ((StudyBean) bound).getId() != fx.b.studyId,
                "the session is bound to site B, where the user has no role");
        assertEquals(fx.a.studyId, IsolationFixture.query(DATA_SOURCE,
                "SELECT active_study FROM user_account WHERE user_name = '" + user + "'"),
                "the account's active study was changed to site B");
    }

    static Stream<Arguments> otherStudies() {
        return Stream.of(Arguments.of("site B"), Arguments.of("the parent study"), Arguments.of("another protocol (study 102)"));
    }

    /**
     * Confirming the user's own site stores {@code studyWithRole}; the submit that follows must name that study.
     */
    @ParameterizedTest(name = "to {0}")
    @MethodSource("otherStudies")
    void changeStudyConfirmedForTheOwnSiteCannotBeSubmittedForAnotherStudy(String target) throws Exception {
        IsolationFixture fx = fresh();
        int studyId = studyIdOf(target, fx);
        String user = fx.a.userName(IsolationFixture.INVESTIGATOR);
        MockHttpServletRequest first = driver.request("POST", "/ChangeStudy", user, "action", "confirm", "studyId",
                String.valueOf(fx.a.studyId));
        driver.run(new ChangeStudyServlet(), first);
        MockHttpServletRequest second = driver.continued(first, "POST", "/ChangeStudy", "action", "submit", "studyId",
                String.valueOf(studyId));
        driver.run(new ChangeStudyServlet(), second);

        assertEquals(fx.a.studyId, ((StudyBean) first.getSession().getAttribute("study")).getId(),
                "the session now works in " + target);
        assertEquals(fx.a.studyId, IsolationFixture.query(DATA_SOURCE,
                "SELECT active_study FROM user_account WHERE user_name = '" + user + "'"),
                "the account's active study was changed to " + target);
    }

    /** The parent and the sibling sites are listed without a role; they cannot be confirmed or submitted. */
    @ParameterizedTest(name = "to {0}")
    @MethodSource("otherStudies")
    void changeStudyToAStudyListedWithoutARoleIsRefusedEvenWhenConfirmedFirst(String target) throws Exception {
        IsolationFixture fx = fresh();
        int studyId = studyIdOf(target, fx);
        String user = fx.a.userName(IsolationFixture.COORDINATOR);
        MockHttpServletRequest first = driver.request("POST", "/ChangeStudy", user, "action", "confirm", "studyId",
                String.valueOf(studyId));
        driver.run(new ChangeStudyServlet(), first);
        MockHttpServletRequest second = driver.continued(first, "POST", "/ChangeStudy", "action", "submit", "studyId",
                String.valueOf(studyId));
        driver.run(new ChangeStudyServlet(), second);

        Object bound = first.getSession().getAttribute("study");
        assertTrue(bound == null || ((StudyBean) bound).getId() != studyId, "the session is bound to " + target);
        assertEquals(fx.a.studyId, IsolationFixture.query(DATA_SOURCE,
                "SELECT active_study FROM user_account WHERE user_name = '" + user + "'"));
    }

    @ParameterizedTest(name = "to {0}")
    @MethodSource("otherStudies")
    void afterAChangeStudyTheSiteUserCannotListAnotherSitesSubjects(String target) throws Exception {
        IsolationFixture fx = fresh();
        int studyId = studyIdOf(target, fx);
        MockHttpServletRequest first = driver.request("POST", "/ChangeStudy", fx.a.userName(IsolationFixture.INVESTIGATOR),
                "action", "confirm", "studyId", String.valueOf(fx.a.studyId));
        driver.run(new ChangeStudyServlet(), first);
        driver.run(new ChangeStudyServlet(), driver.continued(first, "POST", "/ChangeStudy", "action", "submit",
                "studyId", String.valueOf(studyId)));

        IsolationDriver.Outcome list = driver.run(new FindSubjectsDataServlet(),
                driver.continued(first, "GET", "/FindSubjectsData", "length", "500"));
        List<String> others = new ArrayList<>(fx.b.markers());
        others.add(IsolationFixture.text(DATA_SOURCE, "SELECT label FROM study_subject WHERE study_subject_id = 102"));
        assertTrue(list.bodyLeaks(others).isEmpty(),
                "FindSubjectsData listed the subjects of " + target + ": " + list.bodyLeaks(others));
    }

    /** The role checks of every legacy servlet run against the session's study and role, which the switch replaces. */
    @Test
    void aSiteCoordinatorCannotActAsCoordinatorOfAnotherStudyAfterAChangeStudy() throws Exception {
        IsolationFixture fx = fresh();
        String user = fx.a.userName(IsolationFixture.COORDINATOR);
        MockHttpServletRequest first = driver.request("POST", "/ChangeStudy", user, "action", "confirm", "studyId",
                String.valueOf(fx.a.studyId));
        driver.run(new ChangeStudyServlet(), first);
        driver.run(new ChangeStudyServlet(), driver.continued(first, "POST", "/ChangeStudy", "action", "submit",
                "studyId", "102"));
        IsolationDriver.Outcome list = driver.run(new at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ListStudyUserServlet(),
                driver.continued(first, "GET", "/ListStudyUser"));
        assertTrue(list.refused() || list.bodyLeaks(List.of("study 102")).isEmpty(),
                "a site coordinator lists the users of study 102: " + list);
        assertEquals(fx.a.studyId, ((StudyBean) first.getSession().getAttribute("study")).getId());
    }

    /** A user who genuinely holds the parent role may still switch to the parent and to a site below it. */
    @ParameterizedTest(name = "to {0}")
    @MethodSource("ownStudies")
    void aUserWithAParentRoleMaySwitchToTheParentAndItsSites(String target) throws Exception {
        IsolationFixture fx = fresh();
        String user = "iso_parentcoord_" + fx.a.tag.toLowerCase();
        IsolationFixture.update(DATA_SOURCE, "INSERT INTO user_account (user_name, passwd, first_name, last_name, email,"
                + " active_study, institutional_affiliation, status_id, owner_id, date_created, user_type_id, enabled,"
                + " account_non_locked, lock_counter, run_webservices, authtype, enable_api_key, passwd_timestamp)"
                + " VALUES ('" + user + "', 'x', 'F', 'L', '" + user + "@example.invalid', 1, 'X', 1, 1, now(), 2,"
                + " true, true, 0, false, 'STANDARD', false, now())");
        IsolationFixture.update(DATA_SOURCE, "INSERT INTO study_user_role (role_name, study_id, status_id, owner_id,"
                + " date_created, user_name) VALUES ('coordinator', 1, 1, 1, now(), '" + user + "')");
        int studyId = "the parent study".equals(target) ? fx.parentStudyId : fx.a.studyId;
        MockHttpServletRequest first = driver.request("POST", "/ChangeStudy", user, "action", "confirm", "studyId",
                String.valueOf(studyId));
        driver.run(new ChangeStudyServlet(), first);
        assertNotNull(first.getSession().getAttribute("studyWithRole"), "the switch to " + target + " is not prepared");
        driver.run(new ChangeStudyServlet(), driver.continued(first, "POST", "/ChangeStudy", "action", "submit",
                "studyId", String.valueOf(studyId)));
        assertEquals(studyId, ((StudyBean) first.getSession().getAttribute("study")).getId());
    }

    static Stream<Arguments> ownStudies() {
        return Stream.of(Arguments.of("the parent study"), Arguments.of("a site"));
    }

    private static int studyIdOf(String target, IsolationFixture fx) {
        return switch (target) {
            case "site B" -> fx.b.studyId;
            case "the parent study" -> fx.parentStudyId;
            default -> 102;
        };
    }

    // ---- multi-step flows ---------------------------------------------------------------------------------

    /** {@code action=show} loads the study subject named by {@code id} into the session; later steps use that copy. */
    @ParameterizedTest(name = "as {0}")
    @MethodSource("updateRoles")
    void updateStudySubjectCannotRelabelASubjectOfAnotherSite(String role) throws Exception {
        IsolationFixture fx = fresh();
        String ss = String.valueOf(fx.b.studySubjectId);
        MockHttpServletRequest show = driver.request("GET", "/UpdateStudySubject", fx.a.userName(role), "id", ss,
                "action", "show");
        driver.run(new UpdateStudySubjectServlet(), show);
        driver.run(new UpdateStudySubjectServlet(), driver.continued(show, "POST", "/UpdateStudySubject", "id", ss,
                "action", "confirm", "label", "HIJACKED-LABEL", "secondaryLabel", "HIJACKED-SEC", "enrollmentDate",
                "01/20/2026"));
        driver.run(new UpdateStudySubjectServlet(), driver.continued(show, "POST", "/UpdateStudySubject", "id", ss,
                "action", "submit"));

        assertEquals(fx.b.label(), IsolationFixture.text(DATA_SOURCE,
                "SELECT label FROM study_subject WHERE study_subject_id = " + ss),
                "site B's subject was relabelled by a site-A user");
    }

    static Stream<Arguments> updateRoles() {
        return Stream.of(Arguments.of(IsolationFixture.INVESTIGATOR), Arguments.of(IsolationFixture.COORDINATOR),
                Arguments.of(IsolationFixture.RESEARCH_ASSISTANT));
    }

    /** {@code studyId} is any study: a coordinator could push the site's own subject into another site. */
    @ParameterizedTest(name = "to {0}")
    @MethodSource("otherStudies")
    void reassignStudySubjectCannotMoveASubjectIntoAnotherSite(String target) throws Exception {
        IsolationFixture fx = fresh();
        MockHttpServletRequest req = driver.request("POST", "/ReassignStudySubject",
                fx.a.userName(IsolationFixture.COORDINATOR), "id", String.valueOf(fx.a.studySubjectId), "action",
                "submit", "studyId", String.valueOf(studyIdOf(target, fx)));
        driver.run(new ReassignStudySubjectServlet(), req);

        assertEquals(fx.a.studyId, IsolationFixture.query(DATA_SOURCE,
                "SELECT study_id FROM study_subject WHERE study_subject_id = " + fx.a.studySubjectId),
                "site A's subject now belongs to " + target);
    }

    // ---- record-scope check: denial ends the request ------------------------------------------------------

    /** A refusal by checkRoleByUserAndStudy must not be followed by the write the servlet makes after it. */
    @Test
    void aRefusedRoleCheckEndsTheRequestBeforeTheWrite() throws Exception {
        IsolationFixture fx = fresh();
        // the subject of A, with a request that names site B as its study: the role check sees no role on B
        MockHttpServletRequest req = driver.request("POST", "/RemoveStudySubject",
                fx.a.userName(IsolationFixture.COORDINATOR), "action", "submit", "id",
                String.valueOf(fx.a.studySubjectId), "subjectId", String.valueOf(fx.a.personId), "studyId",
                String.valueOf(fx.b.studyId));
        IsolationDriver.Outcome out = driver.run(new at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.RemoveStudySubjectServlet(), req);
        assertEquals(1, IsolationFixture.query(DATA_SOURCE,
                "SELECT status_id FROM study_subject WHERE study_subject_id = " + fx.a.studySubjectId),
                "the subject was removed after the role check refused: " + out);
        assertEquals(1, out.forwards.size(), "more than one forward: " + out);
    }

    // ---- static-review leaks, now driven -------------------------------------------------------------------

    /** The lock holder of an event CRF is named only to someone working in that CRF's study. */
    @Test
    void checkCrfLockedDoesNotNameTheHolderOfAnotherSitesLock() throws Exception {
        IsolationFixture fx = fresh();
        int holder = IsolationFixture.query(DATA_SOURCE,
                "SELECT user_id FROM user_account WHERE user_name = '" + fx.b.userName(IsolationFixture.INVESTIGATOR) + "'");
        driver.crfLocker.lock(fx.b.eventCrfId, holder);
        try {
            IsolationDriver.Outcome other = driver.run(new at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CheckCRFLocked(),
                    driver.request("POST", "/CheckCRFLocked", fx.a.userName(IsolationFixture.INVESTIGATOR), "ecId",
                            String.valueOf(fx.b.eventCrfId)));
            assertTrue(other.bodyLeaks(List.of(fx.b.userName(IsolationFixture.INVESTIGATOR))).isEmpty(),
                    "the lock holder of site B's CRF was named: " + other);
        } finally {
            driver.crfLocker.unlock(fx.b.eventCrfId);
        }
    }

    @Test
    void checkCrfLockedStillNamesTheHolderOfALockInTheOwnStudy() throws Exception {
        IsolationFixture fx = fresh();
        int holder = IsolationFixture.query(DATA_SOURCE,
                "SELECT user_id FROM user_account WHERE user_name = '" + fx.a.userName(IsolationFixture.COORDINATOR) + "'");
        driver.crfLocker.lock(fx.a.eventCrfId, holder);
        try {
            IsolationDriver.Outcome own = driver.run(new at.ac.meduniwien.ophthalmology.libreclinica.control.submit.CheckCRFLocked(),
                    driver.request("POST", "/CheckCRFLocked", fx.a.userName(IsolationFixture.INVESTIGATOR), "ecId",
                            String.valueOf(fx.a.eventCrfId)));
            assertTrue(own.body.contains(fx.a.userName(IsolationFixture.COORDINATOR)), "the holder is not named: " + own);
        } finally {
            driver.crfLocker.unlock(fx.a.eventCrfId);
        }
    }

    /** A site coordinator is offered the study's team (accounts with a parent role), not every account. */
    @Test
    void assignUserToStudyDoesNotOfferEveryAccountToASiteCoordinator() throws Exception {
        IsolationFixture fx = fresh();
        IsolationDriver.Outcome out = driver.run(
                new at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.AssignUserToStudyServlet(),
                driver.request("GET", "/AssignUserToStudy", fx.a.userName(IsolationFixture.COORDINATOR)));
        assertTrue(out.thrown == null && out.swallowed == null, "the servlet did not run: " + out);
        List<String> strangers = List.of(fx.b.userName(IsolationFixture.INVESTIGATOR),
                fx.a.userName(IsolationFixture.INVESTIGATOR));
        assertTrue(out.attributeLeaks(strangers).isEmpty() && out.bodyLeaks(strangers).isEmpty(),
                "accounts without a role on the parent study were offered: " + out.attributeLeaks(strangers));
    }

    private static final String EXTRACT_FORBIDDEN = "/MainMenu?message=authentication_failed";

    private static org.apache.commons.dbcp.BasicDataSource pool() {
        org.apache.commons.dbcp.BasicDataSource p = new org.apache.commons.dbcp.BasicDataSource();
        p.setDriverClassName("org.postgresql.Driver");
        p.setUrl(POSTGRES.getJdbcUrl());
        p.setUsername(POSTGRES.getUsername());
        p.setPassword(POSTGRES.getPassword());
        return p;
    }

    /** A session of site A's {@code role}: the first request binds study and role as a login does. */
    private MockHttpServletRequest boundSession(IsolationFixture fx, String role) {
        MockHttpServletRequest first = driver.request("GET", "/ListStudySubjects", fx.a.userName(role));
        driver.run(new at.ac.meduniwien.ophthalmology.libreclinica.control.submit.ListStudySubjectsServlet(), first);
        return first;
    }

    @Test
    void extractCannotStartAnExportOfAnotherSitesDataset() throws Exception {
        IsolationFixture fx = fresh();
        at.ac.meduniwien.ophthalmology.libreclinica.controller.ExtractController controller =
                new at.ac.meduniwien.ophthalmology.libreclinica.controller.ExtractController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "dataSource", pool());
        MockHttpServletRequest first = boundSession(fx, IsolationFixture.INVESTIGATOR);
        MockHttpServletRequest req = driver.continued(first, "POST", "/pages/extract");
        req.setContextPath("");
        org.springframework.mock.web.MockHttpServletResponse resp = new org.springframework.mock.web.MockHttpServletResponse();
        Map<String, String> before = driver.fingerprint();
        Object model = controller.processSubmit("1", String.valueOf(fx.b.datasetId), req, resp);
        assertEquals(null, model);
        assertEquals(EXTRACT_FORBIDDEN, resp.getRedirectedUrl(), "the export of site B's dataset was not refused");
        assertEquals(List.of(), IsolationDriver.changed(before, driver.fingerprint()));
    }

    @Test
    void extractOfTheOwnSitesDatasetIsNotRefusedByTheScopeCheck() throws Exception {
        IsolationFixture fx = fresh();
        at.ac.meduniwien.ophthalmology.libreclinica.controller.ExtractController controller =
                new at.ac.meduniwien.ophthalmology.libreclinica.controller.ExtractController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "dataSource", pool());
        MockHttpServletRequest first = boundSession(fx, IsolationFixture.INVESTIGATOR);
        MockHttpServletRequest req = driver.continued(first, "POST", "/pages/extract");
        req.setContextPath("");
        org.springframework.mock.web.MockHttpServletResponse resp = new org.springframework.mock.web.MockHttpServletResponse();
        try {
            controller.processSubmit("1", String.valueOf(fx.a.datasetId), req, resp);
        } catch (Exception | LinkageError e) {
            // the job set-up needs the scheduler and the extract properties, which this harness does not wire
        }
        assertTrue(!EXTRACT_FORBIDDEN.equals(resp.getRedirectedUrl()), "the own dataset was refused");
    }

    @Test
    void theMigrationLogFileIsForTheMigrationRolesAndForLogFileNamesOnly() throws Exception {
        IsolationFixture fx = fresh();
        at.ac.meduniwien.ophthalmology.libreclinica.controller.BatchCRFMigrationController controller =
                new at.ac.meduniwien.ophthalmology.libreclinica.controller.BatchCRFMigrationController();
        // an investigator: not a migration role
        MockHttpServletRequest inv = boundSession(fx, IsolationFixture.INVESTIGATOR);
        org.springframework.mock.web.MockHttpServletResponse denied = new org.springframework.mock.web.MockHttpServletResponse();
        controller.getLogFile("logFile_2026-01-01-010101AM.txt", driver.continued(inv, "GET", "/pages/forms/migrate/x/downloadLogFile"), denied);
        assertEquals(403, denied.getStatus());
        // a coordinator: a file name that is not a log file name is refused
        MockHttpServletRequest coord = boundSession(fx, IsolationFixture.COORDINATOR);
        for (String name : new String[] { "..", "..%2f..%2fetc%2fpasswd", "../datainfo.properties", "a.txt", "logFile_..txt" }) {
            org.springframework.mock.web.MockHttpServletResponse bad = new org.springframework.mock.web.MockHttpServletResponse();
            controller.getLogFile(name, driver.continued(coord, "GET", "/pages/forms/migrate/x/downloadLogFile"), bad);
            assertEquals(404, bad.getStatus(), name);
        }
    }

    @Test
    void ruleMetadataOfAnotherSitesStudyIsRefused() throws Exception {
        IsolationFixture fx = fresh();
        at.ac.meduniwien.ophthalmology.libreclinica.controller.RuleController controller =
                new at.ac.meduniwien.ophthalmology.libreclinica.controller.RuleController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "dataSource", pool());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        fx.a.userName(IsolationFixture.COORDINATOR), "x"));
        try {
            for (String oid : new String[] { fx.b.oid, IsolationFixture.text(DATA_SOURCE, "SELECT oc_oid FROM study WHERE study_id = 1") }) {
                org.springframework.mock.web.MockHttpServletResponse resp = new org.springframework.mock.web.MockHttpServletResponse();
                Object view = controller.studyMetadata(new org.springframework.ui.ExtendedModelMap(),
                        driver.request("GET", "/x", fx.a.userName(IsolationFixture.COORDINATOR)).getSession(), oid, resp);
                assertEquals(403, resp.getStatus(), "metadata of " + oid + " was served to a site coordinator");
                assertEquals(null, view);
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
