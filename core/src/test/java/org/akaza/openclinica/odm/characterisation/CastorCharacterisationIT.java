/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.odm.characterisation;

import org.akaza.openclinica.templates.HibernateOcDbTestCase;
import org.dbunit.operation.DatabaseOperation;

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
     * @see GoldenAssertions#assertXmlSimilarToGolden(byte[], Class, String)
     */
    protected void assertXmlSimilarToGolden(byte[] producedXml, String goldenName) {
        GoldenAssertions.assertXmlSimilarToGolden(producedXml, getClass(), goldenName);
    }

    /**
     * @see GoldenAssertions#assertXmlIdenticalToGolden(byte[], Class, String)
     */
    protected void assertXmlIdenticalToGolden(byte[] producedXml, String goldenName) {
        GoldenAssertions.assertXmlIdenticalToGolden(producedXml, getClass(), goldenName);
    }

    /**
     * @see GoldenAssertions#assertUnmarshalled(Object)
     */
    protected void assertUnmarshalled(Object unmarshalledRoot) {
        GoldenAssertions.assertUnmarshalled(unmarshalledRoot);
    }

    /**
     * Override the DBUnit default {@link DatabaseOperation#CLEAN_INSERT} (which
     * issues a {@code DELETE FROM <table>} for every table named in the dataset
     * before inserting). The institutional Liquibase migrations seed several
     * referential rows in {@code study}, {@code user_account}, {@code status},
     * etc. that downstream tables have FKs to - a blanket DELETE on {@code study}
     * trips {@code fk_study_reference_instrument} (or any of a dozen sibling
     * FKs).
     *
     * <p>{@code INSERT} skips the wipe and adds only the fixture rows. Tests
     * pick deliberately unused IDs (typically negative) to avoid colliding with
     * the seed data, and the surrounding Spring transaction rolls back so the
     * inserts do not leak.
     */
    @Override
    protected DatabaseOperation getSetUpOperation() throws Exception {
        return DatabaseOperation.INSERT;
    }

    /**
     * Run a fixture-row {@code DELETE} as the DBUnit tearDown operation. The
     * {@code INSERT} setup auto-commits via the
     * {@link org.apache.commons.dbcp.BasicDataSource} connection (no Spring
     * transaction wraps the DBUnit setUp call, so the inserts persist even
     * when the surrounding Spring test transaction rolls back). Without a
     * matching tearDown delete, the next run would trip on a unique-key
     * violation on the fixture rows.
     *
     * <p>{@code DELETE} (vs. {@code DELETE_ALL}) only removes the rows named
     * in the dataset, so institutional Liquibase seed rows in {@code study},
     * {@code user_account}, etc. are left intact.
     */
    @Override
    protected DatabaseOperation getTearDownOperation() throws Exception {
        return DatabaseOperation.DELETE;
    }
}
