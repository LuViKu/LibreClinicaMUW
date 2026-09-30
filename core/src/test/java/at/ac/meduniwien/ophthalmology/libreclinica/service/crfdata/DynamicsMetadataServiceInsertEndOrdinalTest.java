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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 *
 * <p>The destination group's rows live in the test. create() adds a row and
 * findAllByEventCRFIdAndItemId() lists the rows there are, so a value can land
 * in a new row only if the service created that row.
 */
public class DynamicsMetadataServiceInsertEndOrdinalTest {

    private static final String TARGET = "SE_A[40].F_A.IG_A.I_A";
    private static final int ITEM_A = 10;
    private static final int ITEM_B = 11;
    private static final int GROUP_B = 12;
    private static final int EVENT_CRF = 20;
    private static final int CRF_VERSION = 30;
    private static final int REPEAT_MAX = 40;

    @Test
    public void endDestination_fromANonRepeatingSource_writesTheNewLastRow() {
        Rows rows = insert("END", 2);

        assertEquals(1, rows.created.size());
        ItemDataBean newRow = rows.created.get(0);
        assertEquals(3, newRow.getOrdinal());
        assertEquals(ITEM_B, newRow.getItemId());
        assertEquals(EVENT_CRF, newRow.getEventCRFId());
        assertEquals(List.of(newRow), rows.updated);
        assertEquals("Y", newRow.getValue());
    }

    /**
     * At repeat_max no row can be added, and oneToEndMany hands back the
     * existing last row: the value overwrites it. A repeating source has
     * always taken this path; a non-repeating one reaches it since its [END]
     * destination goes to the END branch.
     */
    @Test
    public void endDestination_fromANonRepeatingSource_whenTheGroupIsFull_overwritesTheLastRow() {
        Rows rows = insert("END", REPEAT_MAX);

        assertEquals(List.of(), rows.created);
        ItemDataBean lastRow = rows.all.get(REPEAT_MAX - 1);
        assertEquals(REPEAT_MAX, lastRow.getOrdinal());
        assertEquals(List.of(lastRow), rows.updated);
        assertEquals("Y", lastRow.getValue());
    }

    @Test
    public void numericDestination_fromANonRepeatingSource_writesOnlyThatRow() {
        Rows rows = insert("2", 3);

        assertEquals(List.of(), rows.created);
        ItemDataBean row2 = rows.all.get(1);
        assertEquals(2, row2.getOrdinal());
        assertEquals(List.of(row2), rows.updated);
        assertEquals("Y", row2.getValue());
    }

