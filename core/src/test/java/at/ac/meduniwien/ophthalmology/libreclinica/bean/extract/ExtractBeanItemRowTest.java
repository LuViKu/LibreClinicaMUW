/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.extract;

import static org.junit.Assert.assertEquals;

import java.util.Locale;

import org.junit.Before;
import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;

/**
 * An extract row whose item has no data type (item.item_data_type_id is
 * nullable) is exported as its plain value. The date branch compared the
 * boxed type id with 9 unconditionally, while the branch below it already
 * treated a null id as "not a file".
 */
public class ExtractBeanItemRowTest {

    @Before
    public void locale() {
        ResourceBundleProvider.updateLocale(Locale.ENGLISH);
    }

    private static ExtractBean.extractDataset_ITEMGROUPSIDE row(Integer dataTypeId, String value) {
        ExtractBean.extractDataset_ITEMGROUPSIDE row = new ExtractBean(null).new extractDataset_ITEMGROUPSIDE();
        row.setSQLDatasetBASE_ITEMGROUPSIDE(1, 1, 1, "IG_VITALS", dataTypeId, "Weight", "I_WEIGHT", value, "kg", "v1", 1,
                null, null, null, null, 1, 1, 1, 1, 1, 1, 1, 1);
        return row;
    }

    @Test
    public void anItemWithoutADataTypeIsExportedAsItsValue() {
        assertEquals("72", row(null, "72").itemValue);
    }

    @Test
    public void aTextItemIsExportedAsItsValue() {
        assertEquals("72", row(5, "72").itemValue);
    }
}
