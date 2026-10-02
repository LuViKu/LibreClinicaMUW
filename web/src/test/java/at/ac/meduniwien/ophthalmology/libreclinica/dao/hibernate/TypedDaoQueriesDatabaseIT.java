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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudyUserRole;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.user.UserAccount;

/**
 * Hibernate DAO queries whose results the application acts on, run against
 * PostgreSQL through the persistence unit production builds ({@link JpaConfig}).
 *
 * <ul>
 *   <li>{@link StudyUserRoleDao#findAllUserRolesByUserAccount} is the study and
 *       site role check of the REST clinical-data export
 *       ({@code GenerateClinicalDataServiceImpl}): an empty list refuses the
 *       export.</li>
 *   <li>{@link ItemDataDao#getMaxGroupRepeat} and
 *       {@link StudyEventDao#findMaxOrdinalByStudySubjectStudyEventDefinition}
 *       give the ordinal the next repeating-group row and the next repeating
 *       event are written with; with no rows they must answer 0, not fail.</li>
 *   <li>{@link RuleSetRuleDao#getCountWithFilter} is the row count of the rule
 *       assignment listing.</li>
 * </ul>
 *
 * <p>The three scalar queries are typed native queries ({@code Integer},
 * {@code Long}); a mapping that does not fit the column type, or a lost null
 * fallback, fails here rather than on a data write.
 */
@SuppressWarnings("null")
class TypedDaoQueriesDatabaseIT extends AbstractApiControllerDatabaseIT {

    private static AnnotationConfigApplicationContext context;
    private static TransactionTemplate tx;

    /** A site of the default study, created for this class. */
    private static int siteId;

