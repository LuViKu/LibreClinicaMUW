/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.crfdata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.Test;

/**
 * Equality of a conditional-display (SCD) metadata row (2026-09-29).
 *
 * <p>The two form-metadata ids are {@code Integer}s and were compared with
 * {@code !=}, i.e. by reference: two rows with the same ids above 127 (outside
 * the Integer cache) were unequal. {@code hashCode} unboxed them and threw on
 * null.
 */
public class SCDItemMetadataBeanTest {

    private static SCDItemMetadataBean bean(int scdId, int controlId) {
        SCDItemMetadataBean b = new SCDItemMetadataBean();
        b.setScdItemFormMetadataId(scdId);
        b.setControlItemFormMetadataId(controlId);
        b.setControlItemName("CONTROL");
        b.setOptionValue("1");
        b.setMessage("shown when CONTROL is 1");
        b.setScdItemId(7);
        return b;
    }

    @Test
    public void rowsWithTheSameIdsAboveTheIntegerCacheAreEqual() {
        SCDItemMetadataBean a = bean(1000, 2000);
        SCDItemMetadataBean b = bean(1000, 2000);
        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void rowsWithDifferentIdsAreNotEqual() {
        assertFalse(bean(1000, 2000).equals(bean(1001, 2000)));
        assertFalse(bean(1000, 2000).equals(bean(1000, 2001)));
    }

    @Test
    public void aNullIdNeitherThrowsNorEqualsAnId() throws Exception {
        SCDItemMetadataBean a = bean(1000, 2000);
        SCDItemMetadataBean b = bean(1000, 2000);
        Field f = SCDItemMetadataBean.class.getDeclaredField("controlItemFormMetadataId");
        f.setAccessible(true);
        f.set(a, null);

        a.hashCode();
        assertFalse(a.equals(b));
        assertFalse(b.equals(a));
        f.set(b, null);
        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());
    }
}
