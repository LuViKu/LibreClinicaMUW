/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** The requested path is client input: it must not reach the log with a line break in it. */
class InternetFacingPathBlockFilterLogTest {

    private final Logger filterLogger = (Logger) LoggerFactory.getLogger(InternetFacingPathBlockFilter.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Level savedLevel;

    @BeforeEach
    void capture() {
        savedLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);
        logged.start();
        filterLogger.addAppender(logged);
    }

    @AfterEach
    void release() {
        filterLogger.detachAppender(logged);
        filterLogger.setLevel(savedLevel);
    }

    @Test
    void aBlockedPathWithLineBreaksIsLoggedOnOneLine() throws Exception {
        InternetFacingPathBlockFilter filter = new InternetFacingPathBlockFilter("/pages/api/v1/**");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/pages/api/v1/x\r\nforged line");
        request.setServletPath("");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(404, response.getStatus());
        assertEquals(1, logged.list.size());
        String line = logged.list.get(0).getFormattedMessage();
        assertFalse(line.contains("\n") || line.contains("\r"), line);
    }
}
