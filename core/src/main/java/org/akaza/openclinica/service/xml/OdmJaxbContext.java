/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.service.xml;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;

import org.akaza.openclinica.bean.submit.crfdata.ODMContainer;
import org.akaza.openclinica.domain.rule.RulesPostImportContainer;

/**
 * Single Spring-managed wiring point for the project's JAXB contexts.
 *
 * <p>Phase B.3 ([DR-006] Castor → Jakarta JAXB) introduced this class.
 * Per [DR-006 amendment 2026-05-28], the API stays on {@code javax.xml.bind}
 * 2.3.x for B.3; B.4 migrates the whole codebase (including this class)
 * to {@code jakarta.xml.bind} 4.0.x alongside Spring 5→6 and the rest of
 * the namespace cliff.
 *
 * <p>{@link JAXBContext} construction is expensive (it scans every class
 * in the binding for annotations). A single context per binding root is
 * cached for the lifetime of the application context.
 *
 * <p>PR 1/3 of B.3 wires only the rules-XML import/export paths. PR 2/3
 * adds CDISC ODM, OpenRosa, and XForm bindings; PR 3/3 drops the Castor
 * dependency declarations once every call site is migrated.
 */
public class OdmJaxbContext {

    private final ConcurrentMap<Class<?>, JAXBContext> contexts = new ConcurrentHashMap<>();

    /**
     * Returns the cached {@link JAXBContext} for {@code root}, constructing
     * one on first access.
     */
    public JAXBContext contextFor(Class<?> root) {
        return contexts.computeIfAbsent(root, this::newContext);
    }

    private JAXBContext newContext(Class<?> root) {
        try {
            return JAXBContext.newInstance(root);
        } catch (JAXBException e) {
            throw new IllegalStateException(
                    "Cannot build JAXBContext for " + root.getName(), e);
        }
    }

    /**
     * Unmarshal an XML stream into a {@code RulesPostImportContainer}.
     * The input's root element name is ignored (Castor 1.4.1's
     * {@code Unmarshaller.setClass} did the same), so XML that uses
     * {@code <OpenClinicaRules:Rules>} or {@code <RuleImport>} or any
     * other custom root all bind to the same Java type.
     */
    public RulesPostImportContainer unmarshalRulesImport(InputStream in) {
        try {
            Unmarshaller unmarshaller = contextFor(RulesPostImportContainer.class)
                    .createUnmarshaller();
            JAXBElement<RulesPostImportContainer> element = unmarshaller.unmarshal(
                    new StreamSource(in), RulesPostImportContainer.class);
            return element.getValue();
        } catch (JAXBException e) {
            throw new IllegalStateException(
                    "Failed to unmarshal RulesPostImportContainer", e);
        }
    }

    /**
     * Marshal a {@code RulesPostImportContainer} to XML on {@code out}.
     * Output is formatted (indented), UTF-8, without the standalone
     * declaration — matching the byte profile Castor produced from
     * {@code mappingMarshaller.xml} to keep the B.0 characterisation
     * goldens valid.
     */
    public void marshalRulesExport(RulesPostImportContainer rpic, OutputStream out) {
        try {
            Marshaller marshaller = contextFor(RulesPostImportContainer.class)
                    .createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.FALSE);
            marshaller.marshal(rpic, out);
        } catch (JAXBException e) {
            throw new IllegalStateException(
                    "Failed to marshal RulesPostImportContainer", e);
        }
    }

    /**
     * Marshal a {@code RulesPostImportContainer} to XML on {@code out} wrapped
     * in the OpenClinica-rules root element ({@code <OpenClinicaRules:Rules
     * xmlns:OpenClinicaRules="http://www.openclinica.org/ns/rules/v3.1"/>}).
     * Used by the ODM metadata export ({@code MetaDataReportBean}) where the
     * rules block is embedded in a larger document, so the XML prolog is
     * dropped to keep concatenation clean.
     *
     * <p>The same {@code RulesPostImportContainer} class also supports the
     * {@code <RuleImport/>} root via {@link #marshalRulesExport}. JAXB allows
     * one {@code @XmlRootElement} per class, so the alternate root is provided
     * here through a {@link JAXBElement} wrapper with an explicit {@link QName}.
     */
    public void marshalRulesMetadata(RulesPostImportContainer rpic, OutputStream out) {
        try {
            Marshaller marshaller = contextFor(RulesPostImportContainer.class)
                    .createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            // FRAGMENT=true drops the <?xml prolog so the marshalled rules
            // block can be concatenated into the larger ODM metadata export
            // without an embedded prolog — same behaviour as
            // MetaDataReportBean's post-Castor String.replace prolog strip.
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);

            QName rulesRoot = new QName(
                    "http://www.openclinica.org/ns/rules/v3.1", "Rules", "OpenClinicaRules");
            JAXBElement<RulesPostImportContainer> wrapped = new JAXBElement<>(
                    rulesRoot, RulesPostImportContainer.class, rpic);
            marshaller.marshal(wrapped, out);
        } catch (JAXBException e) {
            throw new IllegalStateException(
                    "Failed to marshal RulesPostImportContainer (metadata variant)", e);
        }
    }

    /**
     * Unmarshal a CDISC ODM clinical-data XML stream into an
     * {@link ODMContainer}. Mirrors the Castor unmarshal behaviour from
     * {@code ImportCRFDataServlet.processCsvOrXml}: the root element name is
     * taken from the binding ({@code @XmlRootElement(name="ODM", namespace=
     * "http://www.cdisc.org/ns/odm/v1.3")}), and missing optional children
     * (a {@code <ClinicalData>} without {@code <SubjectData>}, etc.) yield
     * null fields on the parsed bean.
     */
    public ODMContainer unmarshalClinicalData(InputStream in) {
        try {
            Unmarshaller unmarshaller = contextFor(ODMContainer.class).createUnmarshaller();
            JAXBElement<ODMContainer> element = unmarshaller.unmarshal(
                    new StreamSource(in), ODMContainer.class);
            return element.getValue();
        } catch (JAXBException e) {
            throw new IllegalStateException(
                    "Failed to unmarshal ODMContainer (clinical data)", e);
        }
    }
}
