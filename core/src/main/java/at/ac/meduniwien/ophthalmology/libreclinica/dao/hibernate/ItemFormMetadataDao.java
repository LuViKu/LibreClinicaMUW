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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ItemFormMetadata;
import org.hibernate.query.NativeQuery;

// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)

// were deprecated in Hibernate 6.5 in favour of typed overloads. The

// per-call typed-form migration needs each query's expected result

// type reviewed manually — deferred B.5 follow-up. Suppression here

// is intentional and isolated to this DAO.

@SuppressWarnings("all")

public class ItemFormMetadataDao extends AbstractDomainDao<ItemFormMetadata> {

    @Override
    Class<ItemFormMetadata> domainClass() {
        return ItemFormMetadata.class;
    }

    @SuppressWarnings("rawtypes")
	public ItemFormMetadata findByItemCrfVersion(Integer itemId, Integer crfVersionId) {
        String query = "SELECT distinct m.* " + " FROM item_form_metadata m" + " WHERE m.item_id= " + String.valueOf(itemId) + " AND m.crf_version_id= "
                + String.valueOf(crfVersionId);
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(ItemFormMetadata.class);
        return (ItemFormMetadata) q.getSingleResultOrNull();

    }

    public static final String findAllByCrfVersionQuery = "select distinct * from item_form_metadata ifm where ifm.crf_version_id = :crfversionid";

    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<ItemFormMetadata> findAllByCrfVersion(int crf_version_id) {
        NativeQuery q = getCurrentSession().createNativeQuery(findAllByCrfVersionQuery).addEntity(ItemFormMetadata.class);
        q.setParameter("crfversionid", crf_version_id);
        return (List<ItemFormMetadata>) q.getResultList();
    }

}
