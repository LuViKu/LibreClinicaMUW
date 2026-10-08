/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.xml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXParseException;

/**
 * The WAR ships Apache Xerces, which then wins the JAXP service lookup and
 * ignores the JDK's accessExternal* limits. The XML import parsers must
 * refuse a DOCTYPE on this classpath too.
 */
class SecureXmlOnWarClasspathTest {

    private static final String DOCUMENT_WITH_DOCTYPE =
            "<?xml version=\"1.0\"?><!DOCTYPE data [<!ENTITY v \"42\">]>"
            + "<data><instance><F_VA><VA_OD>&v;</VA_OD></F_VA></instance></data>";

    @Test
    void domParserRefusesDoctype() {
        assertThrows(SAXParseException.class, () -> SecureXmlFactories.newDocumentBuilderFactory()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(DOCUMENT_WITH_DOCTYPE.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void jaxbOdmImportRefusesDoctype() {
        String odm = "<?xml version=\"1.0\"?><!DOCTYPE ODM [<!ENTITY k \"SS_X\">]>"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"><ClinicalData StudyOID=\"S\">"
                + "<SubjectData SubjectKey=\"&k;\"/></ClinicalData></ODM>";
        assertThrows(IllegalStateException.class, () -> new OdmJaxbContext()
                .unmarshalClinicalData(new ByteArrayInputStream(odm.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void plainOdmStillBinds() {
        String odm = "<?xml version=\"1.0\"?>"
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\"><ClinicalData StudyOID=\"S\">"
                + "<SubjectData SubjectKey=\"SS_X\"/></ClinicalData></ODM>";
        assertEquals("SS_X", new OdmJaxbContext()
                .unmarshalClinicalData(new ByteArrayInputStream(odm.getBytes(StandardCharsets.UTF_8)))
                .getCrfDataPostImportContainer().getSubjectData().get(0).getSubjectOID());
    }
}
