/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.RetinalArtifactStorageService;

/**
 * Blinding fails closed on screen, the way it already did on export.
 *
 * <p>A trial arm that cannot be looked up used to read as "not the hidden
 * arm", so a database error showed the model's reading of an eye to a
 * clinician the trial had randomised not to see it. That is an unblinding
 * event whether or not a file moved. Now an unanswerable question withholds
 * from a treating role and changes nothing for anyone else — the data manager
 * who is allowed to see AI output still does.
 */
class RetinalJobAccessTest {

    private static RetinalJobAccess withBrokenDatabase() throws SQLException {
        DataSource ds = Mockito.mock(DataSource.class);
        Mockito.when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        return new RetinalJobAccess(ds, Mockito.mock(RetinalArtifactStorageService.class),
                new StudyResourceAccess(ds, Mockito.mock(SiteVisibilityFilter.class)));
    }

    private static MockHttpSession sessionAs(Role role) {
        MockHttpSession s = new MockHttpSession();
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(role);
        s.setAttribute("userRole", r);
        return s;
    }

    @Test
    void anUnanswerableArmLookupReadsAsHidden() throws Exception {
        RetinalJobAccess.JobRow row = new RetinalJobAccess.JobRow();
        row.jobId = 42;
        assertEquals(AiArmPolicy.ARM_HIDDEN, withBrokenDatabase().armForJobRow(row));
    }

    @Test
    void aTreatingClinicianIsThenWithheldFrom() throws Exception {
        RetinalJobAccess.JobRow row = new RetinalJobAccess.JobRow();
        String arm = withBrokenDatabase().armForJobRow(row);
        assertTrue(AiArmPolicy.maskAiFor(arm, sessionAs(Role.INVESTIGATOR)),
                "the physician making the treatment decision must not see AI output on a failed lookup");
    }

    @Test
    void aNonTreatingRoleIsUnaffected() throws Exception {
        RetinalJobAccess.JobRow row = new RetinalJobAccess.JobRow();
        String arm = withBrokenDatabase().armForJobRow(row);
        assertFalse(AiArmPolicy.maskAiFor(arm, sessionAs(Role.STUDYDIRECTOR)),
                "failing closed narrows what a blinded role sees; it does not blind everyone");
    }
}
