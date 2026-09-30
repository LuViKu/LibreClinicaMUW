/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.xml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata.ODMContainer;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RulesPostImportContainer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * ODM clinical-data import (legacy servlet, SPA import API, scheduled import
 * job) and rules import hand the uploaded file straight to JAXB. The
 * unmarshaller must refuse a DOCTYPE — and with it every entity declaration —
 * whichever JAXP parser wins the service lookup; plain documents still bind.
 */
public class OdmJaxbContextXxeTest {

    private static final String SECRET = "TOP-SECRET-MARKER-9d2e";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final OdmJaxbContext jaxb = new OdmJaxbContext();

    private static String odm(String doctype, String subjectKey) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + doctype
                + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\">\n"
                + "  <ClinicalData StudyOID=\"S_XXE\">\n"
                + "    <SubjectData SubjectKey=\"" + subjectKey + "\"/>\n"
                + "  </ClinicalData>\n"
                + "</ODM>\n";
    }

    private static InputStream utf8(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    public void plainClinicalDataStillBinds() {
        ODMContainer parsed = jaxb.unmarshalClinicalData(utf8(odm("", "SS_PLAIN")));
        assertEquals("SS_PLAIN",
                parsed.getCrfDataPostImportContainer().getSubjectData().get(0).getSubjectOID());
    }

    @Test
    public void clinicalDataWithInternalEntityIsRefused() {
        String xml = odm("<!DOCTYPE ODM [<!ENTITY key \"SS_FROM_ENTITY\">]>\n", "&key;");
        try {
            ODMContainer parsed = jaxb.unmarshalClinicalData(utf8(xml));
            fail("DOCTYPE accepted; subject key bound to "
                    + parsed.getCrfDataPostImportContainer().getSubjectData().get(0).getSubjectOID());
        } catch (IllegalStateException expected) {
            // refused
        }
    }

    @Test
    public void clinicalDataWithExternalEntityIsRefused() throws Exception {
        File secret = tmp.newFile("secret.txt");
        Files.write(secret.toPath(), SECRET.getBytes(StandardCharsets.UTF_8));
        String xml = odm("<!DOCTYPE ODM [<!ENTITY key SYSTEM \"" + secret.toURI() + "\">]>\n", "&key;");
        try {
            ODMContainer parsed = jaxb.unmarshalClinicalData(utf8(xml));
            fail("DOCTYPE accepted; subject key bound to "
                    + parsed.getCrfDataPostImportContainer().getSubjectData().get(0).getSubjectOID());
        } catch (IllegalStateException expected) {
            assertFalse(messages(expected), messages(expected).contains(SECRET));
        }
    }

    @Test
    public void rulesTemplateStillBinds() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("properties/rules_template.xml")) {
            assertNotNull("properties/rules_template.xml not on the test classpath", in);
            // Only the root is bound here (nested rule elements are not
            // mapped on RulesPostImportContainer); what matters is that a
            // plain upload still parses.
            RulesPostImportContainer rules = jaxb.unmarshalRulesImport(in);
            assertNotNull(rules);
        }
    }

    @Test
    public void rulesImportWithDoctypeIsRefused() {
        String xml = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE RuleImport [<!ENTITY oid \"R_FROM_ENTITY\">]>\n"
                + "<RuleImport><RuleDef OID=\"&oid;\"><Description>d</Description>"
                + "<Expression>1 eq 1</Expression></RuleDef></RuleImport>";
        try {
            jaxb.unmarshalRulesImport(utf8(xml));
            fail("a rules import with a DOCTYPE was accepted");
        } catch (IllegalStateException expected) {
            // refused
        }
    }
}
