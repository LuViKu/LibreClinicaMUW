/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.control.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Arrays;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.EventDefinitionCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.EventDefinitionCRFDAO;
import org.junit.jupiter.api.Test;

class RemoveCRFVersionServletTest {

    private static CRFVersionBean version(int id) {
        CRFVersionBean v = new CRFVersionBean();
        v.setId(id);
        return v;
    }

    @Test
    void parseIdsSkipsBlankAndMalformedTokens() {
        assertEquals(Arrays.asList(3, 7, 9), RemoveCRFVersionServlet.parseIds("3, 7,,x, 9 ,"));
        assertTrue(RemoveCRFVersionServlet.parseIds(null).isEmpty());
        assertTrue(RemoveCRFVersionServlet.parseIds("").isEmpty());
    }

    @Test
    void malformedSelectedVersionIdsDoNotThrow() {
        EventDefinitionCRFBean edc = new EventDefinitionCRFBean();
        edc.setSelectedVersionIds("1,,abc,2");
        EventDefinitionCRFDAO dao = mock(EventDefinitionCRFDAO.class);
        ArrayList<CRFVersionBean> versions = new ArrayList<>(Arrays.asList(version(5), version(2)));

        RemoveCRFVersionServlet.updateEventDef(edc, dao, versions);
        assertEquals(2, edc.getDefaultVersionId());

        RemoveCRFVersionServlet.updateEventDef(edc, dao, versions, 99);
        assertEquals(2, edc.getDefaultVersionId());
    }

    @Test
    void emptyVersionListIsANoOp() {
        EventDefinitionCRFBean edc = new EventDefinitionCRFBean();
        EventDefinitionCRFDAO dao = mock(EventDefinitionCRFDAO.class);

        RemoveCRFVersionServlet.updateEventDef(edc, dao, new ArrayList<>());
        RemoveCRFVersionServlet.updateEventDef(edc, dao, null, 1);

        verify(dao, never()).update(any(EventDefinitionCRFBean.class));
    }
}
