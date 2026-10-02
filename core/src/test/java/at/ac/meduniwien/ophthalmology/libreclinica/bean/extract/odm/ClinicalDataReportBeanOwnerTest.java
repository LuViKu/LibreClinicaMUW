/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.extract.odm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.odmbeans.ChildNoteBean;

/**
 * The owner of a discrepancy note's child note is written as UserName and Name
 * attributes in the ODM export. user_account.first_name, last_name and
 * user_name are nullable, and a missing value must leave the attribute out
 * rather than fail the whole export.
 */
public class ClinicalDataReportBeanOwnerTest {

    private static ChildNoteBean note(String userName, String firstName, String lastName) {
        ChildNoteBean cn = new ChildNoteBean();
        cn.setOwnerUserName(userName);
        cn.setOwnerFirstName(firstName);
        cn.setOwnerLastName(lastName);
        return cn;
    }

    @Test
    public void aCompleteOwnerIsWritten() {
        assertEquals("UserName=\"jdoe\" Name=\"Jane Doe\"", ClinicalDataReportBean.ownerAttributes(note("jdoe", "Jane", "Doe")));
    }

    @Test
    public void missingNamesAreLeftOut() {
        assertEquals("UserName=\"jdoe\" ", ClinicalDataReportBean.ownerAttributes(note("jdoe", null, null)));
        assertEquals("", ClinicalDataReportBean.ownerAttributes(note(null, null, null)));
        assertEquals("", ClinicalDataReportBean.ownerAttributes(note("", "", "")));
    }

    @Test
    public void aSingleMissingNameDoesNotPrintNull() {
        assertEquals("UserName=\"jdoe\" Name=\"Jane\"", ClinicalDataReportBean.ownerAttributes(note("jdoe", "Jane", null)));
        assertEquals("Name=\"Doe\"", ClinicalDataReportBean.ownerAttributes(note(null, null, "Doe")));
    }
}
