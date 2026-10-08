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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * How {@link StudyParameters} resolves a parameter for a site, the study most
 * data-entry users work in, against a real database: the site's own value;
 * else its parent study's; else the legacy default, also when the value
 * cannot be read. A blank value counts as none.
 *
 * <p>A site of Default Study (study 1), and a parameter of this test's own,
 * are added. Each case removes the values it stores.
 */
@SuppressWarnings("resource") // Connection, PreparedStatement and ResultSet here are Mockito mocks; there is nothing to close
class StudyParametersResolutionDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String HANDLE = "lcMuwResolutionIt";

    private static int siteId;

    @BeforeAll
    static void addSiteAndParameter() throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE,
                "INSERT INTO study_parameter (study_parameter_id, handle, name, description, default_value, "
                        + "inheritable, overridable) VALUES ((SELECT MAX(study_parameter_id) + 1 FROM study_parameter), '"
                        + HANDLE + "', 'resolution IT', '', 'default', true, true)");
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO study (parent_study_id, unique_identifier, secondary_identifier, name, summary, "
                             + "date_created, owner_id, type_id, status_id, protocol_type, oc_oid) "
                             + "VALUES (1, 'S_PARAM_SITE', 'S_PARAM_SITE', 'Parameter site', '', "
                             + "now(), 1, 1, 1, 'observational', 'S_PARAM_SITE')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                siteId = keys.getInt(1);
            }
        }
    }

    @AfterEach
    void dropValues() throws SQLException {
        ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM study_parameter_value WHERE parameter = '" + HANDLE
                + "' OR study_id = " + siteId);
    }

    private static StudyBean site() {
        StudyBean site = new StudyDAO(DATA_SOURCE).findByPK(siteId);
        assertEquals(1, site.getParentStudyId(), "the site is a site of Default Study");
        return site;
    }

    @Test
    void aSiteTakesItsParentsValue() throws SQLException {
        store(1, HANDLE, "parent");

        assertEquals("parent", StudyParameters.value(DATA_SOURCE, site(), HANDLE, "default"));
    }

    @Test
    void aSitesOwnValueOverridesItsParents() throws SQLException {
        store(1, HANDLE, "parent");
        store(siteId, HANDLE, "site");

        assertEquals("site", StudyParameters.value(DATA_SOURCE, site(), HANDLE, "default"));
    }

    @Test
    void aBlankValueCountsAsNone() throws SQLException {
        store(1, HANDLE, "parent");
        store(siteId, HANDLE, "  ");

        assertEquals("parent", StudyParameters.value(DATA_SOURCE, site(), HANDLE, "default"));
    }

    @Test
    void withNoValueTheLegacyDefaultApplies() {
        assertEquals("default", StudyParameters.value(DATA_SOURCE, site(), HANDLE, "default"));
    }

    @Test
    void aValueThatCannotBeReadFallsBackToTheDefault() throws SQLException {
        DataSource broken = Mockito.mock(DataSource.class);
        Mockito.when(broken.getConnection()).thenThrow(new SQLException("database unreachable"));

        assertEquals("default", StudyParameters.value(broken, site(), HANDLE, "default"));
    }

    @Test
    void aSiteUserIsAskedForAReasonAsTheParentOrTheSiteSays() throws SQLException {
        String parentBefore = stored(1, StudyParameters.ADMIN_FORCED_REASON_FOR_CHANGE);
        try {
            // The legacy default forces a reason; the parent turning it off reaches the site.
            store(1, StudyParameters.ADMIN_FORCED_REASON_FOR_CHANGE, "false");
            assertFalse(StudyParameters.adminForcedReasonForChange(DATA_SOURCE, site()));

            store(siteId, StudyParameters.ADMIN_FORCED_REASON_FOR_CHANGE, "true");
            assertTrue(StudyParameters.adminForcedReasonForChange(DATA_SOURCE, site()));
        } finally {
            if (parentBefore == null) {
                ClinicalWriteFixtures.execute(DATA_SOURCE, "DELETE FROM study_parameter_value WHERE study_id = 1 "
                        + "AND parameter = '" + StudyParameters.ADMIN_FORCED_REASON_FOR_CHANGE + "'");
            } else {
                store(1, StudyParameters.ADMIN_FORCED_REASON_FOR_CHANGE, parentBefore);
            }
        }
    }

    private static String stored(int studyId, String handle) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM study_parameter_value WHERE study_id = ? AND parameter = ?")) {
            ps.setInt(1, studyId);
            ps.setString(2, handle);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Upsert {@code studyId}'s value of {@code handle}. */
    private static void store(int studyId, String handle, String value) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE study_parameter_value SET value = ? WHERE study_id = ? AND parameter = ?")) {
                ps.setString(1, value);
                ps.setInt(2, studyId);
                ps.setString(3, handle);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO study_parameter_value (study_parameter_value_id, study_id, value, parameter) "
                                + "VALUES ((SELECT COALESCE(MAX(study_parameter_value_id), 0) + 1 "
                                + "FROM study_parameter_value), ?, ?, ?)")) {
                    ps.setInt(1, studyId);
                    ps.setString(2, value);
                    ps.setString(3, handle);
                    ps.executeUpdate();
                }
            }
        }
    }
}
