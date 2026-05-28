/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.akaza.openclinica.dao.hibernate.ConfigurationDao;
import org.akaza.openclinica.domain.technicaladmin.ConfigurationBean;
import org.akaza.openclinica.templates.HibernateOcDbTestCase;

public class ConfigurationDaoTest extends HibernateOcDbTestCase {

    public ConfigurationDaoTest() {
        super();
    }

    public void testSaveOrUpdate() {
        ConfigurationDao configurationDao = (ConfigurationDao) getContext().getBean("configurationDao");
        ConfigurationBean configurationBean = new ConfigurationBean();
        configurationBean.setKey("user.test");
        configurationBean.setValue("test");
        configurationBean.setDescription("Testing attention please");

        configurationBean = configurationDao.saveOrUpdate(configurationBean);

        assertNotNull("Persistant id is null", configurationBean.getId());
    }

    public void testfindById() {
        ConfigurationDao configurationDao = (ConfigurationDao) getContext().getBean("configurationDao");
        ConfigurationBean configurationBean = configurationDao.findById(-1);

        assertEquals("Key should be test.test", "test.test", configurationBean.getKey());
    }

    public void testfindByKey() {
        ConfigurationDao configurationDao = (ConfigurationDao) getContext().getBean("configurationDao");
        ConfigurationBean configurationBean = configurationDao.findByKey("test.test");

        assertEquals("Key should be test.test", "test.test", configurationBean.getKey());
    }

    /**
     * findById round-trips the value column, not just the key.
     * Phase 0 integration-test backlog (MIGRATION.md): exercises Hibernate
     * column-to-property mapping for the value column.
     */
    public void testFindByIdReturnsValue() {
        ConfigurationDao configurationDao = (ConfigurationDao) getContext().getBean("configurationDao");
        ConfigurationBean fixtureRow = configurationDao.findById(-2);

        assertNotNull("Fixture row -2 should exist", fixtureRow);
        assertEquals("muw.fixture.two", fixtureRow.getKey());
        assertEquals("second-value", fixtureRow.getValue());
    }

    /**
     * findByKey returns null for a key that is not in the table, rather than
     * throwing or returning an empty bean. Lockdown for callers that rely on
     * a missing-key signal.
     */
    public void testFindByKeyReturnsNullForUnknownKey() {
        ConfigurationDao configurationDao = (ConfigurationDao) getContext().getBean("configurationDao");
        ConfigurationBean configurationBean = configurationDao.findByKey("muw.this.key.does.not.exist");

        assertNull("findByKey on an absent key should return null", configurationBean);
    }

    /**
     * findAll returns all fixture rows. Beyond row count, asserts the
     * specific keys we seeded - tolerates Liquibase or bootstrap inserting
     * additional rows (assertTrue containsAll, not assertEquals on size).
     */
    public void testFindAllReturnsFixtureRows() {
        ConfigurationDao configurationDao = (ConfigurationDao) getContext().getBean("configurationDao");
        ArrayList<ConfigurationBean> all = configurationDao.findAll();

        assertNotNull("findAll should never return null", all);
        Set<String> keys = new HashSet<>();
        for (ConfigurationBean row : all) {
            keys.add(row.getKey());
        }
        assertTrue("findAll missing fixture row 'test.test': " + keys,
                keys.contains("test.test"));
        assertTrue("findAll missing fixture row 'muw.fixture.two': " + keys,
                keys.contains("muw.fixture.two"));
        assertTrue("findAll missing fixture row 'muw.fixture.long': " + keys,
                keys.contains("muw.fixture.long"));
    }

    /**
     * saveOrUpdate on an existing row mutates it in place (same primary key,
     * new value), rather than inserting a duplicate.
     */
    public void testSaveOrUpdateMutatesExistingRow() {
        ConfigurationDao configurationDao = (ConfigurationDao) getContext().getBean("configurationDao");
        ConfigurationBean existing = configurationDao.findById(-2);
        assertNotNull("Fixture row -2 must exist for this test", existing);
        Integer originalId = existing.getId();

        existing.setValue("updated-value");
        ConfigurationBean saved = configurationDao.saveOrUpdate(existing);

        assertEquals("saveOrUpdate must not allocate a new primary key for an existing row",
                originalId, saved.getId());

        ConfigurationBean reread = configurationDao.findByKey("muw.fixture.two");
        assertEquals("updated-value", reread.getValue());
    }
}