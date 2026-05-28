/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
/**
 * OpenRosa {@code manifest} DTOs. Phase B.3 PR 3c-2 migrated the
 * Castor mapping ({@code properties/openRosaManifestMapping.xml}) to
 * {@code javax.xml.bind} 2.3.x JAXB annotations.
 */
@javax.xml.bind.annotation.XmlSchema(
        namespace = "http://openrosa.org/xforms/xformsManifest",
        elementFormDefault = javax.xml.bind.annotation.XmlNsForm.QUALIFIED)
package org.akaza.openclinica.web.pform.manifest;
