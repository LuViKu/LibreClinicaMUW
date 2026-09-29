/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;

/**
 * The heritage system-status endpoint (2026-09-29).
 *
 * <p>Every call printed the server's whole environment to stdout, which
 * Tomcat writes to its log: database, mail and device credentials passed as
 * environment variables ended up there.
 */
class SystemControllerTest {

    @Test
    void theSystemStatusDoesNotPrintTheEnvironment() throws Exception {
        Map.Entry<String, String> variable = System.getenv().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().length() > 3)
                .findFirst().orElse(null);
        assumeTrue(variable != null, "the test JVM has no environment variable to look for");

        Field datainfo = CoreResources.class.getDeclaredField("DATAINFO");
        datainfo.setAccessible(true);
        Object datainfoBefore = datainfo.get(null);
        if (datainfoBefore == null) {
            datainfo.set(null, new Properties());
        }
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        int status;
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            // No data source: the database probe fails and is logged; the
            // status report itself still comes back.
            status = new SystemController().getSystemStatus().getStatusCode().value();
        } finally {
            System.setOut(stdout);
            datainfo.set(null, datainfoBefore);
        }

        assertEquals(200, status);
        String out = captured.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains(variable.getKey() + "=" + variable.getValue()),
                "the environment variable " + variable.getKey() + " was printed");
    }
}
