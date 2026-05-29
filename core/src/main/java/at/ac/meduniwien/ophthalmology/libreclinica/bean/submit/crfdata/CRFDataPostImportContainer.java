/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.crfdata;

import java.util.ArrayList;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;

/**
 * CRFDataPostImportContainer, meant to serve as the 'ClinicalData' tag in CRF Data Import. Will contain the following:
 * -- SubjectData -- StudyEventData -- FormData -- ItemGroupData -- ItemData Note that each list will have 1 to n
 * elements, and each element is contained inside its parent element.
 *
 * @author thickerson, 04/2008
 *
 * <p>Phase B.3 PR 3a: full JAXB bean-tree wiring complete. StudyOID
 * attribute (PR 2) + SubjectData + UpsertOn children. Per DR-006
 * amendment the namespace stays on cdisc/v1.3 (jakarta.xml.bind 2.3.x);
 * jakarta migration happens in B.4.
 */
@XmlAccessorType(XmlAccessType.NONE)
public class CRFDataPostImportContainer {

    @XmlElement(name = "SubjectData", namespace = "http://www.cdisc.org/ns/odm/v1.3")
    private ArrayList<SubjectDataBean> subjectData;
    @XmlAttribute(name = "StudyOID")
    private String studyOID;
    @XmlElement(name = "UpsertOn", namespace = "http://www.cdisc.org/ns/odm/v1.3")
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
