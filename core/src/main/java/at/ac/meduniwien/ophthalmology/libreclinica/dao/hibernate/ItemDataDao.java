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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ItemData;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

public class ItemDataDao extends AbstractDomainDao<ItemData> {

    Class<ItemData> domainClass() {
        return ItemData.class;
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    public ItemData findByItemEventCrfOrdinal(Integer itemId, Integer eventCrfId, Integer ordinal) {
        String query = "from " + getDomainClassName()
                + " item_data where item_data.item.itemId = :itemid and item_data.eventCrf.eventCrfId = :eventcrfid and item_data.ordinal = :ordinal";
        Query<ItemData> q = getCurrentSession().createQuery(query, ItemData.class);
        q.setParameter("itemid", itemId);
        q.setParameter("eventcrfid", eventCrfId);
        q.setParameter("ordinal", ordinal);
        return q.uniqueResult();
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public List<ItemData> findAllByEventCrf(Integer eventCrfId) {
        String query = "select * from item_data where event_crf_id = " + eventCrfId;
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(ItemData.class);
        
        return (List<ItemData>) q.list();
      
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings({ "rawtypes", "unchecked" })
	public List<ItemData> findByEventCrfGroup(Integer eventCrfId, Integer itemGroupId) {
        String query = "select id.* " + 
            "from item_data id " + 
            "join item i on id.item_id = i.item_id " + 
            "join event_crf ec on id.event_crf_id=ec.event_crf_id " + 
            "join item_group_metadata igm on i.item_id=igm.item_id and igm.crf_version_id = ec.crf_version_id " + 
            "where id.event_crf_id = " + eventCrfId + " and igm.item_group_id = " + itemGroupId + " " + 
            "order by id.ordinal, igm.ordinal";
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(ItemData.class);
        
        return (List<ItemData>) q.list();
      
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    public List<ItemData> findByEventCrfId(Integer eventCrfId) {
        String query = "from " + getDomainClassName() + " item_data where item_data.eventCrf.eventCrfId = :eventcrfid";
        Query<ItemData> q = getCurrentSession().createQuery(query, ItemData.class);
        q.setParameter("eventcrfid", eventCrfId);
        return q.list();
      
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("rawtypes")
    public int getMaxGroupRepeat(Integer eventCrfId, Integer itemId) {
        getCurrentSession().flush();
        String query = "select max(ordinal) from item_data where event_crf_id = " + eventCrfId + " and item_id = " + itemId;
        Query q = getCurrentSession().createNativeQuery(query);
        Number result = (Number) q.uniqueResult();
        if (result == null) return 0;
        else return result.intValue();
    }
}
