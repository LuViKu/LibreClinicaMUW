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

// 2026-06-28 — Session.createQuery(String) / createNativeQuery(String)

// were deprecated in Hibernate 6.5 in favour of typed overloads. The

// per-call typed-form migration needs each query's expected result

// type reviewed manually — deferred B.5 follow-up. Suppression here

// is intentional and isolated to this DAO.

@SuppressWarnings("deprecation")

public class StudyUserRoleDao extends CompositeIdAbstractDomainDao<StudyUserRole> {

    @Override
    public Class<StudyUserRole> domainClass() {
        return StudyUserRole.class;
    }

    @SuppressWarnings("unchecked")
    public ArrayList<StudyUserRole> findAllUserRolesByUserAccount(UserAccount userAccount, int studyId, int parentStudyId) {
        String query = "from " + getDomainClassName()
                + "   where   user_name=:username  AND  status_id=1  AND  ( study_id=:studyId OR study_id=:parentStudyId) ";
        Query<StudyUserRole> q = getCurrentSession().createQuery(query);
        q.setParameter("username", userAccount.getUserName());
        q.setParameter("studyId", studyId);
        q.setParameter("parentStudyId", parentStudyId);
        return new ArrayList<StudyUserRole>(q.getResultList());
    }

}
