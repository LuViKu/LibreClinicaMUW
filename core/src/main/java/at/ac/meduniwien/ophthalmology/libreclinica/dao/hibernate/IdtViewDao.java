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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.IdtView;
import org.hibernate.query.Query;

@SuppressWarnings("all")

public class IdtViewDao extends AbstractDomainDao<IdtView> {

    @Override
    Class<IdtView> domainClass() {
        return IdtView.class;
    }


    
    public List<IdtView> findFilter1(int studyId, int pStudyId, int per_page, int page, ArrayList<String> studySubjects,
            ArrayList<String>eventDefs,ArrayList<String> crfs , int tagId, String operation) {
        // Values are bound, and the connector is one of two keywords: the
        // heritage version concatenated every value into the HQL.
        String connector = "OR".equalsIgnoreCase(operation) ? "or" : "and";
        if (!"OR".equalsIgnoreCase(operation) && !"AND".equalsIgnoreCase(operation)) {
            throw new IllegalArgumentException("operation must be AND or OR");
        }
        StringBuilder query = new StringBuilder(" from ").append(getDomainClassName()).append(" where tagId = :tagId");
        if (!studySubjects.isEmpty()) query.append(" and studySubjectId in (:studySubjects)");
        if (!eventDefs.isEmpty()) query.append(" and sedOid in (:eventDefs)");
        query.append(" and eventCrfId in (select eventCrfId from ").append(getDomainClassName())
                .append(" where  path is not null and (itemDataWorkflowStatus is null or itemDataWorkflowStatus!='done') group by eventCrfId))");
        query.append(" and ((");
        if (!crfs.isEmpty()) query.append(" crfName in (:crfs) and");
        query.append(" eventCrfStatusId=1) or eventCrfStatusId=2)  and (studyId = :studyId ").append(connector)
                .append(" parentStudyId = :pStudyId) ) ");
        query.append(" order by itemDataId");

        Query<IdtView> q = getCurrentSession().createQuery(query.toString(), IdtView.class);
        q.setParameter("tagId", tagId);
        q.setParameter("studyId", studyId);
        q.setParameter("pStudyId", pStudyId);
        if (!studySubjects.isEmpty()) q.setParameterList("studySubjects", studySubjects);
        if (!eventDefs.isEmpty()) q.setParameterList("eventDefs", eventDefs);
        if (!crfs.isEmpty()) q.setParameterList("crfs", crfs);
        q.setMaxResults(per_page); // limit
        q.setFirstResult((page - 1) * per_page); // offset
        return q.getResultList();
    }

    
    public String getListOf(ArrayList<String> objects){
        String str="";
        String netStr="";
        for (String object:objects){
            str= str+ ",'"+object+"'";            
        }
        
        netStr=str.substring(1);
       return netStr; 
    }
    
    
}
