/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ItemGroupMetadata;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)

// were deprecated in Hibernate 6.5 in favour of typed overloads. The

// per-call typed-form migration needs each query's expected result

// type reviewed manually — deferred B.5 follow-up. Suppression here

// is intentional and isolated to this DAO.

@SuppressWarnings("all")

public class ItemGroupMetadataDao extends AbstractDomainDao<ItemGroupMetadata> {

    @Override
    Class<ItemGroupMetadata> domainClass() {
        return ItemGroupMetadata.class;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public ArrayList<ItemGroupMetadata> findByItemGroupCrfVersion(Integer itemGroupId, Integer crfVersionId) {
        String query = "select distinct igm.* from item_group_metadata igm, item_group ig where igm.crf_version_id = " + String.valueOf(crfVersionId)
                + " and ig.item_group_id = igm.item_group_id and ig.item_group_id = " + String.valueOf(itemGroupId) + " order by igm.ordinal asc";
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(ItemGroupMetadata.class);
        return (ArrayList<ItemGroupMetadata>) q.getResultList();
    }

    public ItemGroupMetadata findByItemCrfVersion(int item_id, int crf_version_id) {
        String query = "from " + getDomainClassName() + " do where do.item.itemId = :itemid and do.crfVersion.crfVersionId = :crfversionid";
        Query<ItemGroupMetadata> q = getCurrentSession().createQuery(query, ItemGroupMetadata.class);
        q.setParameter("itemid", item_id);
        q.setParameter("crfversionid", crf_version_id);
        return q.getSingleResultOrNull();
    }

    public static final String findAllByCrfVersionQuery = "select distinct * from item_group_metadata igm where igm.crf_version_id = :crfversionid";

    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<ItemGroupMetadata> findAllByCrfVersion(int crf_version_id) {
        NativeQuery q = getCurrentSession().createNativeQuery(findAllByCrfVersionQuery).addEntity(ItemGroupMetadata.class);
        q.setParameter("crfversionid", crf_version_id);
        return (List<ItemGroupMetadata>) q.getResultList();
    }
}
