/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupMetadataBean;

/**
 * The confirmation table for a CRF version change compares the item lists of both
 * versions. An item without an item_group_metadata row has no group metadata; it
 * is shown as a plain (non-repeating) item and does not abort the table.
 */
class ChangeCRFVersionControllerBuildRecordTest {

    private static ArrayList<String[]> build(ItemBean cur, ItemBean neu,
            ItemGroupMetadataBean curMd, ItemGroupMetadataBean newMd) throws Exception {
        Method m = ChangeCRFVersionController.class.getDeclaredMethod("buildRecord",
                ItemBean.class, ItemBean.class, ItemGroupMetadataBean.class, ItemGroupMetadataBean.class,
                ArrayList.class);
        m.setAccessible(true);
        ArrayList<String[]> rows = new ArrayList<>();
        m.invoke(new ChangeCRFVersionController(), cur, neu, curMd, newMd, rows);
        return rows;
    }

    private static ItemBean item(int id, String name, String... values) {
        ItemBean item = new ItemBean();
        item.setId(id);
        item.setName(name);
        item.setOid("I_" + id);
        ArrayList<ItemDataBean> data = new ArrayList<>();
        int ordinal = 1;
        for (String v : values) {
            ItemDataBean d = new ItemDataBean();
            d.setValue(v);
            d.setOrdinal(ordinal++);
            data.add(d);
        }
        item.setItemDataElements(data);
        return item;
    }

    @Test
    void anItemDroppedByTheNewVersionWithoutGroupMetadataIsListed() throws Exception {
        ArrayList<String[]> rows = build(item(1, "weight", "70"), null, null, null);
        assertEquals(1, rows.size());
        assertEquals("weight", rows.get(0)[0]);
        assertEquals("70", rows.get(0)[3]);
    }

    @Test
    void anItemWithoutValuesAndWithoutGroupMetadataIsListed() throws Exception {
        ArrayList<String[]> rows = build(item(1, "weight"), null, null, null);
        assertEquals(1, rows.size());
        assertEquals("weight", rows.get(0)[0]);
    }

    @Test
    void anItemAddedByTheNewVersionWithoutGroupMetadataIsListed() throws Exception {
        ArrayList<String[]> rows = build(null, item(2, "height"), null, null);
        assertEquals(1, rows.size());
        assertEquals("height", rows.get(0)[4]);
    }

    @Test
    void aNonRepeatingDroppedItemShowsOnlyItsFirstValue() throws Exception {
        ItemGroupMetadataBean md = new ItemGroupMetadataBean();
        md.setRepeatingGroup(false);
        ArrayList<String[]> rows = build(item(1, "weight", "70", "71"), null, md, null);
        assertEquals(1, rows.size());
    }

    @Test
    void aRepeatingDroppedItemShowsEveryValue() throws Exception {
        ItemGroupMetadataBean md = new ItemGroupMetadataBean();
        md.setRepeatingGroup(true);
        ArrayList<String[]> rows = build(item(1, "weight", "70", "71"), null, md, null);
        assertEquals(2, rows.size());
    }
}
