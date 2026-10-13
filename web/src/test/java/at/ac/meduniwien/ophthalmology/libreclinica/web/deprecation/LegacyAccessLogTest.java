/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.deprecation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** A user name with a line break must not start a second log line. */
class LegacyAccessLogTest {

    private final Logger accessLogger = (Logger) LoggerFactory.getLogger(LegacyAccessLog.LOGGER_NAME);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level savedLevel;

    @BeforeEach
    void capture() {
        savedLevel = accessLogger.getLevel();
        accessLogger.setLevel(Level.INFO);
        logged.start();
        accessLogger.addAppender(logged);
    }

    @AfterEach
    void release() {
        accessLogger.detachAppender(logged);
        accessLogger.setLevel(savedLevel);
    }

    @Test
    void aUserNameWithLineBreaksStaysOnOneLine() {
        UserAccountBean user = new UserAccountBean();
        user.setName("root\r\nlegacy-hit path=/forged");
        LegacyAccessLog.hit(new LegacyServletDeprecationCatalog().all().values().iterator().next(),
                new MockHttpServletRequest("GET", "/x"), user, false, LegacyAccessLog.Action.PASS);
        assertEquals(1, logged.list.size());
        String line = logged.list.get(0).getFormattedMessage();
        assertFalse(line.contains("\n") || line.contains("\r"), line);
        assertTrue(line.contains("user=root__legacy-hit path=/forged"), line);
    }
}
