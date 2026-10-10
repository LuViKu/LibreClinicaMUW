/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import at.ac.meduniwien.ophthalmology.libreclinica.testsupport.ProductionMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/admin/studies}: every study with its sites nested,
 * removed ones included, for a system administrator whatever their own
 * study bindings; refused to everyone else.
 */
class StudiesAdminApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String OID = "S_ADMINLIST_IT";

    @BeforeAll
    static void seed() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection()) {
            int study = insertStudy(c, null, "adminlist-it", "Admin list IT", OID, 5);
            insertStudy(c, study, "adminlist-it-z", "Zeta site", OID + "_Z", 7);
            insertStudy(c, study, "adminlist-it-a", "Alpha site", OID + "_A", 1);
        }
    }

    @Test
    void aSystemAdministratorSeesEveryStudyWithItsSitesNested() throws Exception {
        String me = "$[?(@.oid == '" + OID + "')]";
        mockMvc().perform(get("/api/v1/admin/studies").session(sysadminWithoutBindings()))
                .andExpect(status().isOk())
                // The seeded Default Study and the removed IT study, both at top level.
                .andExpect(jsonPath("$[*].oid", hasItem("S_DEFAULTS1")))
                .andExpect(jsonPath(me + ".status", contains("REMOVED")))
                .andExpect(jsonPath(me + ".name", contains("Admin list IT")))
                .andExpect(jsonPath(me + ".uniqueIdentifier", contains("adminlist-it")))
                .andExpect(jsonPath(me + ".principalInvestigator", contains("PI adminlist-it")))
                .andExpect(jsonPath(me + ".createdDate", contains(LocalDate.now().toString())))
                // Sites under their parent, by name, each with its own status.
                .andExpect(jsonPath(me + ".sites[*].oid", contains(OID + "_A", OID + "_Z")))
                .andExpect(jsonPath(me + ".sites[*].status", contains("AVAILABLE", "AUTO_REMOVED")))
                .andExpect(jsonPath(me + ".sites[*].parentOid", contains(OID, OID)))
                // A site never appears at top level.
                .andExpect(jsonPath("$[*].oid", not(hasItem(OID + "_A"))))
                .andExpect(jsonPath("$[?(@.oid == 'S_DEFAULTS1')].parentOid", contains((Object) null)))
                .andExpect(jsonPath("$[?(@.oid == '" + OID + "')].sites[0].sites", contains(empty())));
    }

    @Test
    void anyoneElseIsRefused() throws Exception {
        MockHttpSession dm = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(4);
        ub.setName("datamanager");
        dm.setAttribute("userBean", ub);
        mockMvc().perform(get("/api/v1/admin/studies").session(dm))
                .andExpect(status().isForbidden());
        mockMvc().perform(get("/api/v1/admin/studies").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    private MockMvc mockMvc() {
        return ProductionMvc.standalone(new StudiesAdminApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** A sysadmin session for an account with no study binding and no active study. */
    private static MockHttpSession sysadminWithoutBindings() {
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(9901);
        ub.setName("unbound_admin");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        return session;
    }

    private static int insertStudy(Connection c, Integer parent, String uid, String name, String oid, int statusId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO study (parent_study_id, unique_identifier, name, summary, date_created, owner_id, "
                        + "type_id, status_id, old_status_id, principal_investigator, protocol_type, sponsor, oc_oid) "
                        + "VALUES (?, ?, ?, '', now(), 1, 1, ?, 1, ?, 'observational', 'MUW', ?) "
                        + "RETURNING study_id")) {
            if (parent == null) ps.setNull(1, java.sql.Types.INTEGER); else ps.setInt(1, parent);
            ps.setString(2, uid);
            ps.setString(3, name);
            ps.setInt(4, statusId);
            ps.setString(5, "PI " + uid);
            ps.setString(6, oid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
