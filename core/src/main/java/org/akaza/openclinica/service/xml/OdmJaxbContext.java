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
import javax.xml.transform.stream.StreamSource;

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
}
