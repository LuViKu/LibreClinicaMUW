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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.DiscrepancyNote;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)

// were deprecated in Hibernate 6.5 in favour of typed overloads. The

// per-call typed-form migration needs each query's expected result

// type reviewed manually — deferred B.5 follow-up. Suppression here

// is intentional and isolated to this DAO.

@SuppressWarnings("all")

public class DiscrepancyNoteDao extends AbstractDomainDao<DiscrepancyNote> {

    @Override
    Class<DiscrepancyNote> domainClass() {
        return DiscrepancyNote.class;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public List<DiscrepancyNote> findParentNotesByItemData(Integer itemDataId) {
        String query = "select dn.* from discrepancy_note dn, dn_item_data_map didm where didm.item_data_id=" + itemDataId + " AND dn.parent_dn_id isnull " + 
            "AND dn.discrepancy_note_id=didm.discrepancy_note_id";
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(DiscrepancyNote.class);
        return (List<DiscrepancyNote>) q.getResultList();
    }

    public DiscrepancyNote findByDiscrepancyNoteId(int discrepancyNoteId) {
        String query = "from " + getDomainClassName() + " do where do.discrepancyNoteId = :discrepancynoteid ";
        Query<DiscrepancyNote> q = getCurrentSession().createQuery(query, DiscrepancyNote.class);
        q.setParameter("discrepancynoteid", discrepancyNoteId);
        return q.getSingleResultOrNull();
    }


}
