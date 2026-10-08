/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import org.hibernate.query.NativeQuery;
import org.hibernate.query.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
@SuppressWarnings("resource") // Session comes from the JPA EntityManager (getCurrentSession); the transaction manager closes it
public class RuleSetDao extends AbstractDomainDao<RuleSetBean> {

    @Override
    public Class<RuleSetBean> domainClass() {
        return RuleSetBean.class;
    }

    public RuleSetBean findById(Integer id, StudyBean study) {
        String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.id = :id and ruleSet.studyId = :studyId ";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, RuleSetBean.class);
        q.setParameter("id", id);
        q.setParameter("studyId", study.getId());
        return q.getSingleResultOrNull();
    }

    public Long count(StudyBean study) {
        String query = "select count(*) from " + domainClass().getName() + " ruleSet where ruleSet.studyId = :studyId " + " AND ruleSet.status != :status ";
        Query<Long> q = getCurrentSession().createQuery(query, Long.class);
        q.setParameter("studyId", study.getId());
        q.setParameter("status", Status.DELETED);
        return q.getSingleResultOrNull();

    }

    @Transactional
    public ArrayList<RuleSetBean> findByCrfVersionOrCrfAndStudyAndStudyEventDefinition(CRFVersionBean crfVersion, CRFBean crfBean, StudyBean currentStudy,
            StudyEventDefinitionBean sed) {
        // Using a sql query because we are referencing objects not managed by hibernate
        String query =
            " select rs.* from rule_set rs where rs.study_id = :studyId " + " AND (( rs.study_event_definition_id = :studyEventDefinitionId "
                + " AND (( rs.crf_version_id = :crfVersionId AND rs.crf_id = :crfId ) "
                + " OR (rs.crf_version_id is null AND rs.crf_id = :crfId ))) OR ( rs.study_event_definition_id is null "
                + " and rs.item_id in (select item_id from item_form_metadata where crf_version_id = :crfVersionId)  ))";
        NativeQuery<RuleSetBean> q = getCurrentSession().createNativeQuery(query, domainClass());
        q.setParameter("crfVersionId", crfVersion.getId());
        q.setParameter("crfId", crfBean.getId());
        q.setParameter("studyId", currentStudy.getParentStudyId() != 0 ? currentStudy.getParentStudyId() : currentStudy.getId());
        q.setParameter("studyEventDefinitionId", sed.getId());
        q.setCacheable(true);

        return new ArrayList<RuleSetBean>(q.getResultList());
    }

    public ArrayList<RuleSetBean> findAllByStudy(StudyBean currentStudy) {
        String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.studyId = :studyId  ";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, RuleSetBean.class);
        q.setParameter("studyId", currentStudy.getId());
        return new ArrayList<>(q.getResultList());
    }

    public ArrayList<RuleSetBean> findByCrf(CRFBean crfBean, StudyBean currentStudy) {
        String query =
            " select rs.* from rule_set rs where rs.study_id = :studyId "
                + " AND rs.item_id in ( select distinct(item_id) from item_form_metadata ifm,crf_version cv "
                + " where ifm.crf_version_id = cv.crf_version_id and cv.crf_id = :crfId) ";
        // Using a sql query because we are referencing objects not managed by hibernate
        NativeQuery<RuleSetBean> q = getCurrentSession().createNativeQuery(query, domainClass());
        q.setParameter("crfId", crfBean.getId());
        q.setParameter("studyId", currentStudy.getId());
        return new ArrayList<>(q.getResultList());
    }

    public RuleSetBean findByExpression(RuleSetBean ruleSet) {
        String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.originalTarget.value = :value AND ruleSet.originalTarget.context = :context ";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, RuleSetBean.class);
        q.setParameter("value", ruleSet.getTarget().getValue());
        q.setParameter("context", ruleSet.getTarget().getContext());
        return q.getSingleResultOrNull();
    }

    public RuleSetBean findByExpressionAndStudy(RuleSetBean ruleSet, Integer studyId) {
        String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.originalTarget.value = :value " +
        		"AND ruleSet.originalTarget.context = :context " +
        		"AND ruleSet.studyId = :studyId ";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, RuleSetBean.class);
        q.setParameter("value", ruleSet.getTarget().getValue());
        q.setParameter("context", ruleSet.getTarget().getContext());
        q.setParameter("studyId", studyId);
        return q.getSingleResultOrNull();
    }

    public Long getCountByStudy(StudyBean currentStudy) {
        String query = "select count(*) from " + getDomainClassName() + " ruleSet  where ruleSet.studyId = :studyId and ruleSet.status = :status ";
        Query<Long> q = getCurrentSession().createQuery(query, Long.class);
        q.setParameter("studyId", currentStudy.getId());
        q.setParameter("status", at.ac.meduniwien.ophthalmology.libreclinica.domain.Status.AVAILABLE);
        return q.getSingleResultOrNull();
    }

    public ArrayList<RuleSetBean> findAllByStudyEventDef(StudyEventDefinitionBean sed){
    	String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.studyEventDefinitionId = :studyEventDefId  ";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, RuleSetBean.class);
        q.setParameter("studyEventDefId", sed.getId());
        return new ArrayList<>(q.getResultList());
    }
    
    public ArrayList<RuleSetBean> findAllEventActions(StudyBean currentStudy){
    	String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.originalTarget.value LIKE '%.STARTDATE%' or ruleSet.originalTarget.value LIKE '%.STATUS%' and ruleSet.studyId = :studyId ";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, RuleSetBean.class);
        q.setParameter("studyId", currentStudy.getId());
        return new ArrayList<>(q.getResultList());
    }

    @Transactional
    public ArrayList<RuleSetBean> findAllRunOnSchedules(Boolean shedule){
    	String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.runSchedule = :shedule";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, RuleSetBean.class);
        q.setParameter("shedule", shedule);
        return new ArrayList<>(q.getResultList());
    }

    @Transactional
    public ArrayList<RuleSetBean> findAllRunOnSchedulesPerSchema(Boolean shedule, String schema){
        String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.runSchedule = :shedule";
        Query<RuleSetBean> q = getCurrentSession(schema).createQuery(query, RuleSetBean.class);
        q.setParameter("shedule", shedule);
        return new ArrayList<>(q.getResultList());
    }

    @Transactional
    public ArrayList<RuleSetBean> findAllByStudyEventDefIdWhereItemIsNull(Integer studyEventDefId){
    	String query = "from " + getDomainClassName() + " ruleSet  where ruleSet.studyEventDefinitionId = :studyEventDefId  and ruleSet.itemId is null";
        Query<RuleSetBean> q = getCurrentSession().createQuery(query, domainClass());
        q.setParameter("studyEventDefId", studyEventDefId);
        return new ArrayList<>(q.getResultList());
    }
}
