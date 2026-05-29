/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
/**
 * XForm DTO package. Phase B.3 PR 3c-2 migrated the Castor XML binding
 * (driven by {@code properties/xformMapping.xml}) to {@code jakarta.xml.bind}
 * 2.3.x JAXB annotations on the DTOs themselves. The XForm spec mixes
 * three namespaces:
 *
 * <ul>
 *   <li>{@code http://www.w3.org/2002/xforms} — default for {@code Model},
 *       {@code Bind}, {@code Group}, {@code Input}, etc. (set as the
 *       package default below).</li>
 *   <li>{@code http://www.w3.org/1999/xhtml} — root + outer envelope:
 *       {@code Html}, {@code Head}, {@code Body}. Override per-element
 *       on those types.</li>
 *   <li>{@code http://openrosa.org/javarosa} — JR-prefixed attributes
 *       on {@code Bind} ({@code jr:preload}) and {@code Repeat}
 *       ({@code jr:count}, {@code jr:noAddRemove}). Override per-attribute.</li>
 * </ul>
 *
 * <p>Per DR-006 amendment, B.3 stays on {@code jakarta.xml.bind} 2.3.x;
 * jakarta moves to B.4 alongside Spring 5 → 6.
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
package org.akaza.openclinica.domain.xform.dto;
