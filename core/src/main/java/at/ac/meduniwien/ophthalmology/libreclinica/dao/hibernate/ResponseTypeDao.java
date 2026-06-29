/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ResponseType;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)

// were deprecated in Hibernate 6.5 in favour of typed overloads. The

// per-call typed-form migration needs each query's expected result

// type reviewed manually — deferred B.5 follow-up. Suppression here

// is intentional and isolated to this DAO.

@SuppressWarnings("all")

public class ResponseTypeDao extends AbstractDomainDao<ResponseType> {

    @Override
    Class<ResponseType> domainClass() {
        return ResponseType.class;
    }

    public ResponseType findByResponseTypeName(String name) {
        String query = "from " + getDomainClassName() + " response_type  where response_type.name = :name ";
        Query<ResponseType> q = getCurrentSession().createQuery(query, ResponseType.class);
        q.setParameter("name", name);
        return q.getSingleResultOrNull();
    }

    @SuppressWarnings("rawtypes")
    public ResponseType findByItemFormMetaDataId(Integer itemFormMetadataId) {
        String query = "select rt.* from response_type rt, response_set rs, item_form_metadata ifm where ifm.response_set_id=rs.response_set_id"
                + " and rs.response_type_id=rt.response_type_id and ifm.item_form_metadata_id = " + String.valueOf(itemFormMetadataId);
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(ResponseType.class);
        return (ResponseType) q.getSingleResultOrNull();
    }

}