    @BeforeAll
    static void startPersistenceUnit() throws Exception {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("it",
                Map.of("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")));
        context.getBeanFactory().registerSingleton("dataSource", DATA_SOURCE);
        context.register(JpaConfig.class, StudyUserRoleDao.class, ItemDataDao.class,
                StudyEventDao.class, RuleSetRuleDao.class);
        context.refresh();
        tx = context.getBean("sharedTransactionTemplate", TransactionTemplate.class);

        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            siteId = cloneRow(st, "study", "study_id", "study_id = 1",
                    "parent_study_id = 1, unique_identifier = 'IT-SITE-1', oc_oid = 'S_ITSITE1', name = 'IT site'");
            // owner_id 1 is root; status 1 available, 5 removed.
            st.executeUpdate("INSERT INTO study_user_role"
                    + " (role_name, study_id, status_id, owner_id, date_created, date_updated, update_id, user_name)"
                    + " VALUES ('ra', " + siteId + ", 1, 1, now(), now(), 1, 'it_site_user'),"
                    + "        ('ra', 1, 5, 1, now(), now(), 1, 'it_removed_user')");
        }
    }

    @AfterAll
    static void stopPersistenceUnit() {
        if (context != null) {
            context.close();
        }
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return tx.execute(_ -> work.get());
    }

    /* ---------------- study and site roles ---------------- */

    private static List<StudyUserRole> rolesFor(String userName, int studyId, int parentStudyId) {
        UserAccount user = new UserAccount();
        user.setUserName(userName);
        return inTransaction(() -> context.getBean(StudyUserRoleDao.class)
                .findAllUserRolesByUserAccount(user, studyId, parentStudyId));
    }

    @Test
    void aRoleAtTheSiteAdmitsTheSiteUser() {
        List<StudyUserRole> roles = rolesFor("it_site_user", siteId, 1);
        assertEquals(1, roles.size());
        assertEquals(siteId, roles.get(0).getId().getStudyId());
    }

    /**
     * Only the number of rows is asserted. Every column of study_user_role is
     * part of the entity's embedded id, and this seeded role has never been
     * updated, so date_updated and update_id are null; Hibernate 6 then
     * returns the row as a null entity. The export's check counts the rows
     * and reads nothing from them.
     */
    @Test
    void aRoleAtTheParentStudyAdmitsTheUserToItsSite() {
        assertEquals(1, rolesFor("manual_dm", siteId, 1).size());
    }

    @Test
    void noRoleInTheStudyTreeAdmitsNobody() {
        assertTrue(rolesFor("it_no_role_user", siteId, 1).isEmpty(), "a user without a role");
        assertTrue(rolesFor("it_site_user", 1, 1).isEmpty(), "a site role does not reach the parent study");
        assertTrue(rolesFor("it_removed_user", siteId, 1).isEmpty(), "a removed role");
    }

    /* ---------------- ordinals ---------------- */

    @Test
    void theNextGroupRepeatFollowsTheHighestOrdinal() throws Exception {
        int eventCrfId;
        int itemId;
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            eventCrfId = single(st, "SELECT min(event_crf_id) FROM item_data");
            itemId = single(st, "SELECT min(i.item_id) FROM item i WHERE NOT EXISTS"
                    + " (SELECT 1 FROM item_data d WHERE d.item_id = i.item_id AND d.event_crf_id = " + eventCrfId + ")");
        }
        ItemDataDao dao = context.getBean(ItemDataDao.class);

        assertEquals(0, (int) inTransaction(() -> dao.getMaxGroupRepeat(eventCrfId, itemId)),
                "no rows yet: the first repeat is written as 0 + 1");

        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            for (int ordinal : new int[] { 1, 2, 5 }) {
                cloneRow(st, "item_data", "item_data_id", "event_crf_id = " + eventCrfId,
                        "item_id = " + itemId + ", ordinal = " + ordinal);
            }
        }
        assertEquals(5, (int) inTransaction(() -> dao.getMaxGroupRepeat(eventCrfId, itemId)),
                "the highest ordinal, not the number of rows");
    }

    @Test
    void theNextRepeatingEventFollowsTheHighestSampleOrdinal() throws Exception {
        int studySubjectId;
        int definitionId;
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            studySubjectId = single(st, "SELECT min(study_subject_id) FROM study_event");
            definitionId = single(st, "SELECT min(d.study_event_definition_id) FROM study_event_definition d"
                    + " WHERE NOT EXISTS (SELECT 1 FROM study_event e WHERE e.study_event_definition_id = d.study_event_definition_id"
                    + " AND e.study_subject_id = " + studySubjectId + ")");
        }
        StudyEventDao dao = context.getBean(StudyEventDao.class);

        assertEquals(0, (int) inTransaction(
                () -> dao.findMaxOrdinalByStudySubjectStudyEventDefinition(studySubjectId, definitionId)),
                "no event yet");

        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            for (int ordinal : new int[] { 1, 4 }) {
                cloneRow(st, "study_event", "study_event_id", "study_subject_id = " + studySubjectId,
                        "study_event_definition_id = " + definitionId + ", sample_ordinal = " + ordinal);
            }
        }
        assertEquals(4, (int) inTransaction(
                () -> dao.findMaxOrdinalByStudySubjectStudyEventDefinition(studySubjectId, definitionId)),
                "the highest sample ordinal, not the number of events");
    }

    /* ---------------- rule assignment count ---------------- */

    @Test
    void theRuleAssignmentCountCountsRuleSetRulesOnce() throws Exception {
        try (Connection c = DATA_SOURCE.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO rule_expression (id, value, context, owner_id, date_created, status_id, version) VALUES"
                    + " (-901, 'I_IT_COUNT eq 1', 1, 1, now(), 1, 0),"
                    + " (-902, 'SE_IT.F_IT.IG_IT.I_IT_COUNT', 1, 1, now(), 1, 0)");
            st.executeUpdate("INSERT INTO rule (id, name, description, oc_oid, enabled, rule_expression_id, owner_id, date_created, status_id, version) VALUES"
                    + " (-901, 'counted', 'counted', 'IT_COUNTED_RULE', true, -901, 1, now(), 1, 0),"
                    + " (-902, 'other', 'other', 'IT_OTHER_RULE', true, -901, 1, now(), 1, 0)");
            st.executeUpdate("INSERT INTO rule_set (id, rule_expression_id, study_id, owner_id, date_created, status_id, version) VALUES"
                    + " (-901, -902, 1, 1, now(), 1, 0)");
            st.executeUpdate("INSERT INTO rule_set_rule (id, rule_set_id, rule_id, owner_id, date_created, status_id, version) VALUES"
                    + " (-901, -901, -901, 1, now(), 1, 0),"
                    + " (-902, -901, -901, 1, now(), 1, 0),"
                    + " (-903, -901, -902, 1, now(), 1, 0)");
            // Two actions on each rule set rule: the listing joins them, the count must not multiply.
            st.executeUpdate("INSERT INTO rule_action (id, rule_set_rule_id, action_type, expression_evaluates_to, message, owner_id, date_created, status_id, version) VALUES"
                    + " (-901, -901, 1, true, 'true', 1, now(), 1, 0),"
                    + " (-902, -901, 1, false, 'false', 1, now(), 1, 0),"
                    + " (-903, -902, 1, true, 'true', 1, now(), 1, 0),"
                    + " (-904, -902, 1, false, 'false', 1, now(), 1, 0),"
                    + " (-905, -903, 1, true, 'true', 1, now(), 1, 0)");
        }
        RuleSetRuleDao dao = context.getBean(RuleSetRuleDao.class);

        ViewRuleAssignmentFilter counted = new ViewRuleAssignmentFilter();
        counted.addFilter("studyId", "1");
        counted.addFilter("ruleOid", "IT_COUNTED_RULE");
        assertEquals(2, (int) inTransaction(() -> dao.getCountWithFilter(counted)));

        ViewRuleAssignmentFilter none = new ViewRuleAssignmentFilter();
        none.addFilter("studyId", "1");
        none.addFilter("ruleOid", "IT_NO_SUCH_RULE");
        assertEquals(0, (int) inTransaction(() -> dao.getCountWithFilter(none)));
    }

    /* ---------------- seeding ---------------- */

    private static int single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Copies the first row of {@code table} matching {@code where}, with a new
     * id from the column's sequence and {@code assignments} applied, so a seed
     * row need not spell out every NOT NULL column of these wide tables.
     * Returns the new id.
     */
    private static int cloneRow(Statement st, String table, String idColumn, String where, String assignments)
            throws SQLException {
        st.execute("DROP TABLE IF EXISTS it_clone");
        st.execute("CREATE TEMP TABLE it_clone AS SELECT * FROM " + table + " WHERE " + where
                + " ORDER BY " + idColumn + " LIMIT 1");
        st.execute("UPDATE it_clone SET " + idColumn + " = nextval(pg_get_serial_sequence('" + table + "', '"
                + idColumn + "')), " + assignments);
        int id = single(st, "SELECT " + idColumn + " FROM it_clone");
        st.execute("INSERT INTO " + table + " SELECT * FROM it_clone");
        st.execute("DROP TABLE it_clone");
        return id;
    }
}
