/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.odmbeans;

import java.util.Comparator;
import java.util.Date;


/**
 *
 * @author ywang (March, 2010)
 *
 */
@SuppressWarnings("all")
public class AuditLogBean extends ElementOIDBean {
    private String userId;
    private Date datetimeStamp;
    private String type;
    private String reasonForChange;
    private String oldValue;
    private String newValue;
    private String userName="";
    private String name="";
    private String valueType="";

    /**
     * Time order: by timestamp, entries of the same instant by their audit
     * sequence (the number in "AL_&lt;audit id&gt;"), undated entries last.
     *
     * <p>The natural order of this bean is still {@link ElementOIDBean}'s, by
     * OID string. A {@code compareTo(AuditLogBean)} used to sit here; it
     * overloaded rather than overrode {@code compareTo(ElementOIDBean)}, so
     * sorting a list of audit entries ordered them by OID ("AL_1000" before
     * "AL_200"), not by time.
     */
    public static final Comparator<AuditLogBean> CHRONOLOGICAL =
            Comparator.comparing(AuditLogBean::getDatetimeStamp, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparingLong(AuditLogBean::auditSequence);

    private static long auditSequence(AuditLogBean b) {
        String oid = b.getOid();
        if (oid != null && oid.startsWith("AL_")) {
            try {
                return Long.parseLong(oid.substring(3));
            } catch (NumberFormatException notAnAuditId) {
                // falls through
            }
        }
        return Long.MAX_VALUE;
    }

    public String getUserId() {
        return userId;
    }
    public void setUserId(String userId) {
        this.userId = userId;
    }
    public Date getDatetimeStamp() {
        return datetimeStamp;
    }
    public void setDatetimeStamp(Date datetimeStamp) {
        this.datetimeStamp = datetimeStamp;
    }
    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getReasonForChange() {
        return reasonForChange;
    }
    public void setReasonForChange(String reasonForChange) {
        this.reasonForChange = reasonForChange;
    }
    public String getOldValue() {
        return oldValue;
    }
    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }
    public String getNewValue() {
        return newValue;
    }
    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }
	public String getUserName() {
		return userName;
	}
	public void setUserName(String userName) {
		this.userName = userName;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getValueType() {
		return valueType;
	}
	public void setValueType(String valueType) {
		this.valueType = valueType;
	}
}