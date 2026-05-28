/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.odm.characterisation;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.ElementSelectors;

/**
 * Static helpers for golden-file XML comparison used by Castor characterisation
 * tests. Factored out of the original {@link CastorCharacterisationIT} base
 * class so that <em>unit</em> characterisation tests (those that drive Castor
 * directly with an in-memory bean, no DB required) can use the same comparison
 * logic without inheriting the DB lifecycle.
 *
 * <p>See {@link CastorCharacterisationIT} for the DB-driven base class and the
 * full doc on the characterisation pattern.
 *
 * <h2>Capturing a golden on first run</h2>
 *
 * <p>If the golden file is absent, {@link #assertXmlSimilarToGolden(byte[], Class, String)}
 * fails with a message containing (a) the expected file path under
 * {@code core/src/test/resources/...} and (b) the bytes the test produced.
 * The test author saves those bytes to disk, inspects, commits, and re-runs.
 * After capture, no test changes are needed.
 */
public final class GoldenAssertions {

    private GoldenAssertions() {
        // static-only
    }

    /**
     * Classpath prefix under which a test's golden files live. Mirrors the
     * package structure of the test class, so a test class
     * {@code OdmMetadataCharacterisationIT} in
     * {@code org.akaza.openclinica.odm.characterisation} looks under
     * {@code /org/akaza/openclinica/odm/characterisation/golden/OdmMetadataCharacterisationIT/}.
     */
    public static String goldenClasspathPrefix(Class<?> testClass) {
        return "/" + testClass.getPackage().getName().replace('.', '/')
                + "/golden/" + testClass.getSimpleName() + "/";
    }

    /**
     * Compare the produced XML against a golden file using XMLUnit
     * <em>similar</em>: attribute order, namespace prefix, and insignificant
     * whitespace are ignored; element order, attribute values, and text
     * content are compared.
     *
     * @param producedXml the bytes returned by the production Castor code path
     * @param testClass   the test class (provides the classpath prefix)
     * @param goldenName  filename under {@link #goldenClasspathPrefix(Class)},
     *                    e.g. {@code "rules-empty-container.xml"}
     */
    public static void assertXmlSimilarToGolden(byte[] producedXml,
                                                Class<?> testClass,
                                                String goldenName) {
        String prefix = goldenClasspathPrefix(testClass);
        InputStream golden = testClass.getResourceAsStream(prefix + goldenName);
        if (golden == null) {
            fail(missingGoldenMessage(producedXml, prefix, goldenName));
            return; // unreachable; appeases the compiler
        }
        Diff diff = DiffBuilder.compare(Input.fromStream(golden).build())
                .withTest(Input.fromByteArray(producedXml).build())
                .ignoreWhitespace()
                .ignoreComments()
                .withNodeMatcher(new DefaultNodeMatcher(
                        ElementSelectors.byNameAndText))
                .checkForSimilar()
                .build();
        if (diff.hasDifferences()) {
            fail("Golden mismatch for " + goldenName + ": output has drifted"
                    + " in a schema-relevant way (XMLUnit 'similar'). When"
                    + " this fails during the Phase B.3 Castor -> JAXB swap,"
                    + " either fix the JAXB binding (XmlAdapter, @XmlElement"
                    + " order, etc.) until similar matches, or amend DR-006"
                    + " in favour of Jackson XML / MOXy.\n"
                    + "Differences:\n" + diff.getDifferences()
                    + "\n--- produced (UTF-8) ---\n"
                    + new String(producedXml, StandardCharsets.UTF_8));
        }
    }

    /**
     * Strict byte-equality comparison. Use only when a downstream consumer
     * (e.g. a partner site that diffs the XML literally) demands it. The test
     * javadoc should document the consumer.
     */
    public static void assertXmlIdenticalToGolden(byte[] producedXml,
                                                  Class<?> testClass,
                                                  String goldenName) {
        String prefix = goldenClasspathPrefix(testClass);
        InputStream golden = testClass.getResourceAsStream(prefix + goldenName);
        if (golden == null) {
            fail(missingGoldenMessage(producedXml, prefix, goldenName));
            return;
        }
        byte[] expected = readAllBytes(golden);
        if (!java.util.Arrays.equals(producedXml, expected)) {
            fail("Byte-equality mismatch for " + goldenName + ". Produced"
                    + " XML differs from the captured golden. If the diff is"
                    + " whitespace / attribute order, switch to"
                    + " assertXmlSimilarToGolden(...) instead - byte equality"
                    + " is only appropriate when a downstream consumer demands"
                    + " literal equality.\n--- produced (UTF-8) ---\n"
                    + new String(producedXml, StandardCharsets.UTF_8)
                    + "\n--- expected (UTF-8) ---\n"
                    + new String(expected, StandardCharsets.UTF_8));
        }
    }

    /**
     * Convenience for tests that want to assert a Castor unmarshal round-trips
     * into a non-null object.
     */
    public static void assertUnmarshalled(Object unmarshalledRoot) {
        assertNotNull("Castor unmarshal returned null; the input XML is malformed"
                + " or the Castor mapping has drifted from the schema.",
                unmarshalledRoot);
    }

    private static String missingGoldenMessage(byte[] producedXml,
                                               String classpathPrefix,
                                               String goldenName) {
        String fullPath = "core/src/test/resources" + classpathPrefix + goldenName;
        return "Golden file missing: " + fullPath + "\n\n"
                + "Capture step (run once, inspect, commit):\n"
                + "1. mkdir -p core/src/test/resources" + classpathPrefix + "\n"
                + "2. Save the following bytes to " + fullPath + ":\n"
                + "-------- BEGIN PRODUCED XML --------\n"
                + new String(producedXml, StandardCharsets.UTF_8) + "\n"
                + "-------- END PRODUCED XML --------\n"
                + "3. Inspect the file: does it look like correct ODM 1.3 (or"
                + " whatever schema this code path produces)? If yes, commit."
                + " If no, the production code has a regression - fix the"
                + " production code first, do not capture broken output as the"
                + " baseline.\n"
                + "4. Re-run this test - it must now pass without modification.";
    }

    private static byte[] readAllBytes(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read golden file", e);
        }
    }
}
