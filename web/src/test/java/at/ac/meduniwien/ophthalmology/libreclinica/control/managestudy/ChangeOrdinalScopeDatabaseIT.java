/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

import jakarta.servlet.http.HttpServlet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;

/**
 * The reorder actions against the real schema: the director of study 1 cannot
 * move study 102's event definitions, or the CRFs within one, and the rows keep
 * their order. Moving study 1's own is covered by
 * {@code LegacyGetWritesPostOnlyDatabaseIT}.
 */
class ChangeOrdinalScopeDatabaseIT extends AbstractApiControllerDatabaseIT {

    /** Study 1's "V1 Inclusion" and study 102's imaging visit, with its Demographics CRF. */
    private static final int OWN_DEFINITION = 1;
    private static final int OTHER_STUDY_DEFINITION = 10;
    private static final int OTHER_STUDY_DEFINITION_CRF = 10;

    private LegacyServletHarness harness;

    @BeforeEach
    void setUp() {
        harness = new LegacyServletHarness(DATA_SOURCE);
    }

    @Test
    void anotherStudysEventDefinitionIsNotMoved() throws Exception {
        update("UPDATE study_event_definition SET ordinal = 2 WHERE study_event_definition_id = " + OTHER_STUDY_DEFINITION);
        int own = definitionOrdinal(OWN_DEFINITION);
        try {
            MockHttpServletResponse resp = run(new ChangeDefinitionOrdinalServlet(), "/ChangeDefinitionOrdinal",
                    "current", String.valueOf(OTHER_STUDY_DEFINITION), "previous", String.valueOf(OWN_DEFINITION));

            assertEquals(2, definitionOrdinal(OTHER_STUDY_DEFINITION), "study 102's definition kept its place");
            assertEquals(own, definitionOrdinal(OWN_DEFINITION));
            assertEquals("/MainMenu", resp.getForwardedUrl());
        } finally {
            update("UPDATE study_event_definition SET ordinal = 1 WHERE study_event_definition_id = " + OTHER_STUDY_DEFINITION);
            update("UPDATE study_event_definition SET ordinal = " + own + " WHERE study_event_definition_id = " + OWN_DEFINITION);
        }
    }

    @Test
    void theCrfsOfAnotherStudysEventDefinitionAreNotMoved() throws Exception {
        // A second CRF in study 102's definition, so that there is a pair to swap.
        int version = queryInt("SELECT MIN(crf_version_id) FROM crf_version WHERE crf_id = 2");
        int second = queryInt("INSERT INTO event_definition_crf (study_event_definition_id, study_id, crf_id, required_crf,"
                + " double_entry, default_version_id, status_id, owner_id, date_created, ordinal, source_data_verification_code)"
                + " VALUES (" + OTHER_STUDY_DEFINITION + ", 102, 2, false, false, " + version + ", 1, 1, now(), 2, 1)"
                + " RETURNING event_definition_crf_id");
        update("UPDATE event_definition_crf SET ordinal = 1 WHERE event_definition_crf_id = " + OTHER_STUDY_DEFINITION_CRF);
        try {
            MockHttpServletResponse resp = run(new ChangeDefinitionCRFOrdinalServlet(), "/ChangeDefinitionCRFOrdinal",
                    "current", String.valueOf(second), "previous", String.valueOf(OTHER_STUDY_DEFINITION_CRF),
                    "id", String.valueOf(OTHER_STUDY_DEFINITION), "currentOrdinal", "2", "previousOrdinal", "1");

            assertEquals(1, crfOrdinal(OTHER_STUDY_DEFINITION_CRF), "the CRFs kept their order");
            assertEquals(2, crfOrdinal(second));
            assertEquals("/MainMenu", resp.getForwardedUrl());
        } finally {
            update("DELETE FROM event_definition_crf WHERE event_definition_crf_id = " + second);
            update("UPDATE event_definition_crf SET ordinal = 1 WHERE event_definition_crf_id = " + OTHER_STUDY_DEFINITION_CRF);
        }
    }

    /** Guards the fixture: the other study's rows are study 102's, and the director is study 1's. */
    @Test
    void theFixtureRowsExist() throws Exception {
        assertEquals(1, queryInt("SELECT study_id FROM study_event_definition WHERE study_event_definition_id = " + OWN_DEFINITION));
        assertEquals(102, queryInt("SELECT study_id FROM study_event_definition"
                + " WHERE study_event_definition_id = " + OTHER_STUDY_DEFINITION));
        assertEquals(OTHER_STUDY_DEFINITION, queryInt("SELECT study_event_definition_id FROM event_definition_crf"
                + " WHERE event_definition_crf_id = " + OTHER_STUDY_DEFINITION_CRF));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_dm' AND study_id = 102"));
        assertTrue(queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_dm' AND study_id = 1"
                + " AND role_name = 'director'") > 0);
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** manual_dm, the director of study 1, with the roles login would load. */
    private static UserAccountBean director() {
        UserAccountDAO dao = new UserAccountDAO(DATA_SOURCE);
        UserAccountBean ub = dao.findByUserName("manual_dm");
        for (StudyUserRoleBean role : dao.findAllRolesByUserName("manual_dm")) {
            ub.addRole(role);
        }
        ub.setPasswdTimestamp(new Date());
        return ub;
    }

    private MockHttpServletResponse run(HttpServlet servlet, String path, String... params) throws Exception {
        MockHttpServletRequest req = harness.request("POST", path, director());
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return harness.run(servlet, req);
    }

    private static int definitionOrdinal(int id) throws SQLException {
        return queryInt("SELECT ordinal FROM study_event_definition WHERE study_event_definition_id = " + id);
    }

    private static int crfOrdinal(int id) throws SQLException {
        return queryInt("SELECT ordinal FROM event_definition_crf WHERE event_definition_crf_id = " + id);
    }

    private static int queryInt(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "no row for " + sql);
            return rs.getInt(1);
        }
    }

    private static void update(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
