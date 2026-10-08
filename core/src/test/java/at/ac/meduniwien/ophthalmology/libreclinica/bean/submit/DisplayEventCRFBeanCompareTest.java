/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.submit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;

/**
 * compareTo compared a bean's event-definition CRF with its own getter, so it
 * answered 0 for any two beans and a sort never ordered them.
 */
public class DisplayEventCRFBeanCompareTest {

    private static DisplayEventCRFBean crfAt(int ordinal) {
        EventDefinitionCRFBean edc = new EventDefinitionCRFBean();
        edc.setOrdinal(ordinal);
        DisplayEventCRFBean bean = new DisplayEventCRFBean();
        bean.setEventDefinitionCRF(edc);
        return bean;
    }

    @Test
    public void beansCompareByTheirDefinitionCrfOrdinal() {
        assertTrue(crfAt(1).compareTo(crfAt(2)) < 0);
        assertTrue(crfAt(3).compareTo(crfAt(2)) > 0);
        assertEquals(0, crfAt(2).compareTo(crfAt(2)));
    }
}
