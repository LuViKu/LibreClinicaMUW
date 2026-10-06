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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.login.ChangeStudyServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ReassignStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.UpdateStudySubjectServlet;
import at.ac.meduniwien.ophthalmology.libreclinica.control.submit.FindSubjectsDataServlet;

/**
 * FINDINGS: requests of a site-A user that reach site-B data or change site-B
 * rows through the heritage servlets. Every test here fails today and states
 * what the servlet must do instead; when a servlet is fixed, its probe moves
 * from {@link IsolationProbes#LEAKS} to the passing set in
 * {@link CrossSiteIsolationLegacyDatabaseIT} (the probe table is shared).
 *
 * <p>Kept out of the default run, which must stay green: it runs only with
 * {@code -Disolation.leaks=true} (or {@code -Dgroups=isolation-leak} together
 * with it). The positive controls of each probe are in
 * {@link CrossSiteIsolationLegacyDatabaseIT}, so a failure here is a leak and
 * not a request that never worked.
 *
 * <h2>Causes (file:line in {@code web/src/main/java/.../control})</h2>
 * <ol>
 * <li><b>ChangeStudy</b> ({@code login/ChangeStudyServlet.java}): "submit" takes {@code studyId} from the request
 *     (line 170) and binds that study (line 208) without comparing it with the role "confirm" stored (line 155,
 *     used at 276-277). A site user confirms its own site, submits the parent study, a sibling site or another
 *     protocol, and works there with its own role. {@code UserAccountDAO.findStudyByUser} (lines 738-802) also
 *     offers the parent and the sibling sites in the first place.</li>
 * <li><b>No record-level check</b>: {@code managestudy/ViewEventCRFServlet} (50-75), {@code ViewItemAuditLogServlet}
 *     (31-59), {@code PrintDataEntryServlet} (76-121), {@code extract/ShowFileServlet} (53-61),
 *     {@code ViewStudyUserServlet} (32-64), {@code RemoveStudyUserRoleServlet} (62-107),
 *     {@code SetStudyUserRoleServlet} (60-132), {@code UpdateStudySubjectServlet} {@code action=show} (103) and
 *     the whole Remove/Restore/Delete family of study subjects, events and event CRFs.</li>
 * <li><b>A check that tests the parent study</b>: {@code EnterDataForStudyEventServlet} (94-103) and
 *     {@code ViewEventCRFContentServlet} (68-75) ask whether the event's definition belongs to the session's
 *     study, and for a site that is the parent, which every site's events satisfy.</li>
 * <li><b>A check that forwards and goes on</b>: {@code SecureController.checkRoleByUserAndStudy} (1190-1202)
 *     forwards to the main menu and returns, so {@code RemoveStudySubjectServlet} (101 then 128-161) carries on
 *     and writes. The user sees the menu; the rows are changed.</li>
 * </ol>
 */
@Tag("isolation-leak")
@EnabledIfSystemProperty(named = "isolation.leaks", matches = "true")
class CrossSiteIsolationLegacyLeaksDatabaseIT extends AbstractIsolationIT {

    static Stream<Arguments> leakingAttacks() {
        return attacks(true);
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("leakingAttacks")
    void aSiteUserCannotReadOrChangeAnotherSitesRecords(String name, String role) throws Exception {
        IsolationProbes.Probe probe = probe(name);
        IsolationFixture fx = probe.writes() ? fresh() : shared;
        Verdict v = run(probe, fx, role, fx.b);
        assertTrue(v.clean(), name + " as " + role + " reached site B: " + v.describe());
    }

    // ---- ChangeStudy ---------------------------------------------------------------------------------

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
     * Confirming the user's own site stores {@code studyWithRole}; the submit that follows takes
     * {@code studyId} from the request without comparing it with that role.
     */
    @ParameterizedTest(name = "to {0}")
    @MethodSource("otherStudies")
    void changeStudyConfirmedForTheOwnSiteCannotBeSubmittedForAnotherStudy(String target) throws Exception {
        IsolationFixture fx = fresh();
        int studyId = studyIdOf(target, fx);
        MockHttpServletRequest first = driver.request("POST", "/ChangeStudy", fx.a.userName(IsolationFixture.INVESTIGATOR),
                "action", "confirm", "studyId", String.valueOf(fx.a.studyId));
        driver.run(new ChangeStudyServlet(), first);
        MockHttpServletRequest second = driver.continued(first, "POST", "/ChangeStudy", "action", "submit", "studyId",
                String.valueOf(studyId));
        driver.run(new ChangeStudyServlet(), second);

        assertEquals(fx.a.studyId, ((StudyBean) first.getSession().getAttribute("study")).getId(),
                "the session now works in " + target);
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

    /**
     * The role checks of every legacy servlet run against the session's study and role, which the switch
     * replaced: a site coordinator who switched to study 102 manages that study's users.
     */
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
        assertTrue(list.refused(), "a site coordinator lists the users of study 102: " + list);
    }

    private static int studyIdOf(String target, IsolationFixture fx) {
        return switch (target) {
            case "site B" -> fx.b.studyId;
            case "the parent study" -> fx.parentStudyId;
            default -> 102;
        };
    }

    // ---- multi-step flows ---------------------------------------------------------------------------------

    /**
     * {@code action=show} loads the study subject named by {@code id} into the session; the later
     * confirm and submit steps work on that session copy.
     */
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

    /** {@code studyId} is any study: a coordinator can push the site's own subject into another site. */
    @Test
    void reassignStudySubjectCannotMoveASubjectIntoAnotherSite() throws Exception {
        IsolationFixture fx = fresh();
        MockHttpServletRequest req = driver.request("POST", "/ReassignStudySubject",
                fx.a.userName(IsolationFixture.COORDINATOR), "id", String.valueOf(fx.a.studySubjectId), "action",
                "submit", "studyId", String.valueOf(fx.b.studyId));
        driver.run(new ReassignStudySubjectServlet(), req);

        assertEquals(fx.a.studyId, IsolationFixture.query(DATA_SOURCE,
                "SELECT study_id FROM study_subject WHERE study_subject_id = " + fx.a.studySubjectId),
                "site A's subject now belongs to site B");
    }
}
