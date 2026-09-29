/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.xml;

import java.io.InputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Validator;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Parser factories for XML that comes from outside the application: uploaded
 * rules and ODM files, XForms, OpenRosa submissions.
 *
 * <p>None of these formats uses a document type declaration, so a DOCTYPE is
 * refused outright. That one feature stops external entities (file reads and
 * outbound requests) and entity-expansion bombs alike, and it is honoured by
 * both parsers the application can end up with: the JDK's built-in one and
 * Apache Xerces, which ships in the WAR and then wins the JAXP service lookup.
 * External entities, external DTD loading and XInclude are switched off as
 * well, in case a caller re-enables DOCTYPE handling.
 *
 * <p>The JAXP 1.5 {@code accessExternalDTD}/{@code accessExternalSchema}
 * properties are set where the implementation understands them; Xerces does
 * not, which is why the DOCTYPE refusal carries the protection.
 */
public final class SecureXmlFactories {

    static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";
    static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    static final String EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";
    static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private SecureXmlFactories() {
    }

    /**
     * A {@link DocumentBuilderFactory} that refuses DOCTYPE declarations. It is
     * not namespace aware unless the caller turns that on, matching
     * {@link DocumentBuilderFactory#newInstance()}.
     */
    public static DocumentBuilderFactory newDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser cannot be hardened: " + factory.getClass().getName(), e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD);
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA);
        return factory;
    }

    /**
     * A {@link SAXParserFactory} that refuses DOCTYPE declarations. It is not
     * namespace aware unless the caller turns that on.
     */
    public static SAXParserFactory newSAXParserFactory() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("XML parser cannot be hardened: " + factory.getClass().getName(), e);
        }
        factory.setXIncludeAware(false);
        return factory;
    }

    /**
     * A namespace-aware source over {@code in} for JAXB unmarshalling. Handing
     * JAXB a {@link SAXSource} makes it use this reader instead of creating
     * its own parser with default settings.
     */
    public static SAXSource saxSource(InputStream in) {
        SAXParserFactory factory = newSAXParserFactory();
        factory.setNamespaceAware(true);
        try {
            XMLReader reader = factory.newSAXParser().getXMLReader();
            return new SAXSource(reader, new InputSource(in));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("XML parser cannot be created", e);
        }
    }

    /**
     * Stops a schema {@link Validator} from fetching anything the instance
     * document points at. The schema itself is compiled beforehand from the
     * application's own XSD, so this does not affect its imports.
     */
    public static void restrictExternalAccess(Validator validator) {
        for (String property : new String[] { XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA }) {
            try {
                validator.setProperty(property, "");
            } catch (SAXException e) {
                // Implementation without JAXP 1.5 support (Apache Xerces); the
                // instance document has already been parsed with DOCTYPE refused.
            }
        }
    }

    private static void setAttributeIfSupported(DocumentBuilderFactory factory, String attribute) {
        try {
            factory.setAttribute(attribute, "");
        } catch (IllegalArgumentException e) {
            // Apache Xerces does not know the JAXP 1.5 attributes; the DOCTYPE
            // refusal above already blocks external entities there.
        }
    }
}
