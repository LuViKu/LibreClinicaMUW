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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.EventDefinitionCrfItemTag;
import org.hibernate.query.Query;

@SuppressWarnings("all")

public class EventDefinitionCrfItemTagDao extends AbstractDomainDao<EventDefinitionCrfItemTag> {

    @Override
    Class<EventDefinitionCrfItemTag> domainClass() {
        return EventDefinitionCrfItemTag.class;
    }

    public List<EventDefinitionCrfItemTag> findAllByCrfPath(int tag_id, String crfPath, boolean active) {

        String query = " from " + getDomainClassName() + "  where " + " tag_id= " + tag_id + " and active=" + active + " and path LIKE '" + crfPath + ".%'";

        Query<EventDefinitionCrfItemTag> q = getCurrentSession().createQuery(query, EventDefinitionCrfItemTag.class);
        return q.getResultList();
    }

    public EventDefinitionCrfItemTag findByItemPath(int tag_id, boolean active, String itemPath) {

        String query = " from " + getDomainClassName() + "  where " + " tag_id= " + tag_id + " and active=" + active + " and path= '" + itemPath + "'";

        Query<EventDefinitionCrfItemTag> q = getCurrentSession().createQuery(query, EventDefinitionCrfItemTag.class);
        return q.getSingleResultOrNull();
    }

}
