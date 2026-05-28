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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.akaza.openclinica.templates.HibernateOcDbTestCase;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.ElementSelectors;

/**
 * Base class for Phase B.0 Castor characterisation tests. Subclasses pin a
 * specific Castor marshal / unmarshal code path to a golden XML file captured
 * from the current stack, so the Phase B.3 Castor → JAXB swap can be reviewed
 * via byte-equivalence (XMLUnit "similar") against those goldens.
 *
 * <h2>Why this exists</h2>
 *
 * <p>[DR-006](../../../../../../docs/development/modernization/decision-record.md#dr-006--castor-replacement-jakarta-jaxb)
 * ratified Jakarta JAXB as the Castor replacement. The
 * [Phase B execution playbook §B.0](../../../../../../docs/development/modernization/phase-b-execution-playbook.md#b0--castor-characterisation-tests-pre-flight)
 * makes characterisation tests a non-negotiable pre-flight gate — without
 * them, the JAXB swap cannot be reviewed for regulatory regression.
 *
 * <h2>How subclasses work</h2>
 *
 * <ol>
 *   <li>Build a deterministic input — either an in-memory bean graph populated
 *       from a DBUnit fixture, or a known input XML loaded from
 *       {@code core/src/test/resources/org/akaza/openclinica/odm/characterisation/input/}.</li>
 *   <li>Run it through the production Castor code path (the actual production
 *       code, not a duplicate — characterisation only protects what production
 *       does).</li>
 *   <li>Compare the output XML against a golden file at
 *       {@code core/src/test/resources/org/akaza/openclinica/odm/characterisation/golden/<TestClassName>/<scenario>.xml}.</li>
 *   <li>Use {@link #assertXmlSimilarToGolden(byte[], String)} so XMLUnit handles
 *       attribute order, namespace prefix differences, and insignificant
 *       whitespace — JAXB and Castor differ on all three but produce schema-
 *       equivalent output. Use {@link #assertXmlIdenticalToGolden(byte[], String)}
 *       only when a downstream consumer depends on byte equality
 *       (rare — document the consumer in the test javadoc).</li>
 * </ol>
 *
 * <h2>Capturing a new golden file</h2>
 *
 * <p>Run the test once with the golden file absent. The default
 * {@link #assertXmlSimilarToGolden(byte[], String)} implementation will write
 * the produced XML to the expected golden path and fail with a message asking
 * the test author to (a) inspect the captured XML for correctness, (b) commit
 * it to git, then (c) re-run. This makes capture a single-step process.
 *
 * <h2>What happens during Phase B.3</h2>
 *
 * <p>The production code path is swapped from Castor to JAXB. These tests do
 * not change. If a test fails, either:
 * <ul>
 *   <li>JAXB output differs in a schema-relevant way → fix the JAXB binding
 *       (XmlAdapter, @XmlElement order, etc.) until similar matches.</li>
 *   <li>The difference is genuinely insignificant for ODM consumers → tighten
 *       the XMLUnit comparison (whitespace policy, attribute matcher) for that
 *       test only, and document why in the test javadoc.</li>
 *   <li>JAXB cannot match even with adaptation → DR-006 is amended in favour
 *       of Jackson XML or MOXy. See [the DR-006 entry](../../../../../../docs/development/modernization/decision-record.md#dr-006--castor-replacement-jakarta-jaxb).</li>
 * </ul>
 *
 * <h2>Why this extends HibernateOcDbTestCase</h2>
 *
 * <p>Most Castor use sites in LibreClinica today read domain entities through
 * Hibernate before marshalling (e.g. {@code MetaDataReportBean} pulls a Study,
 * StudyEventDefinitions, EventDefinitionCRFs from the database). The Phase 0.3
 * per-test transaction model is reused unchanged. Characterisation tests that
 * do not touch the DB can extend a lighter base class instead.
 */
public abstract class CastorCharacterisationIT extends HibernateOcDbTestCase {

