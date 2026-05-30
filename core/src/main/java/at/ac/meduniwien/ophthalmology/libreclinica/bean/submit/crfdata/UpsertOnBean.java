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
import jakarta.xml.bind.annotation.XmlAttribute;

@XmlAccessorType(XmlAccessType.NONE)
public class UpsertOnBean {

    @XmlAttribute(name = "NotStarted")
    private boolean notStarted = true;
    @XmlAttribute(name = "DataEntryStarted")
    private boolean dataEntryStarted = true;
    @XmlAttribute(name = "DataEntryComplete")
    private boolean dataEntryComplete = true;

    public boolean isNotStarted() {
        return notStarted;
    }

    public void setNotStarted(boolean notStarted) {
        this.notStarted = notStarted;
    }

    public boolean isDataEntryStarted() {
        return dataEntryStarted;
    }

    public void setDataEntryStarted(boolean dataEntryStarted) {
        this.dataEntryStarted = dataEntryStarted;
    }

    public boolean isDataEntryComplete() {
        return dataEntryComplete;
    }

    public void setDataEntryComplete(boolean dataEntryComplete) {
        this.dataEntryComplete = dataEntryComplete;
    }

}
