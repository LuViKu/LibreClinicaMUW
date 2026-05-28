/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.odm.characterisation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.ElementSelectors;

/**
 * Sanity check that the XMLUnit dependency is wired correctly and that the
 * "similar" matcher behaves as expected for the Phase B.0 Castor
 * characterisation use case. No DB, no Castor — runs in the default
 * {@code mvn test} profile so the framework's correctness is verified on
 * every CI build.
 *
 * <p>This is the framework's smoke test, not a Castor characterisation test
 * proper. The latter are subclasses of {@link CastorCharacterisationIT} that
 * live in the integration-tests profile.
 */
public class CastorCharacterisationFrameworkTest {

    /**
     * Two XML documents that differ only in attribute order and whitespace
     * are reported "similar" by XMLUnit. This is the property the Phase B.3
     * Castor → JAXB swap relies on: the byte-level diff between Castor and
     * JAXB output is mostly attribute-order and namespace-prefix noise.
     *
     * <p>Uses {@link DiffBuilder} directly rather than the
     * {@code CompareMatcher} Hamcrest matcher — sidesteps a classpath conflict
     * between Hamcrest 1.3 (transitive via JUnit 4.13.2) and Hamcrest 2.2
     * (explicit), and keeps the framework smoke test usable on the default
     * surefire suite.
     */
    @Test
    public void xmlUnitSimilarIgnoresAttributeOrderAndWhitespace() {
        String a = "<study oid=\"S_1\" name=\"MUW Demo\"><site oid=\"SITE_1\"/></study>";
        String b = "<study   name=\"MUW Demo\"\n    oid=\"S_1\" >\n   <site oid=\"SITE_1\"/>\n</study>";

        Diff diff = DiffBuilder.compare(Input.fromString(a).build())
                .withTest(Input.fromString(b).build())
                .ignoreWhitespace()
                .ignoreComments()
                .withNodeMatcher(new DefaultNodeMatcher(
                        ElementSelectors.byNameAndText))
                .checkForSimilar()
                .build();

        assertFalse("Documents that differ only in attribute order and"
                + " whitespace must be similar - " + diff.getDifferences(),
                diff.hasDifferences());
    }

    /**
     * Differing attribute values are NOT swallowed - XMLUnit must report
     * them as a difference. This is the property the swap relies on for
     * regression detection: a real schema-relevant change in the JAXB output
     * (e.g. dateTime lexical format, decimal precision) must surface.
     */
    @Test
    public void xmlUnitSimilarDetectsDifferingAttributeValues() {
        String a = "<study oid=\"S_1\"/>";
        String b = "<study oid=\"S_2\"/>";

        Diff diff = DiffBuilder.compare(Input.fromString(a).build())
                .withTest(Input.fromString(b).build())
                .checkForSimilar()
                .build();

        assertTrue("Documents with different attribute values must have"
                + " at least one detected difference; if this fails, every"
                + " Castor → JAXB regression silently passes the gate.",
                diff.hasDifferences());
    }
}
