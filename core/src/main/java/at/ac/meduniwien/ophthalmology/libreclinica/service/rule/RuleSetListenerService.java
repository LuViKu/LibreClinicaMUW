/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.rule;

import java.util.ArrayList;
import java.util.List;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.datamap.StudyEvent;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.patterns.ocobserver.OnStudyEventUpdated;
import org.apache.commons.lang.SerializationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

@Service
public class RuleSetListenerService implements ApplicationListener<OnStudyEventUpdated>  {

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass().getName());

	private RuleSetService ruleSetService;

	private RuleSetDao ruleSetDao;




@Override
	public void onApplicationEvent(final OnStudyEventUpdated event) {
	
		LOGGER.debug("listening");
	if (event.getContainer().getChangeDetails().getStartDateChanged() || event.getContainer().getChangeDetails().getStatusChanged()){ 
       
		StudyEvent studyEvent = event.getContainer().getEvent();
		
		Integer studyEventDefId = studyEvent.getStudyEventDefinition().getStudyEventDefinitionId();
		Integer studyEventOrdinal = studyEvent.getSampleOrdinal();
		Integer userId = studyEvent.getUpdateId();
		
		if(userId==null && studyEvent.getUserAccount()!=null ) userId=  studyEvent.getUserAccount().getUserId();
		  
		StudyEventBean studyEventBean = new StudyEventBean();
		studyEventBean.setId(studyEvent.getStudyEventId());

		ArrayList<RuleSetBean> ruleSets = (ArrayList<RuleSetBean>) createRuleSet(studyEventDefId);
		for (RuleSetBean ruleSet : ruleSets){
			ArrayList<RuleSetBean> ruleSetBeans = new ArrayList<>();		
	            ExpressionBean eBean = new ExpressionBean();
    			eBean.setValue(ruleSet.getTarget().getValue()+".A.B");
    			ruleSet.setTarget(eBean);
    			ruleSet.addExpression(getRuleSetService().replaceSEDOrdinal(ruleSet.getTarget(), studyEventBean));
			ruleSetBeans.add(ruleSet);
			getRuleSetService().runIndividualRulesInBeanProperty(ruleSetBeans, userId,event.getContainer().getChangeDetails() , studyEventOrdinal);
        	}	
		   
		}

}

public RuleSetService getRuleSetService() {
	return ruleSetService;
}


public void setRuleSetService(RuleSetService ruleSetService) {
	this.ruleSetService = ruleSetService;
}


public RuleSetDao getRuleSetDao() {
	return ruleSetDao;
}


public void setRuleSetDao(RuleSetDao ruleSetDao) {
	this.ruleSetDao = ruleSetDao;
}

private List<RuleSetBean> createRuleSet(Integer studyEventDefId) {	
    List<RuleSetBean> ruleSetsDB = new ArrayList<RuleSetBean>();
    List<RuleSetBean> ruleSetCopies = new ArrayList<RuleSetBean>();
	ruleSetsDB = getRuleSetDao().findAllByStudyEventDefIdWhereItemIsNull(studyEventDefId);
	
	for (RuleSetBean ruleSetDB:ruleSetsDB) { 
	    RuleSetBean ruleSetCopy = (RuleSetBean) SerializationUtils.clone(ruleSetDB);
	    ruleSetCopies.add(ruleSetCopy);
	}
	return ruleSetCopies;
}

}
