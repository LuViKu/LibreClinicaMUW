/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.score;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.Test;
import org.mockito.MockedConstruction;
import org.slf4j.LoggerFactory;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ItemDataType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.core.ResponseType;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemFormMetadataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseOptionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ResponseSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.SessionManager;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemDataDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.submit.ItemFormMetadataDAO;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A legacy calculated item whose formula cannot be evaluated is reported
 * (2026-09-29).
 *
 * <p>The calculator collected these messages in a list nothing ever read, so
 * a broken formula on another section left no trace at all. They are now
 * logged at WARN with the item and the event CRF, and without item values.
 */
public class ScoreCalculatorErrorLoggingTest {

    private static final String SECRET_VALUE = "SECRET-VALUE-4711";

    @Test
    public void aFormulaThatCannotBeEvaluatedIsLoggedWithoutItemValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ScoreCalculator.class.getName());
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        ItemFormMetadataBean total = new ItemFormMetadataBean();
        total.setItemId(7);
        total.setSectionId(99);
        total.setLeftItemText("Score total");
        ResponseSetBean rs = new ResponseSetBean();
        ResponseOptionBean formula = new ResponseOptionBean();
        formula.setValue("A+B");
        rs.addOption(formula);
        total.setResponseSet(rs);
        ArrayList<ItemFormMetadataBean> calculated = new ArrayList<>(List.of(total));

        ItemBean totalItem = new ItemBean();
        totalItem.setId(7);
        totalItem.setName("TOTAL");

        try (MockedConstruction<ItemFormMetadataDAO> ifm = mockConstruction(ItemFormMetadataDAO.class, (m, _) -> {
                when(m.findAllByCRFVersionIdAndResponseTypeId(anyInt(), eq(ResponseType.CALCULATION.getId())))
                        .thenReturn(calculated);
                when(m.findAllByCRFVersionIdAndResponseTypeId(anyInt(), eq(ResponseType.GROUP_CALCULATION.getId())))
                        .thenReturn(new ArrayList<>());
            });
            MockedConstruction<ItemDAO> items = mockConstruction(ItemDAO.class,
                    (m, _) -> when(m.findByPK(7)).thenReturn(totalItem));
            MockedConstruction<ItemDataDAO> data = mockConstruction(ItemDataDAO.class,
                    (m, _) -> when(m.findByItemIdAndEventCRFIdAndOrdinal(anyInt(), anyInt(), anyInt()))
                            .thenReturn(new ItemDataBean()))) {

            SessionManager sm = mock(SessionManager.class);
            when(sm.getDataSource()).thenReturn(mock(DataSource.class));
            EventCRFBean ecb = new EventCRFBean();
            ecb.setId(44);
            ecb.setCRFVersionId(3);

            HashMap<String, ItemBean> byName = new HashMap<>();
            ItemBean a = new ItemBean();
            a.setId(5);
            a.setName("A");
            ItemBean b = new ItemBean();
            b.setId(6);
            b.setName("B");
            byName.put("A", a);
            byName.put("B", b);
            HashMap<String, String> itemdata = new HashMap<>();
            // A has no value, so the formula cannot be evaluated; B's value
            // must not reach the log.
            itemdata.put("6_1", SECRET_VALUE);
            TreeSet<String> changed = new TreeSet<>(List.of("A"));

            new ScoreCalculator(sm, ecb, new UserAccountBean())
                    .redoCalculations(byName, itemdata, changed, new HashMap<>(), 1);

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .collect(Collectors.toList());
            assertEquals(1, warnings.size());
            String message = warnings.get(0).getFormattedMessage();
            assertTrue(message, message.contains("Score total"));
            assertTrue(message, message.contains("44"));
            assertTrue(message, message.contains("A is empty"));
            for (ILoggingEvent e : appender.list) {
                assertFalse(e.getFormattedMessage(), e.getFormattedMessage().contains(SECRET_VALUE));
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }

    /**
     * The other way a calculation fails: an item feeding the formula holds
     * something that is not a number. That value is the participant's own item
     * data, so neither the log nor the message the calculator hands back may
     * carry it.
     *
     * <p>This is the path the test above does not reach. It covers a formula
     * that cannot be evaluated at all ("A is empty"), whose messages never
     * contained a value; getMathContextValue's "Number was expected" message
     * did, and reached the log through the buffer the WARN line prints.
     */
    @Test
    public void aValueThatIsNotANumberReachesNeitherTheLogNorTheMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScoreCalculator.class.getName());
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        try {
            SessionManager sm = mock(SessionManager.class);
            when(sm.getDataSource()).thenReturn(mock(DataSource.class));
            EventCRFBean ecb = new EventCRFBean();
            ecb.setId(44);

            ItemFormMetadataBean ifm = new ItemFormMetadataBean();
            ifm.setWidthDecimal("5(0)");
            ResponseSetBean rs = new ResponseSetBean();
            ResponseOptionBean formula = new ResponseOptionBean();
            formula.setValue("A+B");
            rs.addOption(formula);
            ifm.setResponseSet(rs);

            StringBuffer errors = new StringBuffer();
            String result = new ScoreCalculator(sm, ecb, new UserAccountBean())
                    .getMathContextValue(SECRET_VALUE, ifm, ItemDataType.INTEGER, errors);

            // the value does not parse, so nothing is calculated
            assertEquals("", result);
            // the message says which formula failed ...
            assertTrue(errors.toString(), errors.toString().contains("Number was expected"));
            assertTrue(errors.toString(), errors.toString().contains("A+B"));
            // ... and carries no item value, so the WARN line that prints this
            // buffer cannot leak one either
            assertFalse(errors.toString(), errors.toString().contains(SECRET_VALUE));
            for (ILoggingEvent e : appender.list) {
                assertFalse(e.getFormattedMessage(), e.getFormattedMessage().contains(SECRET_VALUE));
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}
