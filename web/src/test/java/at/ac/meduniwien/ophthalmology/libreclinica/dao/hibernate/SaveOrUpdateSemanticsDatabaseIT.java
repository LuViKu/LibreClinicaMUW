/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import at.ac.meduniwien.ophthalmology.libreclinica.config.JpaConfig;
import at.ac.meduniwien.ophthalmology.libreclinica.controller.api.AbstractApiControllerDatabaseIT;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudyUserRole;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudyUserRoleId;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetAuditBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleAuditBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.ConfigurationBean;

/**
 * What the generic DAOs' {@code saveOrUpdate} does on Hibernate 7, which
 * removed {@code Session.save} and {@code saveOrUpdate}. The contract the
 * callers were reviewed against
 * ({@code docs/development/modernization/spring-boot-4-hibernate-7-call-sites.md}):
 *
 * <ul>
 *   <li>a new entity is persisted: the instance passed in becomes managed and
 *       carries its generated id;</li>
 *   <li>a detached entity is merged: the row is updated, the instance
 *       returned is the managed copy and the one passed in stays detached;</li>
 *   <li>a managed entity is returned as it is;</li>
 *   <li>an audit row that points at a detached rule set (rule) is stored: the
 *       audit beans no longer cascade into the subject, which on Hibernate 7
 *       would throw "detached entity passed to persist".</li>
 * </ul>
 *
 * Runs against the persistence unit production builds ({@link JpaConfig}), with
 * one transaction per DAO call as the servlets and API controllers call them.
 */
