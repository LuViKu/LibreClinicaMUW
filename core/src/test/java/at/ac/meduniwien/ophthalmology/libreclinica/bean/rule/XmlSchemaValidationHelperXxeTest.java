/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.rule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Uploaded rules XML is parsed here before anything else looks at it. A
 * DOCTYPE — and with it every entity declaration — is refused, while a plain
 * rules document still validates against rules.xsd.
 */
public class XmlSchemaValidationHelperXxeTest {

    private static final String SECRET = "TOP-SECRET-MARKER-5f1c";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String rules(String doctype, String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + doctype
                + "<RuleImport>\n"
                + "  <RuleAssignment>\n"
                + "    <Target Context=\"OC_RULES_V1\">SE_X.F_X.IG_X.I_X</Target>\n"
                + "    <RuleRef OID=\"R_1\">\n"
                + "      <DiscrepancyNoteAction IfExpressionEvaluates=\"true\">\n"
                + "        <Message>" + message + "</Message>\n"
                + "      </DiscrepancyNoteAction>\n"
                + "    </RuleRef>\n"
                + "  </RuleAssignment>\n"
                + "</RuleImport>\n";
    }

    private File write(String name, String content) throws IOException {
        File f = tmp.newFile(name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static InputStream rulesXsd() {
        InputStream xsd = XmlSchemaValidationHelperXxeTest.class.getClassLoader()
                .getResourceAsStream("properties/rules.xsd");
        if (xsd == null) {
            throw new IllegalStateException("properties/rules.xsd not on the test classpath");
        }
        return xsd;
    }

    private static String messages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    public void plainRulesDocumentStillValidates() throws Exception {
        File xml = write("rules.xml", rules("", "value out of range"));
        new XmlSchemaValidationHelper().validateAgainstSchema(xml, rulesXsd());
        new XmlSchemaValidationHelper().validateAgainstSchema(rules("", "value out of range"), rulesXsd());
    }

    @Test
    public void internalEntityDeclarationIsRefused() throws Exception {
        File xml = write("rules.xml",
                rules("<!DOCTYPE RuleImport [<!ENTITY msg \"expanded\">]>\n", "&msg;"));
        try {
            new XmlSchemaValidationHelper().validateAgainstSchema(xml, rulesXsd());
            fail("a rules upload with a DOCTYPE was accepted");
        } catch (OpenClinicaSystemException expected) {
            assertTrue(messages(expected), messages(expected).contains("DOCTYPE"));
        }
    }

    @Test
    public void externalEntityIsNotResolved() throws Exception {
        File secret = write("secret.txt", SECRET);
        String doctype = "<!DOCTYPE RuleImport [<!ENTITY msg SYSTEM \"" + secret.toURI() + "\">]>\n";
        File xml = write("rules.xml", rules(doctype, "&msg;"));
        try {
            new XmlSchemaValidationHelper().validateAgainstSchema(xml, rulesXsd());
            fail("a rules upload with an external entity was accepted");
        } catch (OpenClinicaSystemException expected) {
            assertFalse(messages(expected), messages(expected).contains(SECRET));
            assertTrue(messages(expected), messages(expected).contains("DOCTYPE"));
        }
    }

    @Test
    public void stringOverloadRefusesDoctypeToo() {
        String xml = rules("<!DOCTYPE RuleImport [<!ENTITY msg \"expanded\">]>\n", "&msg;");
        try {
            new XmlSchemaValidationHelper().validateAgainstSchema(xml, rulesXsd());
            fail("a rules document with a DOCTYPE was accepted");
        } catch (OpenClinicaSystemException expected) {
            assertTrue(messages(expected), messages(expected).contains("DOCTYPE"));
        }
    }
}
