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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.Role;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.UserType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.service.auth.SiteVisibilityFilter;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SecurityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A removal or restore that fails part-way changes nothing: the event
 * definition's, the subject's and the event CRF's cascades each run in one
 * transaction, the parent's own status included, and roll it back on any
 * exception. Each test makes the cascade's first {@code item_data} update
 * throw, after the parent, its visits and its event CRFs were updated on
 * the same connection, and compares every status in the study before and
 * after.
 */
class CascadeRollbackDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int STUDY_ID = 1;
    private static final String STUDY_OID = "S_DEFAULTS1";

    /** While set, preparing an {@code UPDATE item_data} statement throws. */
    private static final AtomicBoolean FAIL_ON_VALUES = new AtomicBoolean();

    @AfterEach
    void disarm() {
        FAIL_ON_VALUES.set(false);
    }

    private MockMvc mockMvc() {
        DataSource ds = failingOnValues(DATA_SOURCE);
        SiteVisibilityFilter visibility = new SiteVisibilityFilter(ds);
        return ProductionMvc.standalone(
                        new EventDefinitionsApiController(ds),
                        new SubjectsApiController(ds, Mockito.mock(SecurityManager.class), visibility),
                        new EventCrfRemovalApiController(ds, visibility))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void aFailedDefinitionRemovalChangesNothing() throws Exception {
        String before = statuses();
        FAIL_ON_VALUES.set(true);
        mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V3_DAY90/disable")
                        .session(adminSession()))
                .andExpect(status().isInternalServerError());
        assertEquals(before, statuses());
    }

    @Test
    void aFailedDefinitionRestoreChangesNothing() throws Exception {
        mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V1_INCLUSION/disable")
                        .session(adminSession()))
                .andExpect(status().isOk());
        try {
            String before = statuses();
            FAIL_ON_VALUES.set(true);
            mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V1_INCLUSION/restore")
                            .session(adminSession()))
                    .andExpect(status().isInternalServerError());
            assertEquals(before, statuses());
        } finally {
            FAIL_ON_VALUES.set(false);
            mockMvc().perform(post("/api/v1/studies/" + STUDY_OID + "/event-definitions/SE_V1_INCLUSION/restore")
                    .session(adminSession()));
        }
    }

    @Test
    void aFailedSubjectRemovalChangesNothing() throws Exception {
        String before = statuses();
        FAIL_ON_VALUES.set(true);
        mockMvc().perform(post("/api/v1/subjects/M-005/remove").session(dataManagerSession()))
                .andExpect(status().isInternalServerError());
        assertEquals(before, statuses());
    }

    @Test
    void aFailedEventCrfRemovalChangesNothing() throws Exception {
        String before = statuses();
        FAIL_ON_VALUES.set(true);
        mockMvc().perform(post("/api/v1/eventCrfs/9/remove").session(dataManagerSession())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong subject\"}"))
                .andExpect(status().isInternalServerError());
        assertEquals(before, statuses());
    }

    /* ---------------------------------------------------------------- */
    /* Helpers                                                          */
    /* ---------------------------------------------------------------- */

    /** Every status and recorded status in the database, and the removal audit rows, as one string. */
    private static String statuses() throws SQLException {
        StringBuilder out = new StringBuilder();
        for (String table : new String[] {"study_event_definition", "event_definition_crf", "study_subject",
                "study_event", "event_crf", "item_data"}) {
            String old = table.equals("event_crf") || table.equals("item_data") ? "COALESCE(old_status_id, 0)" : "0";
            out.append(table).append('=').append(text("SELECT string_agg(" + table + "_id || ':' || status_id || ':' || "
                    + old + ", ',' ORDER BY " + table + "_id) FROM " + table)).append('\n');
        }
        out.append(text("SELECT COUNT(*) FROM audit_log_event WHERE audit_log_event_type_id = "
                + AuditTypeIds.EVENT_CRF_REMOVED));
        return out.toString();
    }

    /** {@code real}, except that preparing an {@code UPDATE item_data} throws while armed. */
    private static DataSource failingOnValues(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(CascadeRollbackDatabaseIT.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (_, method, args) -> {
                    Object result = call(real, method, args);
                    return result instanceof Connection c ? failingOnValues(c) : result;
                });
    }

    private static Connection failingOnValues(Connection real) {
        return (Connection) Proxy.newProxyInstance(CascadeRollbackDatabaseIT.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (_, method, args) -> {
                    if (FAIL_ON_VALUES.get() && method.getName().equals("prepareStatement")
                            && args[0] instanceof String sql && sql.startsWith("UPDATE item_data")) {
                        throw new IllegalStateException("injected failure");
                    }
                    return call(real, method, args);
                });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static MockHttpSession adminSession() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(1);
        ub.setName("root");
        ub.addUserType(UserType.SYSADMIN);
        session.setAttribute("userBean", ub);
        session.setAttribute("study", study());
        return session;
    }

    private static MockHttpSession dataManagerSession() throws SQLException {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
        MockHttpSession session = new MockHttpSession();
        UserAccountBean ub = new UserAccountBean();
        ub.setId(Integer.parseInt(text("SELECT user_id FROM user_account WHERE user_name = 'manual_dm'")));
        ub.setName("manual_dm");
        session.setAttribute("userBean", ub);
        session.setAttribute("study", study());
        StudyUserRoleBean role = new StudyUserRoleBean();
        role.setRole(Role.STUDYDIRECTOR);
        role.setStudyId(STUDY_ID);
        session.setAttribute("userRole", role);
        return session;
    }

    private static StudyBean study() {
        StudyBean study = new StudyBean();
        study.setId(STUDY_ID);
        study.setOid(STUDY_OID);
        study.setName("Default Study");
        return study;
    }

    private static String text(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }
}
