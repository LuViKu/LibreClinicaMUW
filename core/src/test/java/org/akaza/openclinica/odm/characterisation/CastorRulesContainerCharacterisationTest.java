/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.odm.characterisation;

import static org.akaza.openclinica.odm.characterisation.GoldenAssertions.assertXmlSimilarToGolden;

import java.io.ByteArrayOutputStream;

import org.akaza.openclinica.domain.rule.RulesPostImportContainer;
import org.akaza.openclinica.service.xml.OdmJaxbContext;
import org.junit.Test;

/**
 * First Phase B.0 Castor characterisation test. Marshals a known
 * {@link RulesPostImportContainer} via the exact same Castor invocation chain
 * as
 * {@code org.akaza.openclinica.bean.extract.odm.MetaDataReportBean.handleLoadCastor}
 * (private), against the institutional mapping file
 * {@code core/src/main/resources/properties/mappingMarshallerMetadata.xml}.
 *
 * <p>Captures the output as a golden file. Phase B.3 (Castor → JAXB) must
 * produce byte-similar XML for the same input. If it cannot, see DR-006
 * revisit triggers.
 *
 * <p>This is a unit test (no DB): {@code XMLContext} + {@code Mapping} are
 * loaded from the classpath; no Spring, no Hibernate, no Postgres needed.
 * Runs in the default {@code mvn test} profile.
 *
 * <h2>How to add more characterisation scenarios</h2>
 *
 * <p>Add another {@code @Test} method that builds a different
 * {@code RulesPostImportContainer} shape (a container with one
 * {@code RuleSetBean}, with multiple rule defs, with edge-case expressions,
 * etc.), call {@link #marshalViaCastor(RulesPostImportContainer)}, and
 * compare against a new golden filename. Capture-on-first-run handles the
 * golden file creation.
 */
public class CastorRulesContainerCharacterisationTest {

    /**
     * The empty-container case pins the smallest possible Castor output: an
     * empty {@code <Rules>} root with the OpenClinicaRules namespace. If
     * JAXB cannot reproduce this without a custom {@code @XmlRootElement}
     * + namespace declaration, the institutional ODM consumers that look
     * for that namespace prefix break silently.
     */
    @Test
    public void emptyContainerMarshalsToRootElementOnly() throws Exception {
        RulesPostImportContainer empty = new RulesPostImportContainer();

        byte[] xml = marshalViaJaxb(empty);

        assertXmlSimilarToGolden(xml,
                CastorRulesContainerCharacterisationTest.class,
                "empty-container.xml");
    }

    /**
     * Production code path equivalent to
     * {@code MetaDataReportBean.handleLoadCastor(RulesPostImportContainer)}
     * after Phase B.3 PR 2/3 swapped Castor → {@code javax.xml.bind} 2.3.x
     * JAXB via {@link OdmJaxbContext#marshalRulesMetadata}. Output is wrapped
     * in {@code <OpenClinicaRules:Rules ...>} (matching the
     * mappingMarshallerMetadata.xml envelope) and emitted as a fragment
     * (no XML prolog), so the rules block can be concatenated into the larger
     * ODM metadata export.
     */
    private static byte[] marshalViaJaxb(RulesPostImportContainer rpic) {
        OdmJaxbContext ctx = new OdmJaxbContext();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ctx.marshalRulesMetadata(rpic, out);
        return out.toByteArray();
    }
}