@SuppressWarnings("null")
class SaveOrUpdateSemanticsDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static AnnotationConfigApplicationContext context;
    private static TransactionTemplate tx;

    @BeforeAll
    static void startPersistenceUnit() throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("it",
                Map.of("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")));
        context.getBeanFactory().registerSingleton("dataSource", DATA_SOURCE);
        context.register(JpaConfig.class, ConfigurationDao.class, StudyUserRoleDao.class,
                RuleSetDao.class, RuleSetRuleDao.class, RuleSetAuditDao.class, RuleSetRuleAuditDao.class);
        context.refresh();
        tx = context.getBean("sharedTransactionTemplate", TransactionTemplate.class);

        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO rule_expression (id, value, context, owner_id, date_created, status_id, version) VALUES"
                    + " (-911, 'SE_X.F_Y.IG_Z.I_W', 1, 1, now(), 1, 0), (-912, 'true', 1, 1, now(), 1, 0)");
            st.executeUpdate("INSERT INTO rule (id, name, description, oc_oid, enabled, rule_expression_id, study_id, owner_id, date_created, status_id, version) VALUES"
                    + " (-911, 'r', 'r', 'IT_SAVE_RULE', true, -912, 1, 1, now(), 1, 0)");
            st.executeUpdate("INSERT INTO rule_set (id, rule_expression_id, study_id, owner_id, date_created, status_id, version) VALUES"
                    + " (-911, -911, 1, 1, now(), 1, 0)");
            st.executeUpdate("INSERT INTO rule_set_rule (id, rule_set_id, rule_id, owner_id, date_created, status_id, version) VALUES"
                    + " (-911, -911, -911, 1, now(), 1, 0)");
        }
    }

    @AfterAll
    static void stopPersistenceUnit() throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM rule_set_rule_audit WHERE rule_set_rule_id = -911");
            st.executeUpdate("DELETE FROM rule_set_audit WHERE rule_set_id = -911");
            st.executeUpdate("DELETE FROM rule_set_rule WHERE id = -911");
            st.executeUpdate("DELETE FROM rule_set WHERE id = -911");
            st.executeUpdate("DELETE FROM rule WHERE id = -911");
            st.executeUpdate("DELETE FROM rule_expression WHERE id IN (-911, -912)");
            st.executeUpdate("DELETE FROM configuration WHERE key LIKE 'it.save.%'");
            st.executeUpdate("DELETE FROM study_user_role WHERE user_name = 'it_save_user'");
        }
        if (context != null) {
            context.close();
        }
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return tx.execute(_ -> work.get());
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static ConfigurationBean config(String key, String value) {
        ConfigurationBean bean = new ConfigurationBean();
        bean.setKey(key);
        bean.setValue(value);
        return bean;
    }

    // ---- new entity ---------------------------------------------------------------------

    @Test
    void aNewEntityIsPersistedAndTheInstancePassedInCarriesTheGeneratedId() throws Exception {
        ConfigurationDao dao = context.getBean(ConfigurationDao.class);
        ConfigurationBean fresh = config("it.save.new", "1");

        ConfigurationBean returned = inTransaction(() -> dao.saveOrUpdate(fresh));

        assertSame(fresh, returned, "persist: the same instance becomes the managed one");
        assertNotNull(fresh.getId(), "and carries the generated id, as Session.save assigned it");
        assertEquals("1", scalar("SELECT value FROM configuration WHERE key = 'it.save.new'"));
        assertEquals(String.valueOf(fresh.getId()), scalar("SELECT id FROM configuration WHERE key = 'it.save.new'"));
    }

    // ---- detached entity ----------------------------------------------------------------

    @Test
    void aDetachedEntityIsMergedTheRowIsUpdatedAndTheReturnedCopyIsTheManagedOne() throws Exception {
        ConfigurationDao dao = context.getBean(ConfigurationDao.class);
        inTransaction(() -> dao.saveOrUpdate(config("it.save.detached", "old")));
        ConfigurationBean detached = inTransaction(() -> dao.findByKey("it.save.detached"));
        detached.setValue("new");

        ConfigurationBean returned = inTransaction(() -> dao.saveOrUpdate(detached));

        assertNotSame(detached, returned, "merge returns a managed copy; the argument stays detached");
        assertEquals(detached.getId(), returned.getId());
        assertEquals("new", scalar("SELECT value FROM configuration WHERE key = 'it.save.detached'"));
        assertEquals("1", scalar("SELECT count(*) FROM configuration WHERE key = 'it.save.detached'"),
                "an update, not a second row");
    }

    @Test
    void aChangeMadeToTheDetachedArgumentAfterTheCallIsNotWritten() throws Exception {
        // The reason callers must go on with the returned instance.
        ConfigurationDao dao = context.getBean(ConfigurationDao.class);
        inTransaction(() -> dao.saveOrUpdate(config("it.save.late", "a")));
        ConfigurationBean detached = inTransaction(() -> dao.findByKey("it.save.late"));

        ConfigurationBean returned = inTransaction(() -> dao.saveOrUpdate(detached));
        detached.setValue("changed after the call");

        assertEquals("a", scalar("SELECT value FROM configuration WHERE key = 'it.save.late'"));
        assertNotSame(detached, returned);
    }

    // ---- managed entity -----------------------------------------------------------------

    @Test
    void anEntityAlreadyManagedInTheCallersTransactionIsReturnedAsItIsAndFlushedWithIt() throws Exception {
        ConfigurationDao dao = context.getBean(ConfigurationDao.class);
        inTransaction(() -> dao.saveOrUpdate(config("it.save.managed", "a")));

        ConfigurationBean[] seen = new ConfigurationBean[2];
        inTransaction(() -> {
            ConfigurationBean loaded = dao.findByKey("it.save.managed");
            loaded.setValue("b");
            seen[0] = loaded;
            seen[1] = dao.saveOrUpdate(loaded);
            return null;
        });

        assertSame(seen[0], seen[1]);
        assertEquals("b", scalar("SELECT value FROM configuration WHERE key = 'it.save.managed'"));
    }

    // ---- composite id -------------------------------------------------------------------

    @Test
    void aCompositeIdEntityIsInsertedOnceAndSavingItAgainIsHarmless() throws Exception {
        StudyUserRoleDao dao = context.getBean(StudyUserRoleDao.class);
        Date now = new Date();
        StudyUserRole role = new StudyUserRole(new StudyUserRoleId("ra", 1, 1, 1, now, "it_save_user"));

        StudyUserRole first = inTransaction(() -> dao.saveOrUpdate(role));
        assertSame(role, first);
        assertEquals("1", scalar("SELECT count(*) FROM study_user_role WHERE user_name = 'it_save_user'"));

        StudyUserRole again = new StudyUserRole(new StudyUserRoleId("ra", 1, 1, 1, now, "it_save_user"));
        inTransaction(() -> dao.saveOrUpdate(again));
        assertEquals("1", scalar("SELECT count(*) FROM study_user_role WHERE user_name = 'it_save_user'"),
                "an equal id is the same row: no second insert, no error");
    }

    // ---- audit rows that point at a detached subject ------------------------------------

    @Test
    void anAuditRowPointingAtADetachedRuleSetRuleIsStored() throws Exception {
        RuleSetRuleDao ruleSetRules = context.getBean(RuleSetRuleDao.class);
        RuleSetRuleAuditDao audits = context.getBean(RuleSetRuleAuditDao.class);

        // What the rule servlets and RulesApiController do: save the rule set rule, then audit it.
        RuleSetRuleBean detached = inTransaction(() -> ruleSetRules.findById(-911));
        detached.setStatus(Status.DELETED);
        RuleSetRuleBean saved = inTransaction(() -> ruleSetRules.saveOrUpdate(detached));
        assertEquals(String.valueOf(Status.DELETED.getCode()),
                scalar("SELECT status_id FROM rule_set_rule WHERE id = -911"));

        RuleSetRuleAuditBean audit = new RuleSetRuleAuditBean();
        audit.setRuleSetRuleBean(saved);
        audit.setStatus(Status.DELETED);
        RuleSetRuleAuditBean stored = inTransaction(() -> audits.saveOrUpdate(audit));

        assertSame(audit, stored);
        assertNotNull(audit.getId());
        assertEquals("1", scalar("SELECT count(*) FROM rule_set_rule_audit WHERE rule_set_rule_id = -911"));
        // The subject is untouched by the audit write.
        assertEquals("1", scalar("SELECT count(*) FROM rule_set_rule WHERE id = -911"));

        // and with the instance the caller loaded, not the returned one
        RuleSetRuleAuditBean second = new RuleSetRuleAuditBean();
        second.setRuleSetRuleBean(detached);
        second.setStatus(Status.AVAILABLE);
        inTransaction(() -> audits.saveOrUpdate(second));
        assertEquals("2", scalar("SELECT count(*) FROM rule_set_rule_audit WHERE rule_set_rule_id = -911"));
    }

    @Test
    void anAuditRowPointingAtADetachedRuleSetIsStored() throws Exception {
        RuleSetDao ruleSets = context.getBean(RuleSetDao.class);
        RuleSetAuditDao audits = context.getBean(RuleSetAuditDao.class);

        RuleSetBean detached = inTransaction(() -> ruleSets.getCurrentSession().get(RuleSetBean.class, -911));
        assertTrue(detached != null && detached.getId() == -911);
        RuleSetAuditBean audit = new RuleSetAuditBean();
        audit.setRuleSetBean(detached);
        audit.setStatus(Status.DELETED);

        inTransaction(() -> audits.saveOrUpdate(audit));

        assertNotNull(audit.getId());
        assertEquals("1", scalar("SELECT count(*) FROM rule_set_audit WHERE rule_set_id = -911"));
        assertEquals("1", scalar("SELECT count(*) FROM rule_set WHERE id = -911"), "the rule set is still there");
    }
}
