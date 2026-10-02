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

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudyUserRole;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.user.UserAccount;
import org.hibernate.query.Query;

public class StudyUserRoleDao extends CompositeIdAbstractDomainDao<StudyUserRole> {

    @Override
    public Class<StudyUserRole> domainClass() {
        return StudyUserRole.class;
    }

    public ArrayList<StudyUserRole> findAllUserRolesByUserAccount(UserAccount userAccount, int studyId, int parentStudyId) {
        // user_name, status_id and study_id are mapped as attributes of the
        // embedded id. Hibernate 6 resolves only attribute paths, so the bare
        // column names are a semantic error.
        String query = "from " + getDomainClassName() + " sur"
                + " where sur.id.userName = :username and sur.id.statusId = 1"
                + " and (sur.id.studyId = :studyId or sur.id.studyId = :parentStudyId)";
        Query<StudyUserRole> q = getCurrentSession().createQuery(query, StudyUserRole.class);
        q.setParameter("username", userAccount.getUserName());
        q.setParameter("studyId", studyId);
        q.setParameter("parentStudyId", parentStudyId);
        return new ArrayList<StudyUserRole>(q.getResultList());
    }

}
