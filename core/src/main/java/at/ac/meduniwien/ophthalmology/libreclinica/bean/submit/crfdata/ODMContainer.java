/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * ODM Container, the surrounding tag for Clinical Data together with meta data
 *
 * @author thickerson, 04/2008
 *
 * <p>Phase B.3 PR 2/3: minimal JAXB annotations on the rooted element +
 * the ClinicalData child. PR 3/3 will extend annotations across the full
 * clinical-data subtree (SubjectData / StudyEventData / FormData / ItemGroupData
 * / ItemData) needed for the production import path. For now only the
 * empty-envelope + StudyOID-attribute round-trip is JAXB-bound; production
 * callers (ImportCRFDataServlet et al.) stay on Castor until PR 3.
 */
@XmlRootElement(name = "ODM", namespace = "http://www.cdisc.org/ns/odm/v1.3")
@XmlAccessorType(XmlAccessType.NONE)
public class ODMContainer {

    @XmlElement(name = "ClinicalData", namespace = "http://www.cdisc.org/ns/odm/v1.3")
    private CRFDataPostImportContainer crfDataPostImportContainer;
    private String subjectUniqueIdentifier;
    private String studyUniqueIdentifier;

    public CRFDataPostImportContainer getCrfDataPostImportContainer() {
        return crfDataPostImportContainer;
    }

    public void setCrfDataPostImportContainer(CRFDataPostImportContainer crfDataPostImportContainer) {
        this.crfDataPostImportContainer = crfDataPostImportContainer;
    }

    public String getSubjectUniqueIdentifier() {
        return subjectUniqueIdentifier;
    }

    public void setSubjectUniqueIdentifier(String subjectUniqueIdentifier) {
        this.subjectUniqueIdentifier = subjectUniqueIdentifier;
    }

    public String getStudyUniqueIdentifier() {
        return studyUniqueIdentifier;
    }

    public void setStudyUniqueIdentifier(String studyUniqueIdentifier) {
        this.studyUniqueIdentifier = studyUniqueIdentifier;
    }

}
