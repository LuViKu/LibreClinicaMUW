/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.logic.expressionTree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Rule expressions compare CRF date items (a date of birth, if collected) on
 * every save. The date helpers used to log each operand at INFO.
 */
public class ExpressionTreeHelperLoggingTest {

    @Test
    public void dateOperandsNeverReachTheLog() {
        Logger logger = (Logger) LoggerFactory.getLogger(ExpressionTreeHelper.class.getName());
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        try {
            assertNotNull(ExpressionTreeHelper.getDate("1961-04-12"));
            try {
                ExpressionTreeHelper.getDateFromddMMMyyyyDashes("12-Apr-1961");
            } catch (RuntimeException localeWithoutEnglishMonths) {
                // the parse depends on the JVM locale; only the logging matters here
            }
            assertTrue(ExpressionTreeHelper.isDateyyyyMMddDashes("1961-04-12"));
            assertFalse(ExpressionTreeHelper.isDateddMMMyyyyDashes("1961-04-12"));
            assertFalse(ExpressionTreeHelper.isDateddMMMyyyyDashes("12-Apr-61"));

            for (ILoggingEvent e : appender.list) {
                assertFalse(e.getFormattedMessage(), e.getFormattedMessage().contains("1961"));
                assertFalse(e.getFormattedMessage(), e.getFormattedMessage().contains("-61"));
            }
            for (ILoggingEvent e : appender.list) {
                assertEquals(e.getFormattedMessage(), Level.DEBUG, e.getLevel());
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}
