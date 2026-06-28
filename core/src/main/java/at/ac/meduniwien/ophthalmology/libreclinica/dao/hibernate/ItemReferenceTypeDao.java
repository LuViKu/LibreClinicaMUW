/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ItemReferenceType;
import org.hibernate.query.Query;

public class ItemReferenceTypeDao extends AbstractDomainDao<ItemReferenceType> {

    @Override
    Class<ItemReferenceType> domainClass() {
        return ItemReferenceType.class;
    }

    // TODO update to CriteriaQuery 
    public ItemReferenceType findByItemReferenceTypeId(int item_reference_type_id) {
        String query = "from " + getDomainClassName() + " item_reference_type  where item_reference_type.itemReferenceTypeId = :itemreferencetypeid ";
        Query<ItemReferenceType> q = getCurrentSession().createQuery(query, ItemReferenceType.class);
        q.setParameter("itemreferencetypeid", item_reference_type_id);
        return q.getSingleResultOrNull();
    }

}
