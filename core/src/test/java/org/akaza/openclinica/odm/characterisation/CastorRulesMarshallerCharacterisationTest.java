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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.akaza.openclinica.domain.rule.RulesPostImportContainer;
import org.exolab.castor.mapping.Mapping;
import org.exolab.castor.xml.Marshaller;
import org.exolab.castor.xml.XMLContext;
import org.junit.Test;
import org.xml.sax.InputSource;

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

        byte[] xml = marshalViaCastor(empty);

        assertXmlSimilarToGolden(xml,
                CastorRulesMarshallerCharacterisationTest.class,
                "empty-container.xml");
    }

    /**
     * Production code path lifted from
     * {@code DownloadRuleSetXmlServlet.handleLoadCastor(FileWriter, RulesPostImportContainer)}.
     * The {@code FileWriter} → {@code StringWriter} substitution is the only
     * adaptation: the test does not care about the output destination, only
     * the bytes Castor produces.
     */
    private static byte[] marshalViaCastor(RulesPostImportContainer rpic) throws Exception {
        byte[] mappingBytes = readClasspathResource(
                "/properties/mappingMarshaller.xml");
        Mapping mapping = new Mapping();
        mapping.loadMapping(new InputSource(new ByteArrayInputStream(mappingBytes)));

        XMLContext xmlContext = new XMLContext();
        xmlContext.addMapping(mapping);

        StringWriter writer = new StringWriter();
        Marshaller marshaller = xmlContext.createMarshaller();
        marshaller.setWriter(writer);
        marshaller.marshal(rpic);
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readClasspathResource(String path) throws Exception {
        try (InputStream in = CastorRulesMarshallerCharacterisationTest.class
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Castor mapping missing: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
