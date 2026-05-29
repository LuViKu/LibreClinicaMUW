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

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;

import org.akaza.openclinica.bean.odmbeans.AuditLogsBean;
import org.akaza.openclinica.bean.odmbeans.DiscrepancyNotesBean;

@XmlAccessorType(XmlAccessType.NONE)
public class FormDataBean {
    @XmlElement(name = "ItemGroupData", namespace = "http://www.cdisc.org/ns/odm/v1.3")
    private ArrayList<ImportItemGroupDataBean> itemGroupData;
    private AuditLogsBean auditLogs;
    private DiscrepancyNotesBean discrepancyNotes;
    @XmlAttribute(name = "FormOID")
    private String formOID;
    // OpenClinica:Status — the only field on this bean that lives in the
    // openclinica extension namespace, not the cdisc default.
    @XmlAttribute(name = "Status",
            namespace = "http://www.openclinica.org/ns/odm_ext_v130/v3.1")
    private String EventCRFStatus;

    public FormDataBean() {
        itemGroupData = new ArrayList<ImportItemGroupDataBean>();
        auditLogs = new AuditLogsBean();
        discrepancyNotes = new DiscrepancyNotesBean();
    }

    public String getFormOID() {
        return formOID;
    }

    public void setFormOID(String formOID) {
        this.formOID = formOID;
    }

    public String getEventCRFStatus() {
        return EventCRFStatus;
    }

    public void setEventCRFStatus(String eventCRFStatus) {
        EventCRFStatus = eventCRFStatus;
    }

    public ArrayList<ImportItemGroupDataBean> getItemGroupData() {
        return itemGroupData;
    }

    public void setItemGroupData(ArrayList<ImportItemGroupDataBean> itemGroupData) {
        this.itemGroupData = itemGroupData;
    }

    public AuditLogsBean getAuditLogs() {
        return auditLogs;
    }

    public void setAuditLogs(AuditLogsBean auditLogs) {
        this.auditLogs = auditLogs;
    }

    public DiscrepancyNotesBean getDiscrepancyNotes() {
        return discrepancyNotes;
    }

    public void setDiscrepancyNotes(DiscrepancyNotesBean discrepancyNotes) {
        this.discrepancyNotes = discrepancyNotes;
    }
}
