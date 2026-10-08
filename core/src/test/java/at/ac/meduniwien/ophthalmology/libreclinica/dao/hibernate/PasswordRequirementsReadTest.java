/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.technicaladmin.ConfigurationBean;

import org.junit.Test;

/**
 * {@code pwd.change.required} is stored as an int: the 2012 changeset seeds
 * {@code 1}, and both the legacy form and {@link PasswordRequirementsDao#setChangeRequired}
 * write {@code 1} or {@code 0}. Read as a boolean, {@code 1} was false, so the
 * password-policy page showed "force a change on first login" as off while it
 * was on, and saving the page switched it off.
 *
 * <p>Not named {@code *DaoTest}: the build excludes that pattern under
 * {@code dao/} as database-bound, and this test needs no database.
 */
public class PasswordRequirementsReadTest {

    private static PasswordRequirementsDao storing(String value) {
        ConfigurationBean bean = new ConfigurationBean();
        bean.setKey(PasswordRequirementsDao.PWD_CHANGE_REQUIRED);
        bean.setValue(value);
        ConfigurationDao configurationDao = mock(ConfigurationDao.class);
        when(configurationDao.findByKey(PasswordRequirementsDao.PWD_CHANGE_REQUIRED)).thenReturn(bean);
        return new PasswordRequirementsDao(configurationDao);
    }

    @Test
    public void theSeededOneReadsAsRequired() {
        assertTrue(storing("1").changeRequired());
    }

    @Test
    public void zeroReadsAsNotRequired() {
        assertFalse(storing("0").changeRequired());
    }

    @Test
    public void whatTheSetterWritesReadsBack() {
        PasswordRequirementsDao dao = storing("0");
        dao.setChangeRequired(1);
        assertTrue(dao.changeRequired());
        dao.setChangeRequired(0);
        assertFalse(dao.changeRequired());
    }

    @Test
    public void aBooleanWrittenByHandStillReads() {
        assertTrue(storing(" true ").changeRequired());
        assertFalse(storing("false").changeRequired());
        assertFalse(storing(null).changeRequired());
    }
}
