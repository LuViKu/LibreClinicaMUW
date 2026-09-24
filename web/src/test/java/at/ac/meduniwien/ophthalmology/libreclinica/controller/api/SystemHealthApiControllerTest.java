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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpSession;

/**
 * DR-033 — the parts of the System Status health endpoints that need no
 * database: the sysadmin gate, and the arithmetic behind "days until the
 * disk is full". The database behaviour is pinned by
 * {@code SystemHealthDatabaseIT}.
 */
class SystemHealthApiControllerTest extends AbstractApiControllerTest {

    private SystemHealthApiController controller() {
        return new SystemHealthApiController(mockDataSource(), Mockito.mock(StorageUsageSampler.class));
    }

    @Test
    void anonymousIs401AndANonSysadminIs403() throws Exception {
        mockMvcFor(controller()).perform(get("/api/v1/admin/uploaders")
                        .session((MockHttpSession) emptySession()))
                .andExpect(status().isUnauthorized());
        mockMvcFor(controller()).perform(get("/api/v1/admin/storage")
                        .session((MockHttpSession) authenticatedSession(7, "physician", 1, "S_DEFAULTS1", "Default Study")))
                .andExpect(status().isForbidden());
    }

    @Test
    void daysUntilFullFollowsTheWeeksRate() {
        // 70 GiB less free space over 7 days = 10 GiB a day; 100 GiB left = 10 days.
        long gib = 1024L * 1024 * 1024;
        assertEquals(10.0, SystemHealthApiController.daysUntilFull(170 * gib, 100 * gib, 7.0), 1e-9);
    }

    @Test
    void noEstimateWhenSpaceDidNotShrink_orTheSpanIsTooShort_orItIsDecadesAway() {
        long gib = 1024L * 1024 * 1024;
        assertNull(SystemHealthApiController.daysUntilFull(100 * gib, 100 * gib, 7.0));
        assertNull(SystemHealthApiController.daysUntilFull(90 * gib, 100 * gib, 7.0));
        assertNull(SystemHealthApiController.daysUntilFull(170 * gib, 100 * gib, 0.5));
        // 1 MiB a week against 100 GiB free: tens of thousands of years
        assertNull(SystemHealthApiController.daysUntilFull(100 * gib + 1024 * 1024, 100 * gib, 7.0));
    }

    @Test
    void theEstimateIsRoundedDownToATenthOfADay() {
        // 3 units/day over 1 day, 10 left = 3.333… days → 3.3
        assertEquals(3.3, SystemHealthApiController.daysUntilFull(13, 10, 1.0), 1e-9);
    }
}
