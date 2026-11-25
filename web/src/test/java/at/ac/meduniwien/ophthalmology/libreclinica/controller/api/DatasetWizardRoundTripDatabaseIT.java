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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A saved dataset reopens as it was saved, and its filters do what the
 * preview says.
 *
 * <p>Until 2026-11 the wizard reopened every dataset with nothing selected
 * (the endpoint read the selection back and then answered without it),
 * without its filters (the answer carried none), and saving it then deleted
 * the stored filters. The "CRF completion date" flag was never stored. A
 * participant ID could not be filtered on at all: the filter offered CRF
 * items only.
 */
class DatasetWizardRoundTripDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<Integer> created = new ArrayList<>();
    private String eventOid;

    @BeforeEach
    void findAVisitDefinition() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT oc_oid FROM study_event_definition WHERE study_id = 1 AND status_id = 1 "
                             + "ORDER BY ordinal LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the seed should have a visit definition in study 1");
            eventOid = rs.getString(1);
        }
    }

    @AfterEach
    void removeDatasets() throws Exception {
        for (Integer id : created) {
            List<Integer> filters = new ArrayList<>();
            try (Connection c = DATA_SOURCE.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT filter_id FROM dataset_filter_map WHERE dataset_id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) filters.add(rs.getInt(1));
                }
            }
            exec("DELETE FROM dataset_filter_map WHERE dataset_id = " + id);
            for (Integer f : filters) exec("DELETE FROM filter WHERE filter_id = " + f);
            exec("DELETE FROM dataset WHERE dataset_id = " + id);
        }
        created.clear();
    }

    private void exec(String sql) throws Exception {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private MockMvc mvc() {
        return ProductionMvc.standalone(new DatasetsApiController(DATA_SOURCE,
                        Mockito.mock(CoreResources.class), Mockito.mock(RuleSetRuleDao.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockHttpSession sysadmin() {
        MockHttpSession s = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        s.setAttribute("userBean", ub);
        StudyBean study = new StudyBean();
        study.setId(1);
        study.setOid("S_DEFAULTS1");
        study.setName("Default Study");
        s.setAttribute("study", study);
        return s;
    }

    private String body(String name, String filtersJson) {
        return "{\"name\":\"" + name + "\",\"description\":\"\","
                + "\"eventDefinitionOids\":[\"" + eventOid + "\"],\"crfVersionIds\":[1],"
                + "\"itemIds\":[1,2,3,4,5],"
                + "\"includeFlags\":{\"completionDate\":true,\"gender\":true},"
                + "\"selectedItemOids\":[]"
                + (filtersJson == null ? "" : ",\"filters\":" + filtersJson)
                + "}";
    }

    private static final String TWO_FILTERS =
            "[{\"itemOid\":\"\",\"field\":\"participantId\",\"operator\":\"starts-with\",\"value\":\"m-00\"},"
                    + "{\"itemOid\":\"I_HEIGHT_CM\",\"operator\":\">\",\"value\":\"100\"}]";

    private int create(String filtersJson) throws Exception {
        MvcResult r = mvc().perform(post("/api/v1/studies/S_DEFAULTS1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RT " + System.nanoTime(), filtersJson))
                        .session(sysadmin()))
                .andExpect(status().isCreated())
                .andReturn();
        int id = JSON.readTree(r.getResponse().getContentAsString()).get("id").asInt();
        created.add(id);
        return id;
    }

    private JsonNode reopen(int id) throws Exception {
        MvcResult r = mvc().perform(get("/api/v1/datasets/" + id).session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn();
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void aSavedDatasetReopensWithItsSelectionFiltersAndFlags() throws Exception {
        int id = create(TWO_FILTERS);

        JsonNode dto = reopen(id);
        assertEquals(eventOid, dto.get("eventDefinitionOids").get(0).asText(),
                "the visit selection comes back: " + dto);
        assertEquals(5, dto.get("itemIds").size(), "the item selection comes back: " + dto);
        assertTrue(dto.get("includeFlags").get("completionDate").asBoolean(),
                "the CRF completion date flag is stored and read back");
        JsonNode filters = dto.get("filters");
        assertEquals(2, filters.size(), "the saved filters come back: " + dto);
        assertEquals("participantId", filters.get(0).get("field").asText());
        assertEquals("starts-with", filters.get(0).get("operator").asText());
        assertEquals("I_HEIGHT_CM", filters.get(1).get("itemOid").asText());
        assertTrue(filters.get(1).get("field") == null || filters.get(1).get("field").isNull(),
                "an item row carries no field");
    }

    @Test
    void aSaveThatDoesNotSendTheFiltersKeepsThem() throws Exception {
        int id = create(TWO_FILTERS);

        mvc().perform(put("/api/v1/datasets/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RT kept " + System.nanoTime(), null))
                        .session(sysadmin()))
                .andExpect(status().isOk());
        assertEquals(2, reopen(id).get("filters").size(), "the filters are still there");

        mvc().perform(put("/api/v1/datasets/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RT cleared " + System.nanoTime(), "[]"))
                        .session(sysadmin()))
                .andExpect(status().isOk());
        assertEquals(0, reopen(id).get("filters").size(), "an empty list removes them, as asked");
    }

    @Test
    void aFilterTheExportCouldNotApplyIsRefusedOnSave() throws Exception {
        for (String bad : List.of(
                "[{\"itemOid\":\"I_HEIGHT_CM\",\"operator\":\">\",\"value\":\"abc\"}]",
                "[{\"itemOid\":\"\",\"field\":\"participantId\",\"operator\":\">\",\"value\":\"M\"}]",
                "[{\"itemOid\":\"I_CONSENT_SIGNED\",\"operator\":\"starts-with\",\"value\":\"x\"}]",
                "[{\"itemOid\":\"\",\"field\":\"enrollmentDate\",\"operator\":\"=\",\"value\":\"24.11.2026\"}]")) {
            MvcResult r = mvc().perform(post("/api/v1/studies/S_DEFAULTS1/datasets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("RT bad " + System.nanoTime(), bad))
                            .session(sysadmin()))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            String message = r.getResponse().getContentAsString();
            assertTrue(message.contains("filters[0]"), "refused for the filter, not something else: " + message);
        }
    }

    @Test
    void thePreviewFiltersOnTheParticipantIdAndCountsWhatTheExportCarries() throws Exception {
        MvcResult r = mvc().perform(post("/api/v1/datasets/0:test-filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"itemOid\":\"\",\"field\":\"participantId\","
                                + "\"operator\":\"=\",\"value\":\" m-001 \"}],"
                                + "\"eventDefinitionOids\":[\"" + eventOid + "\"],\"itemIds\":[1,2,3,4,5]}")
                        .session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode counts = JSON.readTree(r.getResponse().getContentAsString());
        assertEquals(1, counts.get("matchingSubjects").asInt(),
                "the participant ID matches regardless of case and blanks: " + counts);
        assertTrue(counts.get("totalSubjects").asInt() > 1, counts.toString());
        assertTrue(counts.get("matchingVisits").asInt() >= 0, counts.toString());
        assertTrue(counts.get("matchingImages").asInt() >= 0, counts.toString());
        assertTrue(counts.has("matchingValues") && !counts.get("matchingValues").isNull(),
                "with a selection, the CRF values are counted: " + counts);
    }

    @Test
    void withoutASelectionThePreviewDoesNotPretendToCountValues() throws Exception {
        MvcResult r = mvc().perform(post("/api/v1/datasets/0:test-filter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"itemOid\":\"\",\"field\":\"participantId\","
                                + "\"operator\":\"in\",\"values\":[\"M-001\",\"M-002\"]}]}")
                        .session(sysadmin()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode counts = JSON.readTree(r.getResponse().getContentAsString());
        assertEquals(2, counts.get("matchingSubjects").asInt(), counts.toString());
        assertTrue(counts.get("matchingValues") == null || counts.get("matchingValues").isNull(),
                counts.toString());
    }
}
