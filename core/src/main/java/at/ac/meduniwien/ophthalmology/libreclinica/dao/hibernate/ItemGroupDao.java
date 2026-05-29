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

import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.ItemGroupOidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.OidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.CrfBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.ItemGroup;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

public class ItemGroupDao extends AbstractDomainDao<ItemGroup> {

    @Override
    Class<ItemGroup> domainClass() {
        return ItemGroup.class;
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    public ItemGroup findByOcOID(String OCOID) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.ocOid = :OCOID";
        Query<ItemGroup> q = getCurrentSession().createQuery(query, ItemGroup.class);
        q.setParameter("OCOID", OCOID);
        return q.uniqueResult();
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    public ItemGroup findByNameCrfId(String groupName, CrfBean crf) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.name = :groupName and do.crf = :crf";
        Query<ItemGroup> q = getCurrentSession().createQuery(query, ItemGroup.class);
        q.setParameter("groupName", groupName);
        // Hibernate 6: Query.setEntity is gone; setParameter handles entity binding too.
        q.setParameter("crf", crf);
        return q.uniqueResult();
    }

    public static final String findAllByCrfVersionIdQuery = "select distinct ig.* from item_group ig, item_group_metadata igm"
            + " where igm.crf_version_id = :crfversionid and ig.item_group_id = igm.item_group_id";

    // TODO update to CriteriaQuery 
    @SuppressWarnings({ "deprecation", "rawtypes", "unchecked" })
    public ArrayList<ItemGroup> findByCrfVersionId(Integer crfVersionId) {
        NativeQuery q = getCurrentSession().createNativeQuery(findAllByCrfVersionIdQuery).addEntity(ItemGroup.class);
        q.setParameter("crfversionid", crfVersionId.intValue());
        return (ArrayList<ItemGroup>) q.list();
    }

    public String getValidOid(ItemGroup itemGroup, String crfName, String itemGroupLabel, ArrayList<String> oidList) {
    OidGenerator oidGenerator = new ItemGroupOidGenerator();
        String oid = getOid(itemGroup, crfName, itemGroupLabel);
        String oidPreRandomization = oid;
        while (findByOcOID(oid) != null || oidList.contains(oid)) {
            oid = oidGenerator.randomizeOid(oidPreRandomization);
        }
        return oid;
    }

    private String getOid(ItemGroup itemGroup, String crfName, String itemGroupLabel) {
        OidGenerator oidGenerator = new ItemGroupOidGenerator();
        String oid;
        try {
            oid = itemGroup.getOcOid() != null ? itemGroup.getOcOid() : oidGenerator.generateOid(crfName, itemGroupLabel);
            return oid;
        } catch (Exception e) {
            throw new RuntimeException("CANNOT GENERATE OID");
        }
    }
}
