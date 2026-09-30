/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.crfdata;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.junit.Test;
import org.mockito.MockedConstruction;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemGroupMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.EventCRFDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemGroupMetadataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action.PropertyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.Context;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.expression.ExpressionService;

/**
 * An InsertAction destination may name the {@code [END]} row of a repeating
 * group: the value goes into a new last row. insert() has a branch for exactly
 * that ("B is a repeating group with index selected as END", for a repeating or
 * a non-repeating source A). For a non-repeating source the branch above it,
 * "index selected", also matched END and parsed it as a row number, so the
 * action failed with a NumberFormatException before the END branch was reached.
 */
public class DynamicsMetadataServiceInsertEndOrdinalTest {

    private static final String TARGET = "SE_A[40].F_A.IG_A.I_A";
    private static final String DESTINATION_OID = "IG_B[END].I_B";
    private static final String DESTINATION = "SE_A[40].F_A.IG_B[END].I_B";

    @Test
    public void endDestination_fromANonRepeatingSource_writesTheNewLastRow() {
        ItemDataBean source = itemData(1, 10, 1);
        EventCRFBean eventCrf = new EventCRFBean();
        eventCrf.setId(20);
        eventCrf.setCRFVersionId(30);
        eventCrf.setStudyEventId(40);
        ItemGroupMetadataBean nonRepeating = groupMetadata(1, 1);
        ItemGroupMetadataBean repeating = groupMetadata(1, 40);
        ItemBean itemB = new ItemBean();
        itemB.setId(11);
        ItemGroupBean groupB = new ItemGroupBean();
        groupB.setId(12);
        ItemFormMetadataBean inThisForm = new ItemFormMetadataBean();
        inThisForm.setId(5);
        ItemDataBean row1 = itemData(101, 11, 1);
        ItemDataBean row2 = itemData(102, 11, 2);
        ItemDataBean newRow = itemData(103, 11, 3);
        List<Object> updated = new ArrayList<>();

        try (MockedConstruction<UserAccountDAO> _ = mockConstruction(UserAccountDAO.class);
             MockedConstruction<StudyEventDAO> _ = mockConstruction(StudyEventDAO.class);
             MockedConstruction<EventCRFDAO> _ = mockConstruction(EventCRFDAO.class,
                     (m, _) -> when(m.findByPK(20)).thenReturn(eventCrf));
             MockedConstruction<ItemGroupMetadataDAO> _ = mockConstruction(ItemGroupMetadataDAO.class,
                     (m, _) -> {
                         when(m.findByItemAndCrfVersion(10, 30)).thenReturn(nonRepeating);
                         when(m.findByItemAndCrfVersion(11, 30)).thenReturn(repeating);
                     });
             MockedConstruction<ItemFormMetadataDAO> _ = mockConstruction(ItemFormMetadataDAO.class,
                     (m, _) -> when(m.findByItemIdAndCRFVersionId(11, 30)).thenReturn(inThisForm));
             MockedConstruction<ItemDAO> _ = mockConstruction(ItemDAO.class,
                     (m, _) -> when(m.findAllItemsByGroupId(12, 30)).thenReturn(List.of(itemB)));
             MockedConstruction<ItemDataDAO> _ = mockConstruction(ItemDataDAO.class,
                     (m, _) -> {
                         when(m.findByPK(1)).thenReturn(source);
                         when(m.getMaxOrdinalForGroupByItemAndEventCrf(11, eventCrf)).thenReturn(2);
                         when(m.findByItemIdAndEventCRFIdAndOrdinal(11, 20, 3)).thenReturn(new ItemDataBean());
                         when(m.create(any(ItemDataBean.class))).thenReturn(newRow);
                         when(m.findAllByEventCRFIdAndItemId(20, 11))
                                 .thenReturn(new ArrayList<>(List.of(row1, row2, newRow)));
                         doAnswer(invocation -> {
                             updated.add(invocation.getArgument(0));
                             return invocation.getArgument(0);
                         }).when(m).updateValue(any(), anyString());
                     })) {

            ExpressionService expressions = mock(ExpressionService.class);
            when(expressions.getGroupOrdninalCurated(TARGET)).thenReturn("");
            when(expressions.constructFullExpressionIfPartialProvided(DESTINATION_OID, TARGET)).thenReturn(DESTINATION);
            when(expressions.getItemBeanFromExpression(DESTINATION)).thenReturn(itemB);
            when(expressions.getItemGroupExpression(DESTINATION)).thenReturn(groupB);
            when(expressions.getGroupOrdninalCurated(DESTINATION)).thenReturn("END");

            DynamicsMetadataService service = new DynamicsMetadataService(mock(DataSource.class));
            service.setExpressionService(expressions);

            RuleSetBean ruleSet = new RuleSetBean();
            ruleSet.setTarget(new ExpressionBean(Context.OC_RULES_V1, TARGET));
            PropertyBean property = new PropertyBean();
            property.setOid(DESTINATION_OID);
            property.setValue("Y");

            service.insert(1, List.of(property), new UserAccountBean(), ruleSet, null);

            assertEquals(List.of(newRow), updated);
            assertEquals("Y", newRow.getValue());
        }
    }

    private static ItemDataBean itemData(int id, int itemId, int ordinal) {
        ItemDataBean bean = new ItemDataBean();
        bean.setId(id);
        bean.setItemId(itemId);
        bean.setEventCRFId(20);
        bean.setOrdinal(ordinal);
        return bean;
    }

    private static ItemGroupMetadataBean groupMetadata(int repeatNum, int repeatMax) {
        ItemGroupMetadataBean bean = new ItemGroupMetadataBean();
        bean.setRepeatNum(repeatNum);
        bean.setRepeatMax(repeatMax);
        return bean;
    }
}
