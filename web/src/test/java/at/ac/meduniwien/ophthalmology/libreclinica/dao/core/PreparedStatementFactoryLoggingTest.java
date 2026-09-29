/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.PreparedStatement;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Every legacy DAO statement binds its values here, including password
 * hashes, API keys, challenge answers and clinical data. At DEBUG — the level
 * the shipped datainfo.properties sets — the factory used to log each value.
 */
class PreparedStatementFactoryLoggingTest {

    @Test
    void boundValuesNeverReachTheLogEvenAtDebug() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(PreparedStatementFactory.class.getName());
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            HashMap<Integer, Object> variables = new HashMap<>();
            variables.put(1, "SECRET-password-hash-6c1f");
            variables.put(2, Integer.valueOf(42));
            new PreparedStatementFactory(variables).generate(mock(PreparedStatement.class));

            assertFalse(appender.list.isEmpty(), "the type/position debug line is still written");
            for (ILoggingEvent e : appender.list) {
                assertFalse(e.getFormattedMessage().contains("SECRET-password-hash-6c1f"), e.getFormattedMessage());
                assertFalse(e.getFormattedMessage().contains("value["), e.getFormattedMessage());
            }
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("java.lang.String")));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}
