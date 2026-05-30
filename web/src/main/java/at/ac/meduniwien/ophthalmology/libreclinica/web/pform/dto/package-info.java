/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
/**
 * PFORM / OpenRosa XForm DTOs — the web-side parallel of
 * {@code core/.../domain/xform/dto/} used by {@link
 * at.ac.meduniwien.ophthalmology.libreclinica.web.pform.OpenRosaXmlGenerator}. Phase B.3 PR
 * 3c-2 migrated the Castor mapping (driven by
 * {@code properties/openRosaXFormMapping.xml}) to {@code jakarta.xml.bind}
 * 2.3.x JAXB annotations on the DTOs themselves.
 */
@jakarta.xml.bind.annotation.XmlSchema(
        namespace = "http://www.w3.org/2002/xforms",
        elementFormDefault = jakarta.xml.bind.annotation.XmlNsForm.QUALIFIED,
        xmlns = {
                @jakarta.xml.bind.annotation.XmlNs(prefix = "",
                        namespaceURI = "http://www.w3.org/2002/xforms"),
                @jakarta.xml.bind.annotation.XmlNs(prefix = "h",
                        namespaceURI = "http://www.w3.org/1999/xhtml"),
                @jakarta.xml.bind.annotation.XmlNs(prefix = "jr",
                        namespaceURI = "http://openrosa.org/javarosa")
        })
package at.ac.meduniwien.ophthalmology.libreclinica.web.pform.dto;
