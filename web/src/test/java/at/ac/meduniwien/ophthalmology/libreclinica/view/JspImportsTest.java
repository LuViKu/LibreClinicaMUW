/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Tomcat 10 compiles JSPs against jakarta.servlet only. A page or tag
 * directive that imports a javax.servlet type fails to compile when the page
 * is first requested, and the page renders blank, as
 * admin/configurationPasswordRequirements.jsp did. The build never compiles
 * JSPs, so the imports are checked here.
 */
class JspImportsTest {

    private static final Path WEBAPP = Paths.get("src/main/webapp");

    private static final Pattern IMPORT_DIRECTIVE = Pattern.compile(
            "<%@\\s*(?:page|tag)\\b[^%]*?\\bimport\\s*=\\s*\"([^\"]*)\"", Pattern.DOTALL);

    @Test
    void noJspImportsAJavaxServletType() throws IOException {
        assertTrue(Files.isDirectory(WEBAPP), "run from the web module: " + WEBAPP.toAbsolutePath());

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(WEBAPP)) {
            for (Path file : (Iterable<Path>) files.filter(JspImportsTest::isJspSource)::iterator) {
                // Latin-1 decodes any byte sequence; the directives are ASCII.
                Matcher m = IMPORT_DIRECTIVE.matcher(
                        new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    for (String imported : m.group(1).split(",")) {
                        if (imported.trim().startsWith("javax.servlet")) {
                            offenders.add(WEBAPP.relativize(file) + ": " + imported.trim());
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    private static boolean isJspSource(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".jsp") || name.endsWith(".jspf") || name.endsWith(".tag");
    }
}
