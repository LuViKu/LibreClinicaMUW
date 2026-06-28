/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ItemDataType;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)

// were deprecated in Hibernate 6.5 in favour of typed overloads. The

// per-call typed-form migration needs each query's expected result

// type reviewed manually — deferred B.5 follow-up. Suppression here

// is intentional and isolated to this DAO.

@SuppressWarnings("deprecation")

public class ItemDataTypeDao extends AbstractDomainDao<ItemDataType> {

    @Override
    Class<ItemDataType> domainClass() {
        return ItemDataType.class;
    }

    public ItemDataType findByItemDataTypeCode(String item_data_type_code) {
        String query = "from " + getDomainClassName() + " item_data_type  where item_data_type.code = :itemdatatypecode ";
        Query<ItemDataType> q = getCurrentSession().createQuery(query, ItemDataType.class);
        q.setParameter("itemdatatypecode", item_data_type_code);
        return (ItemDataType) q.getSingleResultOrNull();
    }

    public ItemDataType findByItemDataTypeId(int item_data_type_id) {
        String query = "from " + getDomainClassName() + " item_data_type  where item_data_type.itemDataTypeId = :item_data_type_id ";
        Query<ItemDataType> q = getCurrentSession().createQuery(query, ItemDataType.class);
        q.setParameter("item_data_type_id", item_data_type_id);
        ItemDataType result = (ItemDataType) q.getSingleResultOrNull();
        return result;
    }

    @SuppressWarnings("rawtypes")
    public ItemDataType findByItemId(int item_id) {
        String query = "select idt.* from item_data_type idt join item i on idt.item_data_type_id=i.item_data_type_id where i.item_id = " + item_id;
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(ItemDataType.class);
        ItemDataType result = (ItemDataType) q.getSingleResultOrNull();
        return result;
    }
}
