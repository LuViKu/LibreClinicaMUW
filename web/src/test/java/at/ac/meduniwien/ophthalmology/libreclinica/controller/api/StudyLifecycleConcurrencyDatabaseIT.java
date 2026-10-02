/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.insertStudy;
import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.intQuery;
import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.oldStatusOf;
import static at.ac.meduniwien.ophthalmology.libreclinica.controller.api.LifecycleFixtures.statusOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Two removals of the same study at once. The second request passes the
 * controller's "already removed" check before the first commits, then
 * waits on the study row. Once the first commits, the second must fail
 * with 409 and leave the status the first recorded: overwriting it with
 * "removed" would bring a locked study back available on restore.
 */
class StudyLifecycleConcurrencyDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static int study;

    @BeforeAll
    static void seed() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        try (Connection c = DATA_SOURCE.getConnection()) {
            study = insertStudy(c, null, "race-it", "Race IT", "S_RACE_IT", 6);
        }
    }

    @Test
    void aSecondConcurrentRemovalIsRefusedAndKeepsTheRecordedStatus() throws Exception {
        try (Connection first = DATA_SOURCE.getConnection()) {
            first.setAutoCommit(false);
            // The first removal's opening step, not yet committed: it holds the row.
            try (Statement s = first.createStatement()) {
                s.executeUpdate("UPDATE study SET old_status_id = status_id, status_id = 5 "
                        + "WHERE study_id = " + study);
            }

            CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> {
                ResourceBundleProvider.updateLocale(Locale.ENGLISH);
                try {
                    return mockMvc().perform(post("/api/v1/studies/S_RACE_IT/disable")
                                    .contentType("application/json").content("{\"reason\":\"twice\"}")
                                    .session(sysadminSession()))
                            .andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            waitUntilAnUpdateOfStudyIsBlocked();
            first.commit();
            assertEquals(409, second.get(30, TimeUnit.SECONDS));
        }
        assertEquals(5, statusOf("study", "study_id", study));
        assertEquals(6, oldStatusOf("study", "study_id", study), "the first removal recorded 'locked'");
    }

    private static void waitUntilAnUpdateOfStudyIsBlocked() throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (intQuery("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                    + "AND query LIKE 'UPDATE study SET old_status_id%'") > 0) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(false, "the second removal never reached the study row");
    }

    private static MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new StudiesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static MockHttpSession sysadminSession() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        return session;
    }
}
