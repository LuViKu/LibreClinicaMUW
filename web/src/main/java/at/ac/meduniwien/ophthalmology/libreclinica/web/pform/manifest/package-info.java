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
 * {@code jakarta.xml.bind} 2.3.x JAXB annotations.
 */
@jakarta.xml.bind.annotation.XmlSchema(
        namespace = "http://openrosa.org/xforms/xformsManifest",
        elementFormDefault = jakarta.xml.bind.annotation.XmlNsForm.QUALIFIED)
package at.ac.meduniwien.ophthalmology.libreclinica.web.pform.manifest;
