/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.rule;

import at.ac.meduniwien.ophthalmology.libreclinica.exception.OpenClinicaSystemException;
import at.ac.meduniwien.ophthalmology.libreclinica.service.xml.SecureXmlFactories;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;

import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

/**
 * Validates uploaded XML (rules files, ODM imports) against one of the
 * application's own XSDs.
 *
 * <p>The uploaded document is parsed with
 * {@link SecureXmlFactories#newDocumentBuilderFactory()}, which refuses a
 * DOCTYPE and therefore every entity declaration, so an upload cannot pull
 * server files or remote URLs into the document. The XSD is trusted and is
 * compiled as before (the ODM 1.2.1 schema imports W3C schemas by URL).
 */
@SuppressWarnings("all")

public class XmlSchemaValidationHelper {

    public void validateAgainstSchema(File xmlFile, File xsdFile) {
        validate(new InputSource(xmlFile.toURI().toASCIIString()), new StreamSource(xsdFile));
    }

    /**
     * @param xml the document itself (not a URI)
     */
    public void validateAgainstSchema(String xml, File xsdFile) {
        validate(new InputSource(new StringReader(xml)), new StreamSource(xsdFile));
    }

    public void validateAgainstSchema(String xml, InputStream xsdFile) {
        validate(new InputSource(new StringReader(xml)), new StreamSource(xsdFile));
    }

    public void validateAgainstSchema(File xmlFile, InputStream xsdFile) {
        validate(new InputSource(xmlFile.toURI().toASCIIString()), new StreamSource(xsdFile));
    }

    private void validate(InputSource xml, Source schemaSource) {
        try {
            // parse an XML document into a DOM tree
            DocumentBuilderFactory builderFactory = SecureXmlFactories.newDocumentBuilderFactory();
            builderFactory.setNamespaceAware(true);
            DocumentBuilder parser = builderFactory.newDocumentBuilder();
            Document document = parser.parse(xml);

            // create a SchemaFactory capable of understanding WXS schemas
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

            // load a WXS schema, represented by a Schema instance
            Schema schema = factory.newSchema(schemaSource);

            // create a Validator instance, which can be used to validate an
            // instance document
            Validator validator = schema.newValidator();
            SecureXmlFactories.restrictExternalAccess(validator);

            // validate the DOM tree
            validator.validate(new DOMSource(document));

        } catch (FileNotFoundException ex) {
            throw new OpenClinicaSystemException("File was not found", ex.getCause());
        } catch (IOException ioe) {
            throw new OpenClinicaSystemException("IO Exception", ioe.getCause());
        } catch (SAXParseException spe) {
            throw new OpenClinicaSystemException("Line : " + spe.getLineNumber() + " - " + spe.getMessage(), spe.getCause());
        } catch (SAXException e) {
            throw new OpenClinicaSystemException(e.getMessage(), e.getCause());
        } catch (ParserConfigurationException pce) {
            throw new OpenClinicaSystemException(pce.getMessage(), pce.getCause());
        }
    }
}