    /**
     * Runs an InsertAction from a non-repeating item into IG_B[ordinal].I_B, a
     * repeating group in the same form that already has {@code existingRows}
     * rows, each holding the value "earlier".
     */
    private static Rows insert(String ordinal, int existingRows) {
        String destinationOid = "IG_B[" + ordinal + "].I_B";
        String destination = "SE_A[40].F_A." + destinationOid;
        ItemDataBean source = itemData(1, ITEM_A, 1);
        EventCRFBean eventCrf = new EventCRFBean();
        eventCrf.setId(EVENT_CRF);
        eventCrf.setCRFVersionId(CRF_VERSION);
        eventCrf.setStudyEventId(40);
        ItemGroupMetadataBean nonRepeating = groupMetadata(1, 1);
        ItemGroupMetadataBean repeating = groupMetadata(1, REPEAT_MAX);
        ItemBean itemB = new ItemBean();
        itemB.setId(ITEM_B);
        ItemGroupBean groupB = new ItemGroupBean();
        groupB.setId(GROUP_B);
        ItemFormMetadataBean inThisForm = new ItemFormMetadataBean();
        inThisForm.setId(5);
        Rows rows = new Rows();
        for (int n = 1; n <= existingRows; n++) {
            ItemDataBean row = itemData(100 + n, ITEM_B, n);
            row.setValue("earlier");
            rows.all.add(row);
        }

        try (MockedConstruction<UserAccountDAO> _ = mockConstruction(UserAccountDAO.class);
             MockedConstruction<StudyEventDAO> _ = mockConstruction(StudyEventDAO.class);
             MockedConstruction<EventCRFDAO> _ = mockConstruction(EventCRFDAO.class,
                     (m, _) -> when(m.findByPK(EVENT_CRF)).thenReturn(eventCrf));
             MockedConstruction<ItemGroupMetadataDAO> _ = mockConstruction(ItemGroupMetadataDAO.class,
                     (m, _) -> {
                         when(m.findByItemAndCrfVersion(ITEM_A, CRF_VERSION)).thenReturn(nonRepeating);
                         when(m.findByItemAndCrfVersion(ITEM_B, CRF_VERSION)).thenReturn(repeating);
                     });
             MockedConstruction<ItemFormMetadataDAO> _ = mockConstruction(ItemFormMetadataDAO.class,
                     (m, _) -> when(m.findByItemIdAndCRFVersionId(ITEM_B, CRF_VERSION)).thenReturn(inThisForm));
             MockedConstruction<ItemDAO> _ = mockConstruction(ItemDAO.class,
                     (m, _) -> when(m.findAllItemsByGroupId(GROUP_B, CRF_VERSION)).thenReturn(List.of(itemB)));
             MockedConstruction<ItemDataDAO> _ = mockConstruction(ItemDataDAO.class,
                     (m, _) -> {
                         when(m.findByPK(1)).thenReturn(source);
                         rows.stub(m, eventCrf);
                     })) {

            ExpressionService expressions = mock(ExpressionService.class);
            when(expressions.getGroupOrdninalCurated(TARGET)).thenReturn("");
            when(expressions.constructFullExpressionIfPartialProvided(destinationOid, TARGET)).thenReturn(destination);
            when(expressions.getItemBeanFromExpression(destination)).thenReturn(itemB);
            when(expressions.getItemGroupExpression(destination)).thenReturn(groupB);
            when(expressions.getGroupOrdninalCurated(destination)).thenReturn(ordinal);

            DynamicsMetadataService service = new DynamicsMetadataService(mock(DataSource.class));
            service.setExpressionService(expressions);

            RuleSetBean ruleSet = new RuleSetBean();
            ruleSet.setTarget(new ExpressionBean(Context.OC_RULES_V1, TARGET));
            PropertyBean property = new PropertyBean();
            property.setOid(destinationOid);
            property.setValue("Y");

            service.insert(1, List.of(property), new UserAccountBean(), ruleSet, null);
        }
        return rows;
    }

    /**
     * The destination item's rows, shared by every ItemDataDAO the service
     * constructs (it builds a new one for each call), and what it did to them.
     */
    private static final class Rows {

        final List<ItemDataBean> all = new ArrayList<>();
        final List<ItemDataBean> created = new ArrayList<>();
        final List<Object> updated = new ArrayList<>();

        void stub(ItemDataDAO m, EventCRFBean eventCrf) {
            when(m.getGroupSize(ITEM_B, EVENT_CRF)).thenAnswer(_ -> all.size());
            when(m.getMaxOrdinalForGroupByItemAndEventCrf(ITEM_B, eventCrf))
                    .thenAnswer(_ -> all.stream().mapToInt(ItemDataBean::getOrdinal).max().orElse(0));
            when(m.findByItemIdAndEventCRFIdAndOrdinal(eq(ITEM_B), eq(EVENT_CRF), anyInt()))
                    .thenAnswer(invocation -> withOrdinal(invocation.getArgument(2)));
            when(m.create(any(ItemDataBean.class))).thenAnswer(invocation -> {
                ItemDataBean row = invocation.getArgument(0);
                row.setId(200 + created.size());
                created.add(row);
                all.add(row);
                return row;
            });
            when(m.findAllByEventCRFIdAndItemId(EVENT_CRF, ITEM_B)).thenAnswer(_ -> new ArrayList<>(all));
            when(m.updateValue(any(), anyString())).thenAnswer(invocation -> {
                updated.add(invocation.getArgument(0));
                return invocation.getArgument(0);
            });
        }

        private ItemDataBean withOrdinal(int ordinal) {
            for (ItemDataBean row : all) {
                if (row.getOrdinal() == ordinal) {
                    return row;
                }
            }
            return new ItemDataBean();
        }
    }

    private static ItemDataBean itemData(int id, int itemId, int ordinal) {
        ItemDataBean bean = new ItemDataBean();
        bean.setId(id);
        bean.setItemId(itemId);
        bean.setEventCRFId(EVENT_CRF);
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