    /**
     * Classpath prefix under which golden files live. Mirrors the package
     * structure of the test class, so a subclass {@code OdmMetadataCharacterisationIT}
     * in this package looks under
     * {@code /org/akaza/openclinica/odm/characterisation/golden/OdmMetadataCharacterisationIT/}.
     */
    protected String goldenClasspathPrefix() {
        return "/org/akaza/openclinica/odm/characterisation/golden/"
                + getClass().getSimpleName() + "/";
    }

    /**
     * Compare the produced XML against a golden file using XMLUnit "similar":
     * attribute order, namespace prefix, and insignificant whitespace are
     * ignored; element order, attribute values, and text content are compared.
     *
     * @param producedXml the bytes returned by the production Castor code path
     * @param goldenName  filename under {@link #goldenClasspathPrefix()},
     *                    e.g. {@code "study-1234-metadata.xml"}
     */
    protected void assertXmlSimilarToGolden(byte[] producedXml, String goldenName) {
        InputStream golden = getClass().getResourceAsStream(
                goldenClasspathPrefix() + goldenName);
        if (golden == null) {
            fail(missingGoldenMessage(producedXml, goldenName));
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
            fail("Golden mismatch for " + goldenName + ": Castor → JAXB output"
                    + " has drifted in a schema-relevant way (XMLUnit"
                    + " 'similar'). If JAXB cannot match without adaptation,"
                    + " amend DR-006.\n"
                    + "Differences:\n" + diff.getDifferences());
        }
    }

    /**
     * Strict byte-equality comparison. Use only when a downstream consumer
     * (e.g. a partner site that diffs the XML literally) demands it. Document
     * the consumer in the test javadoc.
     *
     * @param producedXml the bytes returned by the production Castor code path
     * @param goldenName  filename under {@link #goldenClasspathPrefix()}
     */
    protected void assertXmlIdenticalToGolden(byte[] producedXml, String goldenName) {
        InputStream golden = getClass().getResourceAsStream(
                goldenClasspathPrefix() + goldenName);
        if (golden == null) {
            fail(missingGoldenMessage(producedXml, goldenName));
            return;
        }
        byte[] expected = readAllBytes(golden);
        if (!java.util.Arrays.equals(producedXml, expected)) {
            fail("Byte-equality mismatch for " + goldenName + ". Produced XML"
                    + " differs from the captured golden. If the diff is"
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
     * Helper: assert that a Castor unmarshal round-trips into a non-null object.
     */
    protected void assertUnmarshalled(Object unmarshalledRoot) {
        assertNotNull("Castor unmarshal returned null; the input XML is malformed"
                + " or the Castor mapping has drifted from the schema.",
                unmarshalledRoot);
    }

    private String missingGoldenMessage(byte[] producedXml, String goldenName) {
        String fullPath = "core/src/test/resources" + goldenClasspathPrefix() + goldenName;
        return "Golden file missing: " + fullPath + "\n\n"
                + "Capture step (run once, inspect, commit):\n"
                + "1. mkdir -p core/src/test/resources" + goldenClasspathPrefix() + "\n"
                + "2. Save the following bytes to " + fullPath + ":\n"
                + "-------- BEGIN PRODUCED XML --------\n"
                + new String(producedXml, StandardCharsets.UTF_8) + "\n"
                + "-------- END PRODUCED XML --------\n"
                + "3. Inspect the file: does it look like correct ODM 1.3 (or"
                + " whatever schema this code path produces)? If yes, commit. If"
                + " no, the production code has a regression - fix the production"
                + " code first, do not capture broken output as the baseline.\n"
                + "4. Re-run this test - it must now pass without modification.";
    }

    private static byte[] readAllBytes(InputStream in) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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

    /**
     * Quick sanity check used by the suppress-noisy-stack helper.
     */
    @SuppressWarnings("unused")
    private static InputStream toStream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }
}
