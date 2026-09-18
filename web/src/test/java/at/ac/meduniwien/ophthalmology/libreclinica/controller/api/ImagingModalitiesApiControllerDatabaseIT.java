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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;

/**
 * P3.4 — a study's imaging catalogue, maintained through the API.
 *
 * <p>The point of the catalogue is that onboarding a study needs no code
 * change, so what matters here is that an administrator can actually do the
 * whole job: add an acquisition, say which device performs it and which CRF box
 * it ticks, correct a mistake, and retire one.
 *
 * <p>Two refusals carry weight. A binding to an item that does not exist is
 * rejected, because such a binding does not fail loudly later — it silently
 * stops ticking, which on a form reads exactly like a modality that was not
 * performed. And retiring is a status change: files filed under a modality keep
 * naming it, and an audit row explaining a CRF value has to stay resolvable
 * after somebody tidies the list.
 */
@SuppressWarnings("null")
class ImagingModalitiesApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final String BASE = "/api/v1/studies/" + STUDY_OID + "/imaging-modalities";

    /** An item the demo seed really has, so a binding can be accepted. */
    private static final String REAL_ITEM_OID = "I_BLOOD_PRESSURE_SYS";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void cleanUp() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_table = 'imaging_modality'");
        exec("DELETE FROM imaging_modality WHERE code LIKE 'IT_%'");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ImagingModalitiesApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sessionAs(Role role, boolean sysAdmin) {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        if (sysAdmin) ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid(STUDY_OID);
        s.setAttribute("study", study);
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(role);
        s.setAttribute("userRole", r);
        return s;
    }

    private MockHttpSession dm() {
        return sessionAs(Role.STUDYDIRECTOR, false);
    }

    private int createModality(String code) throws Exception {
        MvcResult res = mockMvc().perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"labelDe\":\"Testgerät\","
                                + "\"labelEn\":\"Test device\",\"device\":\"testcam\","
                                + "\"kindsAccepted\":\"image,dicom\",\"lateralityRequired\":true,"
                                + "\"ordinal\":1}")
                        .session(dm()))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(res.getResponse().getContentAsString()).get("id").asInt();
    }

    private int auditCount(int type) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ?")) {
            ps.setInt(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /* ---------------- the gate ---------------- */

    @Test
    void anUnauthenticatedCallerSeesNothing() throws Exception {
        mockMvc().perform(get(BASE).session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anInvestigatorMayReadButNotChangeTheCatalogue() throws Exception {
        MockHttpSession investigator = sessionAs(Role.INVESTIGATOR, false);
        // Reading is harmless: the catalogue names devices, not patients.
        mockMvc().perform(get(BASE).session(investigator)).andExpect(status().isOk());
        // Changing it changes what the platform writes into CRFs.
        mockMvc().perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT_X\",\"labelDe\":\"x\",\"labelEn\":\"x\"}")
                .session(investigator))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStudyIsNotFound() throws Exception {
        mockMvc().perform(get("/api/v1/studies/S_NOPE/imaging-modalities").session(dm()))
                .andExpect(status().isNotFound());
    }

    /* ---------------- the whole job an administrator has to do ---------------- */

    @Test
    void anAdministratorCanAddAnAcquisitionAndSayWhatItTicks() throws Exception {
        int id = createModality("IT_CAM");
        assertTrue(auditCount(AuditTypeIds.IMAGING_MODALITY_CREATED) >= 1);

        mockMvc().perform(put(BASE + "/" + id + "/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"performed\",\"laterality\":\"OU\",\"itemOid\":\""
                                + REAL_ITEM_OID + "\",\"performedValue\":\"1\"}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings[0].role").value("performed"))
                .andExpect(jsonPath("$.bindings[0].itemOid").value(REAL_ITEM_OID));

        // Which box a camera ticks changed; "why did this value appear" has to
        // be answerable later.
        assertTrue(auditCount(AuditTypeIds.IMAGING_MODALITY_BINDING_CHANGED) >= 1);

        mockMvc().perform(get(BASE).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modalities[?(@.code=='IT_CAM')]").isNotEmpty());
    }

    @Test
    void settingTheSameRoleAndEyeTwiceCorrectsItRatherThanFailing() throws Exception {
        int id = createModality("IT_CAM");
        String body = "{\"role\":\"performed\",\"laterality\":\"OU\",\"itemOid\":\"";
        mockMvc().perform(put(BASE + "/" + id + "/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body + REAL_ITEM_OID + "\"}").session(dm()))
                .andExpect(status().isOk());
        // An admin fixing a typo should not have to delete the old one first.
        mockMvc().perform(put(BASE + "/" + id + "/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body + "I_CONSENT_SIGNED\"}").session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings.length()").value(1))
                .andExpect(jsonPath("$.bindings[0].itemOid").value("I_CONSENT_SIGNED"));
    }

    @Test
    void aModalityCanBeCorrected() throws Exception {
        int id = createModality("IT_CAM");
        mockMvc().perform(put(BASE + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"IT_CAM\",\"labelDe\":\"Geändert\",\"labelEn\":\"Changed\","
                                + "\"device\":\"othercam\",\"kindsAccepted\":\"e2e\","
                                + "\"lateralityRequired\":false,\"ordinal\":4}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.device").value("othercam"))
                .andExpect(jsonPath("$.kindsAccepted").value("e2e"))
                .andExpect(jsonPath("$.ordinal").value(4));
        assertTrue(auditCount(AuditTypeIds.IMAGING_MODALITY_UPDATED) >= 1);
    }

    @Test
    void aBindingCanBeRemoved() throws Exception {
        int id = createModality("IT_CAM");
        MvcResult res = mockMvc().perform(put(BASE + "/" + id + "/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"performed\",\"itemOid\":\"" + REAL_ITEM_OID + "\"}")
                        .session(dm()))
                .andExpect(status().isOk()).andReturn();
        int bindingId = JSON.readTree(res.getResponse().getContentAsString())
                .get("bindings").get(0).get("id").asInt();

        mockMvc().perform(delete(BASE + "/" + id + "/bindings/" + bindingId).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bindings.length()").value(0));
    }

    /* ---------------- retiring ---------------- */

    /**
     * Retiring keeps the row. Files filed under a modality keep naming it, and
     * an audit row explaining a CRF value has to stay resolvable after somebody
     * tidies the catalogue.
     */
    @Test
    void retiringAModalityKeepsTheRow() throws Exception {
        int id = createModality("IT_CAM");
        mockMvc().perform(delete(BASE + "/" + id).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusId").value(5));

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status_id FROM imaging_modality WHERE imaging_modality_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the row must survive so its history stays readable");
                assertEquals(5, rs.getInt(1));
            }
        }
        mockMvc().perform(delete(BASE + "/" + id).session(dm()))
                .andExpect(status().isConflict());
    }

    /* ---------------- refusals ---------------- */

    /**
     * The important one. A binding to an item that is not there does not fail
     * loudly at write time — it silently stops ticking, and an un-ticked box
     * reads as "this modality was not performed".
     */
    @Test
    void aBindingToAnItemThatDoesNotExistIsRefused() throws Exception {
        int id = createModality("IT_CAM");
        mockMvc().perform(put(BASE + "/" + id + "/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"performed\",\"itemOid\":\"I_NO_SUCH_ITEM_ANYWHERE\"}")
                        .session(dm()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownRoleOrEyeIsRefused() throws Exception {
        int id = createModality("IT_CAM");
        mockMvc().perform(put(BASE + "/" + id + "/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"whatever\",\"itemOid\":\"" + REAL_ITEM_OID + "\"}")
                .session(dm()))
                .andExpect(status().isBadRequest());
        mockMvc().perform(put(BASE + "/" + id + "/bindings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"performed\",\"laterality\":\"XX\",\"itemOid\":\""
                        + REAL_ITEM_OID + "\"}")
                .session(dm()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownFileKindIsRefused() throws Exception {
        mockMvc().perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT_BAD\",\"labelDe\":\"x\",\"labelEn\":\"x\","
                        + "\"kindsAccepted\":\"image,hologram\"}")
                .session(dm()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aDuplicateCodeInTheSameStudyConflicts() throws Exception {
        createModality("IT_CAM");
        mockMvc().perform(post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"it_cam\",\"labelDe\":\"x\",\"labelEn\":\"x\"}")
                .session(dm()))
                // Compared case-insensitively: IT_CAM and it_cam are one code,
                // and letting both exist would make which one ticks arbitrary.
                .andExpect(status().isConflict());
    }

    @Test
    void aModalityOfAnotherStudyIsNotReachableThroughThisOne() throws Exception {
        int id = createModality("IT_CAM");
        mockMvc().perform(put("/api/v1/studies/S_NOPE/imaging-modalities/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"IT_CAM\",\"labelDe\":\"x\",\"labelEn\":\"x\"}")
                .session(dm()))
                .andExpect(status().isNotFound());
    }
}
