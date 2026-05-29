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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.odmbeans.AuditLogsBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.odmbeans.DiscrepancyNotesBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.odmbeans.ElementRefBean;

@XmlAccessorType(XmlAccessType.NONE)
public class ImportItemDataBean {
    @XmlAttribute(name = "ItemOID")
    private String itemOID;
    @XmlAttribute(name = "TransactionType")
    private String transactionType;
    @XmlAttribute(name = "Value")
    private String value;
    @XmlAttribute(name = "IsNull")
    private String isNull; // boolean, tbh?
    private ElementRefBean measurementUnitRef = new ElementRefBean();
    private String reasonForNull;
    private AuditLogsBean auditLogs = new AuditLogsBean();
    private DiscrepancyNotesBean discrepancyNotes = new DiscrepancyNotesBean();
    
    private boolean hasValueWithNull; //this is just a flag, it is not an attribute/element

    public String getItemOID() {
        return itemOID;
    }

    public void setItemOID(String itemOID) {
        this.itemOID = itemOID;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(String transactionType) {
        this.transactionType = transactionType;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getIsNull() {
        return isNull;
    }

    public void setIsNull(String isNull) {
        this.isNull = isNull;
    }

    public ElementRefBean getMeasurementUnitRef() {
        return measurementUnitRef;
    }

    public void setMeasurementUnitRef(ElementRefBean measurementUnitRef) {
        this.measurementUnitRef = measurementUnitRef;
    }

    public String getReasonForNull() {
        return reasonForNull;
    }

    public void setReasonForNull(String reasonForNull) {
        this.reasonForNull = reasonForNull;
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

    public boolean isHasValueWithNull() {
        return hasValueWithNull;
    }

    public void setHasValueWithNull(boolean hasValueWithNull) {
        this.hasValueWithNull = hasValueWithNull;
    }
}
