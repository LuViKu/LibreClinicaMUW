/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.service.xml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;

import org.akaza.openclinica.bean.submit.crfdata.ODMContainer;
import org.akaza.openclinica.domain.rule.RulesPostImportContainer;
import org.akaza.openclinica.domain.xform.dto.Html;

/**
 * Single Spring-managed wiring point for the project's JAXB contexts.
 *
 * <p>Phase B.3 ([DR-006] Castor → Jakarta JAXB) introduced this class.
 * Per [DR-006 amendment 2026-05-28], the API stays on {@code jakarta.xml.bind}
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

    /**
     * Unmarshal an XForm document (the {@code xform_template.xml}-shaped XHTML
     * + XForms tree) into the {@code core} {@link Html} DTO used by
     * {@code XformParser}. Mirrors the Castor behaviour with
     * {@code unmarshaller.setClass(Html.class)} +
     * {@code setWhitespacePreserve(false)} — the root element name is taken
     * from the {@code @XmlRootElement} on {@link Html}.
     */
    public Html unmarshalXform(String xml) {
        try {
            Unmarshaller unmarshaller = contextFor(Html.class).createUnmarshaller();
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            return (Html) unmarshaller.unmarshal(new StreamSource(in));
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to unmarshal XForm Html", e);
        }
    }

    /**
     * Marshal an XForm {@link Html} DTO to a String. Output is a single-line
     * (non-indented) XML fragment — no XML declaration prolog — so it can be
     * sliced and reassembled by {@code OpenRosaXmlGenerator.buildForm} just
     * like the Castor output.
     */
    public String marshalXform(Html html) {
        try {
            Marshaller marshaller = contextFor(Html.class).createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            StringWriter writer = new StringWriter();
            marshaller.marshal(html, writer);
            return writer.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to marshal XForm Html", e);
        }
    }

    /**
     * Generic unmarshal helper for any {@code @XmlRootElement}-annotated
     * type. Used by the OpenRosa-side bindings ({@code web/pform/dto/Html},
     * etc.) where the bean lives outside {@code core}. The root element
     * name is taken from the JAXB binding on the target class.
     */
    public <T> T unmarshalRoot(Class<T> rootClass, String xml) {
        if (rootClass == null) {
            throw new IllegalArgumentException("rootClass cannot be null");
        }
        try {
            Unmarshaller unmarshaller = contextFor(rootClass).createUnmarshaller();
            ByteArrayInputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            return rootClass.cast(unmarshaller.unmarshal(new StreamSource(in)));
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to unmarshal " + rootClass.getName(), e);
        }
    }

    /**
     * Generic marshal-to-String helper for OpenRosa-side bindings ({@code
     * XFormList}, {@code Manifest}, the {@code web/pform/dto/Html}). Lives in
     * {@code core} so the JAXB context cache stays in one place, but the
     * caller passes a {@code @XmlRootElement}-annotated bean of any type.
     * Output is formatted, UTF-8, without the XML prolog so the byte stream
     * is identical in shape to the Castor output the OpenRosa clients
     * expect.
     */
    public String marshalToString(Object root) {
        if (root == null) {
            throw new IllegalArgumentException("root cannot be null");
        }
        try {
            Marshaller marshaller = contextFor(root.getClass()).createMarshaller();
            // Castor's XForm marshaller pinned indent=false; the OpenRosa
            // formList/Manifest marshallers didn't set it. Keeping all three
            // unformatted matches the XForm code path's expectation that
            // {@code String.indexOf("<instance>")} returns a single token.
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);
            StringWriter writer = new StringWriter();
            marshaller.marshal(root, writer);
            return writer.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("Failed to marshal " + root.getClass().getName(), e);
        }
    }
}
