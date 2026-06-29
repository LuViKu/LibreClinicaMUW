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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.crfdata.SCDItemMetadataBean;
import org.hibernate.query.NativeQuery;

// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)

// were deprecated in Hibernate 6.5 in favour of typed overloads. The

// per-call typed-form migration needs each query's expected result

// type reviewed manually — deferred B.5 follow-up. Suppression here

// is intentional and isolated to this DAO.

@SuppressWarnings("all")

public class SCDItemMetadataDao extends AbstractDomainDao<SCDItemMetadataBean>{
    
    @Override
    Class<SCDItemMetadataBean> domainClass() {
        return SCDItemMetadataBean.class;
    }
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ArrayList<SCDItemMetadataBean> findAllBySectionId(Integer sectionId) {
        String query = "select scd.* from scd_item_metadata scd where scd.scd_item_form_metadata_id in ("
            + "select ifm.item_form_metadata_id from item_form_metadata ifm where ifm.section_id = :sectionId)";
        NativeQuery q = this.getCurrentSession().createNativeQuery(query).addEntity(this.domainClass());
        q.setParameter("sectionId", sectionId);
        return (ArrayList<SCDItemMetadataBean>) q.getResultList();  
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public List<Integer> findAllSCDItemFormMetadataIdsBySectionId(Integer sectionId) {
        String query = "select scd.scd_item_form_metadata_id from scd_item_metadata scd where scd.scd_item_form_metadata_id in ("
        + "select ifm.item_form_metadata_id from item_form_metadata ifm where ifm.section_id = :sectionId)";
        NativeQuery q = this.getCurrentSession().createNativeQuery(query);
        q.setParameter("sectionId", sectionId);
        return q.getResultList();
    }
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ArrayList<SCDItemMetadataBean> findAllSCDByItemFormMetadataId(Integer itemFormMetadataId) {
        String query = "select scd.* from scd_item_metadata scd where scd.scd_item_form_metadata_id = :itemFormMetadataId)";
        NativeQuery q = this.getCurrentSession().createNativeQuery(query);
        q.setParameter("itemFormMetadataId", itemFormMetadataId);
        return (ArrayList<SCDItemMetadataBean>) q.getResultList();
    }
}
