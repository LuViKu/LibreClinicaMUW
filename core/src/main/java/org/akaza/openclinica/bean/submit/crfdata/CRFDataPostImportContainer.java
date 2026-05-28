/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package org.akaza.openclinica.bean.submit.crfdata;

import java.util.ArrayList;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;

/**
 * CRFDataPostImportContainer, meant to serve as the 'ClinicalData' tag in CRF Data Import. Will contain the following:
 * -- SubjectData -- StudyEventData -- FormData -- ItemGroupData -- ItemData Note that each list will have 1 to n
 * elements, and each element is contained inside its parent element.
 *
 * @author thickerson, 04/2008
 *
 * <p>Phase B.3 PR 2/3: only the StudyOID attribute is JAXB-bound here; the
 * subjectData + upsertOn child trees are added in PR 3/3 once the production
 * import path is migrated off Castor. JAXB leaves the unannotated fields
 * null after parsing — same contract Castor 1.4.1 provided for empty inputs.
 */
@XmlAccessorType(XmlAccessType.NONE)
public class CRFDataPostImportContainer {

    private ArrayList<SubjectDataBean> subjectData;
    @XmlAttribute(name = "StudyOID")
    private String studyOID;
    private UpsertOnBean upsertOn;

    public String getStudyOID() {
        return studyOID;
    }

    public void setStudyOID(String studyOID) {
        this.studyOID = studyOID;
    }

    public ArrayList<SubjectDataBean> getSubjectData() {
        return subjectData;
    }

    public void setSubjectData(ArrayList<SubjectDataBean> subjectData) {
        this.subjectData = subjectData;
    }

    public UpsertOnBean getUpsertOn() {
        return upsertOn;
    }

    public void setUpsertOn(UpsertOnBean upsertOn) {
        this.upsertOn = upsertOn;
    }

}
