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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.config.LaxParsingSpringLiquibase;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import liquibase.integration.spring.SpringLiquibase;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code lc-muw-2026-09-30-dde-second-pass-date.xml}: a clean second pass of
 * double data entry whose date the pass itself cleared gets it back, and
 * nothing else does.
 *
 * <p>Before 1.5.0-beta.16-muw, {@code DdeService.commitPass2} recorded the
 * clean pass (audit type 110, {@code date_validate_completed}) and then
 * cleared the date it had just set. The test puts event CRFs into that
 * state, and into the states the restore must leave alone, and runs
 * {@code master.xml} as a deployment would: on a database that has not had
 * the restore yet.
 */
class DdeSecondPassDateRestoreDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String RESTORE_CHANGESET = "2026-09-30-restore-dde-second-pass-date";

    @Test
    void aCleanSecondPassGetsBackTheDateItLost() throws Exception {
        // Double data entry on the V2 visits (event definition CRF 2).
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_definition_crf SET double_entry = true WHERE event_definition_crf_id = 2");
        // Event CRF 11: first pass complete, a clean second pass recorded, no date.
        recordSecondPass(11, "date_validate_completed", "now() - interval '1 hour'");
        // Event CRF 2: a clean pass, then one with mismatches that are not
        // reconciled yet.
        recordSecondPass(2, "date_validate_completed", "now() - interval '3 hours'");
        recordSecondPass(2, "mismatch_count", "now() - interval '2 hours'");
        // Event CRF 15: reopened and completed again after its clean pass.
        recordSecondPass(15, "date_validate_completed", "now() - interval '1 day'");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET date_completed = now() WHERE event_crf_id = 15");
        // Event CRF 5: a clean pass recorded, but the CRF has been removed.
        recordSecondPass(5, "date_validate_completed", "now() - interval '1 hour'");
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "UPDATE event_crf SET status_id = 7, date_completed = now() - interval '2 hours' "
                        + "WHERE event_crf_id = 5");
        assertFalse(listedForVerification().contains("11"), "the lost date keeps it from SDV");

        runTheDeploymentChangelog();

        assertNotNull(secondPassDate(11), "the clean second pass has its date again");
        assertEquals(1, restorationsAudited(11), "and the audit trail says so");
        assertTrue(listedForVerification().contains("11"), "so the CRF is complete for verification");

        assertNull(secondPassDate(2), "its last second pass found mismatches");
        assertNull(secondPassDate(15), "its first pass was completed again, after the second");
        assertNull(secondPassDate(5), "a removed CRF is left as it is");
        assertEquals(0, restorationsAudited(2) + restorationsAudited(15) + restorationsAudited(5));
    }

    /** A pass-2 commit as commitPass2 recorded it, at {@code when} (SQL). */
    private static void recordSecondPass(int eventCrfId, String entityName, String when)
            throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO audit_log_event (audit_log_event_type_id, audit_date, user_id, "
                        + "audit_table, entity_id, entity_name, old_value, new_value) VALUES ("
                        + AuditTypeIds.DDE_PASS2_COMMITTED + ", " + when + ", "
                        + ClinicalWriteFixtures.userId(DATA_SOURCE, "manual_crc") + ", 'event_crf', "
                        + eventCrfId + ", '" + entityName + "', '', 'x')");
    }

    /** Liquibase on a database the restore has not run on yet. */
    private static void runTheDeploymentChangelog() throws Exception {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "DELETE FROM databasechangelog WHERE id = '" + RESTORE_CHANGESET + "'");
        SpringLiquibase liquibase = new LaxParsingSpringLiquibase();
        liquibase.setDataSource(DATA_SOURCE);
        liquibase.setChangeLog("classpath:migration/master.xml");
        liquibase.setResourceLoader(new DefaultResourceLoader());
        liquibase.afterPropertiesSet();
    }

    private static Timestamp secondPassDate(int eventCrfId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT date_validate_completed FROM event_crf WHERE event_crf_id = ?")) {
            ps.setInt(1, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getTimestamp(1);
            }
        }
    }

    private static int restorationsAudited(int eventCrfId) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = ? "
                             + "AND audit_table = 'event_crf' AND entity_id = ? "
                             + "AND entity_name = 'date_validate_completed' AND user_id IS NULL "
                             + "AND reason_for_change IS NOT NULL")) {
            ps.setInt(1, AuditTypeIds.DDE_SECOND_PASS_DATE_RESTORED);
            ps.setInt(2, eventCrfId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<String> listedForVerification() throws Exception {
        SiteVisibilityFilter filter = new SiteVisibilityFilter(DATA_SOURCE);
        String body = MockMvcBuilders.standaloneSetup(new SdvApiController(DATA_SOURCE, filter))
                .build()
                .perform(get("/api/v1/sdv")
                        .session(ClinicalWriteFixtures.sessionAs(DATA_SOURCE, "manual_monitor")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> listed = new ArrayList<>();
        for (JsonNode row : new ObjectMapper().readTree(body)) {
            listed.add(row.get("eventCrfOid").asText());
        }
        return listed;
    }
}
