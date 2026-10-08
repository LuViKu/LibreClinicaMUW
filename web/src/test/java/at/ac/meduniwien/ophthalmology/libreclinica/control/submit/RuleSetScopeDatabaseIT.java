/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.submit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

import jakarta.servlet.http.HttpServlet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.StudyUserRoleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.control.admin.LegacyServletHarness;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleAuditDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetRuleDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetServiceInterface;

/**
 * The legacy rule pages take a rule set or rule set rule by id. Against the real
 * schema: the director of study 1 cannot remove, restore, run or test study
 * 102's rule set rules, and the rows keep their status; study 1's own still
 * remove and restore.
 */
class RuleSetScopeDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static final int AVAILABLE = 1;
    private static final int DELETED = 5;
    private static final String MAIN_MENU = "/MainMenu";

    private LegacyServletHarness harness;
    private RuleSetRuleDao ruleSetRuleDao;
    private RuleSetRuleAuditDao auditDao;
    private RuleSetServiceInterface ruleSetService;
    private int ownRuleSet;
    private int ownRuleSetRule;
    private int otherRuleSet;
    private int otherRuleSetRule;

    @BeforeEach
    void setUp() throws Exception {
        harness = new LegacyServletHarness(DATA_SOURCE).bean("mailSender", mock(JavaMailSenderImpl.class));
        ruleSetRuleDao = mock(RuleSetRuleDao.class);
        auditDao = mock(RuleSetRuleAuditDao.class);
        ruleSetService = mock(RuleSetServiceInterface.class);
        when(ruleSetService.getRuleSetRuleDao()).thenReturn(ruleSetRuleDao);
        // The DAO stands in for Hibernate: it reads and writes the real row.
        when(ruleSetRuleDao.findById(anyInt())).thenAnswer(call -> {
            int id = call.getArgument(0);
            RuleSetRuleBean rsr = new RuleSetRuleBean();
            rsr.setId(id);
            rsr.setStatus(Status.getByCode(queryInt("SELECT status_id FROM rule_set_rule WHERE id = " + id)));
            RuleSetBean rs = new RuleSetBean();
            rs.setId(queryInt("SELECT rule_set_id FROM rule_set_rule WHERE id = " + id));
            rsr.setRuleSetBean(rs);
            return rsr;
        });
        when(ruleSetRuleDao.saveOrUpdate(any(RuleSetRuleBean.class))).thenAnswer(call -> {
            RuleSetRuleBean rsr = call.getArgument(0);
            update("UPDATE rule_set_rule SET status_id = " + rsr.getStatus().getCode() + " WHERE id = " + rsr.getId());
            return rsr;
        });

        int expression = queryInt("INSERT INTO rule_expression (value, context, owner_id, date_created, status_id)"
                + " VALUES ('SE_X.F_Y.IG_Z.I_W', 1, 1, now(), 1) RETURNING id");
        int rule = queryInt("INSERT INTO rule (name, oc_oid, enabled, rule_expression_id, owner_id, date_created, status_id)"
                + " VALUES ('r', 'RULE_SCOPE_IT', true, " + expression + ", 1, now(), 1) RETURNING id");
        ownRuleSet = addRuleSet(1, 1, expression);
        ownRuleSetRule = addRuleSetRule(ownRuleSet, rule);
        otherRuleSet = addRuleSet(102, 10, expression);
        otherRuleSetRule = addRuleSetRule(otherRuleSet, rule);
    }

    @AfterEach
    void tearDown() throws SQLException {
        update("DELETE FROM rule_set_rule WHERE rule_set_id IN (" + ownRuleSet + ", " + otherRuleSet + ")");
        update("DELETE FROM rule_set WHERE id IN (" + ownRuleSet + ", " + otherRuleSet + ")");
        update("DELETE FROM rule WHERE oc_oid = 'RULE_SCOPE_IT'");
        update("DELETE FROM rule_expression WHERE value = 'SE_X.F_Y.IG_Z.I_W'");
    }

    // ---- UpdateRuleSetRuleServlet ----------------------------------------------------------

    @Test
    void anotherStudysRuleSetRuleIsNotRemoved() throws Exception {
        MockHttpServletResponse resp = change(otherRuleSetRule, "remove", null);

        assertEquals(AVAILABLE, ruleSetRuleStatus(otherRuleSetRule), "study 102's rule kept its status");
        assertEquals(MAIN_MENU, resp.getForwardedUrl());
        verify(auditDao, never()).saveOrUpdate(any());
    }

    @Test
    void anotherStudysRuleSetRuleIsNotRestored() throws Exception {
        update("UPDATE rule_set_rule SET status_id = " + DELETED + " WHERE id = " + otherRuleSetRule);

        MockHttpServletResponse resp = change(otherRuleSetRule, "restore", null);

        assertEquals(DELETED, ruleSetRuleStatus(otherRuleSetRule), "study 102's rule kept its status");
        assertEquals(MAIN_MENU, resp.getForwardedUrl());
    }

    @Test
    void theRulesOfAnotherStudysRuleSetAreNotRemoved() throws Exception {
        // Removing a whole rule set by its id, as the rule set page does.
        MockHttpServletResponse resp = change(null, "remove", otherRuleSet);

        assertEquals(AVAILABLE, ruleSetRuleStatus(otherRuleSetRule), "study 102's rule kept its status");
        assertEquals(MAIN_MENU, resp.getForwardedUrl());
    }

    @Test
    void theCurrentStudysOwnRuleSetRuleIsRemovedAndRestored() throws Exception {
        MockHttpServletResponse resp = change(ownRuleSetRule, "remove", null);
        assertEquals(DELETED, ruleSetRuleStatus(ownRuleSetRule));
        assertEquals("/ViewRuleAssignment", stripQuery(resp.getForwardedUrl()));

        change(ownRuleSetRule, "restore", null);
        assertEquals(AVAILABLE, ruleSetRuleStatus(ownRuleSetRule));
    }

    // ---- RunRuleServlet, TestRuleServlet, DownloadRuleSetXmlServlet -------------------------

    @Test
    void anotherStudysRuleSetRuleIsNotRun() throws Exception {
        RunRuleServlet servlet = new RunRuleServlet();
        servlet.ruleSetService = ruleSetService;

        MockHttpServletResponse resp = run(servlet, "/RunRule", "ruleSetRuleId", String.valueOf(otherRuleSetRule),
                "versionId", "1", "action", "dryRun");

        assertEquals(MAIN_MENU, resp.getForwardedUrl());
        verify(ruleSetService, never()).runRulesInBulk(any(String.class), any(String.class), any(), any(), any());
    }

    @Test
    void anotherStudysRuleSetRuleIsNotTested() throws Exception {
        TestRuleServlet servlet = new TestRuleServlet();
        servlet.ruleSetRuleDao = ruleSetRuleDao;

        MockHttpServletResponse resp = run(servlet, "/TestRule", "ruleSetRuleId", String.valueOf(otherRuleSetRule));

        assertEquals(MAIN_MENU, resp.getForwardedUrl());
        verify(ruleSetRuleDao, never()).findById(anyInt());
    }

    @Test
    void anotherStudysRuleSetRuleIsNotDownloaded() throws Exception {
        DownloadRuleSetXmlServlet servlet = new DownloadRuleSetXmlServlet();
        servlet.ruleSetService = ruleSetService;

        MockHttpServletResponse resp = run(servlet, "/DownloadRuleSetXml", "ruleSetRuleIds",
                ownRuleSetRule + "," + otherRuleSetRule);

        assertEquals(MAIN_MENU, resp.getForwardedUrl());
        verify(ruleSetRuleDao, never()).findById(anyInt());
    }

    /** Guards the fixture: the rule sets belong to studies 1 and 102, and the director to study 1 only. */
    @Test
    void theFixtureRowsExist() throws Exception {
        assertEquals(1, queryInt("SELECT study_id FROM rule_set WHERE id = " + ownRuleSet));
        assertEquals(102, queryInt("SELECT study_id FROM rule_set WHERE id = " + otherRuleSet));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_dm' AND study_id = 102"));
        assertTrue(queryInt("SELECT COUNT(*) FROM study_user_role WHERE user_name = 'manual_dm' AND study_id = 1"
                + " AND role_name = 'director'") > 0);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private MockHttpServletResponse change(Integer ruleSetRule, String action, Integer ruleSet) throws Exception {
        UpdateRuleSetRuleServlet servlet = new UpdateRuleSetRuleServlet();
        servlet.ruleSetRuleDao = ruleSetRuleDao;
        servlet.ruleSetRuleAuditDao = auditDao;
        servlet.ruleSetDao = mock(at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao.class);
        if (ruleSet != null) {
            RuleSetBean bean = new RuleSetBean();
            bean.setId(ruleSet);
            RuleSetRuleBean rsr = new RuleSetRuleBean();
            rsr.setId(queryInt("SELECT id FROM rule_set_rule WHERE rule_set_id = " + ruleSet));
            rsr.setStatus(Status.AVAILABLE);
            bean.addRuleSetRule(rsr);
            when(servlet.ruleSetDao.findById(ruleSet)).thenReturn(bean);
        }
        java.util.List<String> params = new java.util.ArrayList<>();
        params.add("action");
        params.add(action);
        if (ruleSetRule != null) {
            params.add("ruleSetRuleId");
            params.add(String.valueOf(ruleSetRule));
        }
        if (ruleSet != null) {
            params.add("ruleSetId");
            params.add(String.valueOf(ruleSet));
        }
        return run(servlet, "/UpdateRuleSetRule", params.toArray(new String[0]));
    }

    private MockHttpServletResponse run(HttpServlet servlet, String path, String... params) throws Exception {
        MockHttpServletRequest req = harness.request("POST", path, director());
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
        }
        return harness.run(servlet, req);
    }

    private static String stripQuery(String url) {
        return url == null || url.indexOf('?') < 0 ? url : url.substring(0, url.indexOf('?'));
    }

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

    private static int addRuleSet(int study, int definition, int expression) throws SQLException {
        return queryInt("INSERT INTO rule_set (rule_expression_id, study_event_definition_id, study_id, owner_id,"
                + " date_created, status_id) VALUES (" + expression + ", " + definition + ", " + study
                + ", 1, now(), 1) RETURNING id");
    }

    private static int addRuleSetRule(int ruleSet, int rule) throws SQLException {
        return queryInt("INSERT INTO rule_set_rule (rule_set_id, rule_id, owner_id, date_created, status_id)"
                + " VALUES (" + ruleSet + ", " + rule + ", 1, now(), 1) RETURNING id");
    }

    private static int ruleSetRuleStatus(int id) throws SQLException {
        return queryInt("SELECT status_id FROM rule_set_rule WHERE id = " + id);
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
