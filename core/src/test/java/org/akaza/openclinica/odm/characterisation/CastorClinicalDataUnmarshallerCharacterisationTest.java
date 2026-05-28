/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.odm.characterisation;

import static org.akaza.openclinica.odm.characterisation.GoldenAssertions.assertUnmarshalled;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;

import org.akaza.openclinica.bean.submit.crfdata.CRFDataPostImportContainer;
import org.akaza.openclinica.bean.submit.crfdata.ODMContainer;
import org.exolab.castor.mapping.Mapping;
import org.exolab.castor.xml.Unmarshaller;
import org.junit.Test;
import org.xml.sax.InputSource;

/**
 * Phase B.0 characterisation for the clinical-data ODM import path driven
 * by {@code ImportCRFDataServlet}. Castor unmarshals an institutional
 * CDISC ODM 1.3 clinical-data document (with the OpenClinica extension
 * namespace) into an {@link ODMContainer}, which the production code then
 * walks to populate {@code ItemData} rows.
 *
 * <p>Uses {@code properties/cd_odm_mapping.xml} — the clinical-data ODM
 * Castor mapping, distinct from the three rule mappings characterised by
 * the other tests in this package.
 *
 * <p>Why characterise <em>only</em> the empty-envelope shape:
 * a complete CRF-data ODM document carries study OIDs, subject keys, event
 * OIDs, and ItemData rows that reference rows in a live database. Asserting
 * post-parse content requires multi-entity fixtures and is a Phase 0
 * integration-test backlog item ({@code OdmImportRoundTripIT}, item #17 in
 * MIGRATION.md). This test pins the wire envelope only — namespaces,
 * root-element class, and the empty-collection contract that the production
 * iterators rely on.
 */
public class CastorClinicalDataUnmarshallerCharacterisationTest {

    /**
     * The smallest deserialisable ODM clinical-data document Castor's
     * {@code cd_odm_mapping.xml} accepts: a single {@code <ClinicalData>}
     * child with a {@code StudyOID} attribute. Castor must parse this
     * without error and yield a non-null {@link ODMContainer}.
     *
     * <p>The mapping deliberately ignores {@code FileOID} / {@code ODMVersion}
     * / {@code CreationDateTime} attributes on the {@code <ODM>} root - they
     * are CDISC-required for institutional ODM exports but are not stored on
     * the Java side because the import flow only cares about the
     * {@code <ClinicalData>} payload. This test pins that ignore behaviour
     * so a Phase B.3 JAXB binding does not surprise the production import
     * code with a populated header.
     */
    @Test
    public void emptyClinicalDataDocumentUnmarshalsToNonNullContainer() throws Exception {
        String inputXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"\n"
                + "     xmlns:OpenClinica=\"http://www.openclinica.org/ns/odm_ext_v130/v3.1\">\n"
                + "  <ClinicalData StudyOID=\"S_MUW_TEST\"/>\n"
                + "</ODM>\n";

        ODMContainer parsed = unmarshalViaCastor(inputXml);

        assertUnmarshalled(parsed);
    }

    /**
     * Pin the StudyOID round-trip. This is the only attribute the production
     * import flow reads to route the CRF data to the correct study, so a
     * Castor-vs-JAXB drift here re-routes imports to the wrong study or
     * fails on a missing OID.
     */
    @Test
    public void studyOidAttributeRoundtripsThroughUnmarshal() throws Exception {
        String inputXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"\n"
                + "     xmlns:OpenClinica=\"http://www.openclinica.org/ns/odm_ext_v130/v3.1\">\n"
                + "  <ClinicalData StudyOID=\"OID.MUW.TEST.1\"/>\n"
                + "</ODM>\n";

        ODMContainer parsed = unmarshalViaCastor(inputXml);
        CRFDataPostImportContainer clinical = parsed.getCrfDataPostImportContainer();

        assertNotNull("ODMContainer.getCrfDataPostImportContainer() must be"
                + " non-null after parsing a well-formed ClinicalData envelope",
                clinical);
        assertEquals("StudyOID round-trips through Castor unmarshal",
                "OID.MUW.TEST.1", clinical.getStudyOID());
    }

    /**
     * Production code path lifted from
     * {@code ImportCRFDataServlet.processCsvOrXml(...)}. The ODM_MAPPING_DIRPath
     * resolution + FileReader substitution is the only adaptation.
     */
    private static ODMContainer unmarshalViaCastor(String inputXml) throws Exception {
        byte[] mappingBytes = readClasspathResource("/properties/cd_odm_mapping.xml");
        Mapping mapping = new Mapping();
        mapping.loadMapping(new InputSource(new ByteArrayInputStream(mappingBytes)));

        Unmarshaller um1 = new Unmarshaller(mapping);
        ODMContainer odmContainer = new ODMContainer();
        um1.setObject(odmContainer);
        odmContainer = (ODMContainer) um1.unmarshal(new InputSource(new StringReader(inputXml)));
        return odmContainer;
    }

    private static byte[] readClasspathResource(String path) throws Exception {
        try (InputStream in = CastorClinicalDataUnmarshallerCharacterisationTest.class
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
