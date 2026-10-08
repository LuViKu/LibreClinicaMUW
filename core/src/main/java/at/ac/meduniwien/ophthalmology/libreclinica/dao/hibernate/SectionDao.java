/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.Section;
import org.hibernate.query.NativeQuery;

@SuppressWarnings("resource") // Session comes from the JPA EntityManager (getCurrentSession); the transaction manager closes it
public class SectionDao extends AbstractDomainDao<Section> {

    @Override
    Class<Section> domainClass() {
        return Section.class;
    }

    public Section findByCrfVersionOrdinal(int crfVersionId, int ordinal) {
        String query = " select s.* from section s where s.crf_version_id = :crfVersionId and ordinal = :ordinal ";
        NativeQuery<Section> q = getCurrentSession().createNativeQuery(query, domainClass());
        q.setParameter("crfVersionId", crfVersionId);
        q.setParameter("ordinal", ordinal);
        q.setCacheable(true);
        return q.getSingleResultOrNull();
    }

}
