/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;

/**
 * P2-7 — the trial-blinding gate, as a truth table.
 *
 * <p>The nAMD study measures whether seeing AI-derived fluid volumes changes
 * what a treating clinician does. Showing them to a clinician treating a
 * control-arm subject does not produce a wrong value; it invalidates the
 * result the study exists to produce, and nothing on the screen would say so.
 *
 * <p>The SPA hides the panels too, but that is bypassable with a pasted URL.
 * This predicate is the enforcement, so its shape is pinned here rather than
 * only exercised incidentally through the endpoints that call it.
 *
 * <p>P3.0 — the predicate itself moved to {@link AiArmPolicy}, which the
 * subject controller's cohort-change guard now shares. These tests stayed
 * here: what they describe is the retinal surface's blinding behaviour, which
 * is what breaks if the rule drifts.
 */
class RetinalBlindingGateTest extends AbstractApiControllerTest {
    // Extends the shared base: the heritage Role beans need the resource
    // bundles it initialises, and blow up with an NPE without them.


    private static MockHttpSession sessionAs(Role role) {
        MockHttpSession s = new MockHttpSession();
        if (role != null) {
            StudyUserRoleBean r = new StudyUserRoleBean();
            r.setRole(role);
            s.setAttribute("userRole", r);
        }
        return s;
    }

    /* ---------------- who counts as treating ---------------- */

    @Test
    void investigatorsAndCoordinatorsAreTreatingRoles() {
        assertTrue(AiArmPolicy.isTreatingRole(sessionAs(Role.INVESTIGATOR)));
        assertTrue(AiArmPolicy.isTreatingRole(sessionAs(Role.COORDINATOR)));
    }

    /**
     * A data manager or monitor is not treating the patient, so seeing the AI
     * output cannot influence a treatment decision.
     */
    @Test
    void dataManagementRolesAreNotTreatingRoles() {
        assertFalse(AiArmPolicy.isTreatingRole(sessionAs(Role.STUDYDIRECTOR)));
        assertFalse(AiArmPolicy.isTreatingRole(sessionAs(Role.MONITOR)));
        assertFalse(AiArmPolicy.isTreatingRole(sessionAs(Role.ADMIN)));
    }

    @Test
    void aSessionWithNoRoleIsNotTreating() {
        assertFalse(AiArmPolicy.isTreatingRole(sessionAs(null)));
    }

    /* ---------------- what gets masked ---------------- */

    @Test
    void aTreatingClinicianOnAHiddenArmSubjectSeesNoAiOutput() {
        assertTrue(AiArmPolicy.maskAiFor("AI_HIDDEN", sessionAs(Role.INVESTIGATOR)));
        assertTrue(AiArmPolicy.maskAiFor("AI_HIDDEN", sessionAs(Role.COORDINATOR)));
    }

    /** A data manager reviewing the same subject is not blinded. */
    @Test
    void nonTreatingRolesAreNotBlinded() {
        assertFalse(AiArmPolicy.maskAiFor("AI_HIDDEN", sessionAs(Role.STUDYDIRECTOR)));
        assertFalse(AiArmPolicy.maskAiFor("AI_HIDDEN", sessionAs(Role.MONITOR)));
    }

    @Test
    void theShownArmIsNeverMasked() {
        assertFalse(AiArmPolicy.maskAiFor("AI_SHOWN", sessionAs(Role.INVESTIGATOR)));
    }

    /**
     * A null arm is a subject of a study that does not randomise, or one
     * scanned before randomisation. Randomisation happens at enrolment, before
     * any scan exists, so a job-owning subject with no arm is outside the
     * trial rather than un-randomised inside it.
     */
    @Test
    void aSubjectWithNoArmIsNotMasked() {
        assertFalse(AiArmPolicy.maskAiFor(null, sessionAs(Role.INVESTIGATOR)));
    }

    /** The arm name is matched exactly — a near-miss must not silently unblind. */
    @Test
    void onlyTheExactHiddenArmNameMasks() {
        assertFalse(AiArmPolicy.maskAiFor("ai_hidden", sessionAs(Role.INVESTIGATOR)));
        assertFalse(AiArmPolicy.maskAiFor("AI_HIDDEN ", sessionAs(Role.INVESTIGATOR)));
    }
}
