/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.AuditLogEvent;
// Phase B.5: Hibernate 6 removed org.hibernate.type.IntegerType /
// StringType (the BasicType singletons). The 2-arg setParameter(name, value)
// overload infers the type from the value, so the explicit Type argument is
// no longer needed.

public class AuditLogEventDao extends AbstractDomainDao<AuditLogEvent> {

    @Override
    public Class<AuditLogEvent> domainClass() {
        return AuditLogEvent.class;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> findByParam(AuditLogEvent auditLogEvent, String anotherAuditTable) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName();
        String buildQuery = "";
        if (auditLogEvent.getEntityId() != null && auditLogEvent.getAuditTable() != null && anotherAuditTable == null) {
            buildQuery += "do.entityId =:entity_id ";
            buildQuery += " and  do.auditTable =:audit_table order by do.auditId ";
        } else if (auditLogEvent.getEntityId() != null && auditLogEvent.getAuditTable() != null && anotherAuditTable != null) {
            buildQuery += "do.entityId =:entity_id ";
            buildQuery += " and ( do.auditTable =:audit_table or do.auditTable =:anotherAuditTable) order by do.auditId ";
        }
        if (!buildQuery.isEmpty())
            query = "from " + getDomainClassName() + " do  where " + buildQuery;
        else
            query = "from " + getDomainClassName();
        org.hibernate.query.Query<T> q = getCurrentSession().createQuery(query);
        if (auditLogEvent.getEntityId() != null && auditLogEvent.getAuditTable() != null && anotherAuditTable == null) {
            q.setParameter("entity_id", auditLogEvent.getEntityId());
            q.setParameter("audit_table", auditLogEvent.getAuditTable());
        } else if (auditLogEvent.getEntityId() != null && auditLogEvent.getAuditTable() != null && anotherAuditTable != null) {
            q.setParameter("entity_id", auditLogEvent.getEntityId());
            q.setParameter("audit_table", auditLogEvent.getAuditTable());
            q.setParameter("anotherAuditTable", anotherAuditTable);
        }
        return q.list();
    }
}