/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.util.OptionHelper;

/**
 * Values that reach a log call from a request can carry CR/LF. The plain-text
 * appenders must print every message through the ${safeMsg} property, which
 * turns line breaks into spaces, so such a value cannot start a forged line.
 */
public class LogbackLineBreakEscapingTest {

    private static String logbackXml() throws Exception {
        try (InputStream in = LogbackLineBreakEscapingTest.class.getClassLoader().getResourceAsStream("logback.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String safeMsgPattern(String xml) {
        Matcher m = Pattern.compile("<property name=\"safeMsg\" value=\"([^\"]+)\"/>").matcher(xml);
        assertTrue("logback.xml defines the safeMsg property", m.find());
        return m.group(1).replace("&apos;", "'");
    }

    @Test
    public void everyPatternPrintsTheMessageThroughSafeMsg() throws Exception {
        String xml = logbackXml();
        String withoutDefinition = xml.replaceFirst("<property name=\"safeMsg\"[^>]*/>", "");
        assertFalse("a raw %msg in a text pattern lets a CR/LF forge a log line",
                withoutDefinition.contains("%msg") || withoutDefinition.matches("(?s).*%m[ }].*"));
        assertTrue(withoutDefinition.contains("${safeMsg}"));
    }

    @Test
    public void safeMsgTurnsLineBreaksIntoSpaces() throws Exception {
        LoggerContext context = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(safeMsgPattern(logbackXml()));
        layout.start();
        LoggingEvent event = new LoggingEvent(getClass().getName(), context.getLogger("t"), Level.INFO,
                "label=a\r\n2026-09-29 INFO forged\nline", null, null);

        String out = layout.doLayout(event);

        assertFalse(out, out.contains("\n") || out.contains("\r"));
        assertEquals("label=a  2026-09-29 INFO forged line", out);
    }

    /** The console pattern as logback resolves it at startup, properties substituted. */
    @Test
    public void theResolvedConsolePatternKeepsAForgedLineOnOneLine() throws Exception {
        String xml = logbackXml();
        Matcher m = Pattern.compile("<property name=\"consolePattern\"\\s+value=\"([^\"]+)\"/>").matcher(xml);
        assertTrue("logback.xml defines consolePattern", m.find());
        LoggerContext context = new LoggerContext();
        context.putProperty("reqIdField", "[%X{reqId:-}]");
        context.putProperty("safeMsg", safeMsgPattern(xml));
        String resolved = OptionHelper.substVars(m.group(1).replace("&apos;", "'"), context);
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(resolved);
        layout.start();
        LoggingEvent event = new LoggingEvent(getClass().getName(), context.getLogger("t"), Level.WARN,
                "subject=HAE-003\n09/29 10:00:00.000 [main] [] INFO  x - forged", null, null);
        event.setMDCPropertyMap(java.util.Collections.emptyMap());

        String out = layout.doLayout(event);

        assertEquals(out, 1, out.trim().split("\n").length);
        assertTrue(out, out.contains("subject=HAE-003 09/29 10:00:00.000 [main] [] INFO  x - forged"));
    }
}
