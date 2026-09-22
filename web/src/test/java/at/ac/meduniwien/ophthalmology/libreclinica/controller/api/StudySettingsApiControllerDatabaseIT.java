/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.study.StudySettingService;

/**
 * P3.5 — administering what a study does.
 *
 * <p>The response separates what a study has <em>set</em> from what it
 * currently <em>resolves to</em>, and that distinction is the point: an unset
 * key means "as before", and an administrator about to change something has to
 * see which of the two they are looking at. A panel showing only the effective
 * value would make every study look configured.
 */
@SuppressWarnings("null")
class StudySettingsApiControllerDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final String STUDY_OID = "S_DEFAULTS1";
    private static final String BASE = "/api/v1/studies/" + STUDY_OID + "/settings";
    private static final int STUDY_ID = 1;

    @AfterEach
    void cleanUp() throws Exception {
        exec("DELETE FROM audit_log_event WHERE audit_table = 'study_setting'");
        exec("DELETE FROM study_setting WHERE study_id = " + STUDY_ID);
        exec("DELETE FROM study_item_binding WHERE study_id = " + STUDY_ID);
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new StudySettingsApiController(DATA_SOURCE))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sessionAs(Role role) {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        s.setAttribute("study", study);
        StudyUserRoleBean r = new StudyUserRoleBean();
        r.setRole(role);
        s.setAttribute("userRole", r);
        return s;
    }

    private MockHttpSession dm() {
        return sessionAs(Role.STUDYDIRECTOR);
    }

    private int auditRows() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM audit_log_event WHERE audit_log_event_type_id = ?")) {
            ps.setInt(1, AuditTypeIds.STUDY_SETTING_CHANGED);
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
    void anInvestigatorMayReadButNotChange() throws Exception {
        MockHttpSession inv = sessionAs(Role.INVESTIGATOR);
        mockMvc().perform(get(BASE).session(inv)).andExpect(status().isOk());
        mockMvc().perform(put(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"settings\":{\"inference.enabled\":\"false\"}}")
                .session(inv))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStudyIsNotFound() throws Exception {
        mockMvc().perform(get("/api/v1/studies/S_NOPE/settings").session(dm()))
                .andExpect(status().isNotFound());
    }

    /* ---------------- set vs resolved ---------------- */

    /**
     * Nothing is set, so every key shows a null value beside the answer the
     * platform will actually use. An administrator seeing only the latter
     * would think the study had been configured.
     */
    @Test
    void anUnsetKeyShowsNoValueButStillResolves() throws Exception {
        mockMvc().perform(get(BASE).session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings[?(@.key=='inference.enabled')].value")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.settings[?(@.key=='inference.enabled')].resolved")
                        .value(org.hamcrest.Matchers.hasItem("true")));
    }

    @Test
    void settingAKeyShowsUpAsBothSetAndResolved() throws Exception {
        mockMvc().perform(put(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"inference.enabled\":\"false\"}}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings[?(@.key=='inference.enabled')].value")
                        .value(org.hamcrest.Matchers.hasItem("false")))
                .andExpect(jsonPath("$.settings[?(@.key=='inference.enabled')].resolved")
                        .value(org.hamcrest.Matchers.hasItem("false")));
        assertTrue(auditRows() >= 1, "a study that stops running inference must leave a trail");
    }

    /**
     * Clearing restores "as before". It must not store today's default, which
     * would freeze the study against a future change to it.
     */
    @Test
    void clearingAKeyRestoresTheDefaultRatherThanFreezingIt() throws Exception {
        mockMvc().perform(put(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"settings\":{\"inference.enabled\":\"false\"}}")
                .session(dm()))
                .andExpect(status().isOk());
        mockMvc().perform(put(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":{\"inference.enabled\":\"\"}}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings[?(@.key=='inference.enabled')].value")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.settings[?(@.key=='inference.enabled')].resolved")
                        .value(org.hamcrest.Matchers.hasItem("true")));

        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM study_setting WHERE study_id = ? AND setting_key = ?")) {
            ps.setInt(1, STUDY_ID);
            ps.setString(2, StudySettingService.INFERENCE_ENABLED);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(1));
            }
        }
    }

    /* ---------------- item bindings ---------------- */

    @Test
    void aStudyCanNameItsOwnItems() throws Exception {
        mockMvc().perform(put(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemBindings\":{\"visit.crf\":\"F_MY_VISIT\"}}")
                        .session(dm()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemBindings['visit.crf']").value("F_MY_VISIT"));
    }

    /* ---------------- refusals ---------------- */

    /**
     * A typo quietly accepted reads, forever after, as a setting that does
     * nothing — and nobody would look for it.
     */
    @Test
    void anUnknownSettingKeyIsRefused() throws Exception {
        mockMvc().perform(put(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"settings\":{\"inference.enbaled\":\"false\"}}")
                .session(dm()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownKeyDoesNotApplyTheValidOnesAlongsideIt() throws Exception {
        mockMvc().perform(put(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"settings\":{\"inference.enabled\":\"false\",\"nonsense\":\"x\"}}")
                .session(dm()))
                .andExpect(status().isBadRequest());
        // Validation runs before anything is written, so a rejected batch
        // leaves the study exactly as it was.
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM study_setting WHERE study_id = ?")) {
            ps.setInt(1, STUDY_ID);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                org.junit.jupiter.api.Assertions.assertEquals(0, rs.getInt(1));
            }
        }
    }
}
