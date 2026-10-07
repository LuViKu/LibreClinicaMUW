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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

@SuppressWarnings("resource") // in-memory streams and objects only; nothing here holds an OS resource
public class SecureXmlFactoriesTest {

    private static final String PLAIN = "<?xml version=\"1.0\"?><form><item>42</item></form>";
    private static final String WITH_DOCTYPE =
            "<?xml version=\"1.0\"?><!DOCTYPE form [<!ENTITY v \"42\">]><form><item>&v;</item></form>";

    private static InputStream utf8(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void documentBuilderParsesPlainXml() throws Exception {
        Document doc = SecureXmlFactories.newDocumentBuilderFactory().newDocumentBuilder().parse(utf8(PLAIN));
        assertEquals("42", doc.getElementsByTagName("item").item(0).getTextContent());
    }

    @Test
    public void documentBuilderRefusesDoctype() throws Exception {
        try {
            SecureXmlFactories.newDocumentBuilderFactory().newDocumentBuilder().parse(utf8(WITH_DOCTYPE));
            fail("DOCTYPE accepted by " + SecureXmlFactories.newDocumentBuilderFactory().getClass().getName());
        } catch (SAXParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("DOCTYPE"));
        }
    }

    @Test
    public void saxParserParsesPlainXml() throws Exception {
        SecureXmlFactories.newSAXParserFactory().newSAXParser().parse(utf8(PLAIN), new DefaultHandler());
    }

    @Test
    public void saxParserRefusesDoctype() throws Exception {
        try {
            SecureXmlFactories.newSAXParserFactory().newSAXParser().parse(utf8(WITH_DOCTYPE), new DefaultHandler());
            fail("DOCTYPE accepted by " + SecureXmlFactories.newSAXParserFactory().getClass().getName());
        } catch (SAXParseException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("DOCTYPE"));
        }
    }
}
