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
 * Phase B.0 characterisation for the rules-XML <em>marshaller</em> code path
 * driven by {@code DownloadRuleSetXmlServlet.handleLoadCastor}.
 *
 * <p>Distinct from {@link CastorRulesContainerCharacterisationTest}: that one
 * uses {@code properties/mappingMarshallerMetadata.xml} (the metadata mapping
 * embedded inside the ODM metadata extract). This one uses
 * {@code properties/mappingMarshaller.xml} — the standalone rule-set export
 * mapping that ships to administrators via the "Download Rule Set XML"
 * UI feature.
 *
 * <p>Both mapping files declare {@link RulesPostImportContainer} as the root
 * but yield different XML envelopes; both must be byte-similar on JAXB output
 * for Phase B.3 to merge cleanly.
 */
public class CastorRulesMarshallerCharacterisationTest {

    @Test
    public void emptyContainerMarshalsToRootElementOnly() throws Exception {
        RulesPostImportContainer empty = new RulesPostImportContainer();

        byte[] xml = marshalViaJaxb(empty);

        assertXmlSimilarToGolden(xml,
                CastorRulesMarshallerCharacterisationTest.class,
                "empty-container.xml");
    }

    /**
     * Production code path equivalent to
     * {@code DownloadRuleSetXmlServlet.handleLoadCastor(OutputStream, RulesPostImportContainer)}
     * after Phase B.3 PR 1/3 swapped Castor → {@code javax.xml.bind} 2.3.x
     * JAXB via {@link OdmJaxbContext}.
     */
    private static byte[] marshalViaJaxb(RulesPostImportContainer rpic) {
        OdmJaxbContext ctx = new OdmJaxbContext();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ctx.marshalRulesExport(rpic, out);
        return out.toByteArray();
    }
}
