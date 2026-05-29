/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.CrfVersionOidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.oid.OidGenerator;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.CrfVersion;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;

public class CrfVersionDao extends AbstractDomainDao<CrfVersion> {

    @Override
    Class<CrfVersion> domainClass() {
        // TODO Auto-generated method stub
        return CrfVersion.class;
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    public CrfVersion findByCrfVersionId(int crf_version_id) {
        String query = "from " + getDomainClassName() + " crf_version  where crf_version.crfVersionId = :crfversionid ";
        Query<CrfVersion> q = getCurrentSession().createQuery(query, CrfVersion.class);
        q.setParameter("crfversionid", crf_version_id);
        return (CrfVersion) q.uniqueResult();
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("deprecation")
    public CrfVersion findByOcOID(String OCOID) {
        getSessionFactory().getStatistics().logSummary();
        String query = "from " + getDomainClassName() + " do  where do.ocOid = :OCOID";
        Query<CrfVersion> q = getCurrentSession().createQuery(query, CrfVersion.class);
        q.setParameter("OCOID", OCOID);
        return (CrfVersion) q.uniqueResult();
    }

    // TODO update to CriteriaQuery 
    @SuppressWarnings("rawtypes")
    public CrfVersion findByNameCrfId(String name, Integer crfId) {
        String query = "select distinct cv.* from crf_version cv,crf c " + "where c.crf_id = " + crfId + " and cv.name = '" + name
                + "' and cv.crf_id = c.crf_id";
        NativeQuery q = getCurrentSession().createNativeQuery(query).addEntity(CrfVersion.class);
        return ((CrfVersion) q.uniqueResult());
    }
    
    private String getOid(CrfVersion crfVersion, String crfName, String crfVersionName) {
        OidGenerator oidGenerator = new CrfVersionOidGenerator();
        String oid;
        try {
            oid = crfVersion.getOcOid() != null ? crfVersion.getOcOid() : oidGenerator.generateOid(crfName, crfVersionName);
            return oid;
        } catch (Exception e) {
            throw new RuntimeException("CANNOT GENERATE OID");
        }
    }

    public String getValidOid(CrfVersion crfVersion, String crfName, String crfVersionName) {
        OidGenerator oidGenerator = new CrfVersionOidGenerator();
        String oid = getOid(crfVersion, crfName, crfVersionName);
        String oidPreRandomization = oid;
        while (findByOcOID(oid) != null) {
            oid = oidGenerator.randomizeOid(oidPreRandomization);
        }
        return oid;

    }


}
