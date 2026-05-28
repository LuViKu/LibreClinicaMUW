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
import org.castor.xml.XMLConfiguration;
import org.exolab.castor.mapping.Mapping;
import org.exolab.castor.xml.Marshaller;
import org.exolab.castor.xml.XMLContext;
import org.junit.Test;
import org.xml.sax.InputSource;

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

        byte[] xml = marshalViaCastor(empty);

        assertXmlSimilarToGolden(xml,
                CastorRulesContainerCharacterisationTest.class,
                "empty-container.xml");
    }

    /**
     * Production code path lifted verbatim from
     * {@code MetaDataReportBean.handleLoadCastor(RulesPostImportContainer)}.
     * The exception flow is collapsed into a single {@code throws Exception}
     * because the test only cares about the marshal result, not which Castor
     * exception type a regression might raise.
     */
    private static byte[] marshalViaCastor(RulesPostImportContainer rpic) throws Exception {
        // Castor's Mapping.loadMapping is lazy on the SAX side - if the stream
        // closes before marshalling starts, the marshal fails with "Stream
        // closed". Read the mapping into a byte buffer upfront and hand
        // Castor a fresh stream over the buffer.
        byte[] mappingBytes = readClasspathResource(
                "/properties/mappingMarshallerMetadata.xml");
        Mapping mapping = new Mapping();
        mapping.loadMapping(new InputSource(new ByteArrayInputStream(mappingBytes)));

        XMLContext xmlContext = new XMLContext();
        xmlContext.setProperty(XMLConfiguration.NAMESPACES, "true");
        xmlContext.addMapping(mapping);

        StringWriter writer = new StringWriter();
        Marshaller marshaller = xmlContext.createMarshaller();
        marshaller.setWriter(writer);
        marshaller.marshal(rpic);

        // Match the production code path: drop the XML prolog so consumers
        // that concatenate the result into a larger document don't choke.
        String result = writer.toString().replace(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "");
        return result.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readClasspathResource(String path) throws Exception {
        try (InputStream in = CastorRulesContainerCharacterisationTest.class
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Castor mapping missing: " + path + " not on the"
                        + " classpath. If this file has been renamed or moved,"
                        + " update the path here and document the move in the"
                        + " Phase B.3 migration plan.");
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
