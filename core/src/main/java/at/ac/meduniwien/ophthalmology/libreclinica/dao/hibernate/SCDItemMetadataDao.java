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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.crfdata.SCDItemMetadataBean;
import org.hibernate.query.NativeQuery;

public class SCDItemMetadataDao extends AbstractDomainDao<SCDItemMetadataBean>{

    @Override
    Class<SCDItemMetadataBean> domainClass() {
        return SCDItemMetadataBean.class;
    }

    public ArrayList<SCDItemMetadataBean> findAllBySectionId(Integer sectionId) {
        String query = "select scd.* from scd_item_metadata scd where scd.scd_item_form_metadata_id in ("
            + "select ifm.item_form_metadata_id from item_form_metadata ifm where ifm.section_id = :sectionId)";
        NativeQuery<SCDItemMetadataBean> q = this.getCurrentSession().createNativeQuery(query, this.domainClass());
        q.setParameter("sectionId", sectionId);
        return new ArrayList<>(q.getResultList());
    }
}
