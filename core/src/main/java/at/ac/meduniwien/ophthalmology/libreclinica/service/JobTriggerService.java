/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.Status;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.expression.ExpressionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.i18n.util.ResourceBundleProvider;
import at.ac.meduniwien.ophthalmology.libreclinica.patterns.ocobserver.StudyEventChangeDetails;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetService;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class JobTriggerService {
    @Autowired
    RuleSetDao ruleSetDao;
    @Autowired
    DataSource ds;
    @Autowired
    RuleSetService ruleSetService;

    protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
    private static final SimpleDateFormat currentDateFormat = new SimpleDateFormat("HH:mm:ss");

    // @Scheduled(cron = "0 0/2 * * * ?") // trigger every 2 minutes
    // @Scheduled(cron = "0 0/1 * * * ?") // trigger every minute
    @Scheduled(cron = "0 0 0/1 * * ?") // trigger every hour
    public void hourlyJobTrigger() throws NumberFormatException, ParseException {
        try {
            logger.info("Beginning scheduled rule run.  The time is now " + currentDateFormat.format(new Date()));
            triggerJob();
            logger.info("Completed scheduled rule run.");
        } catch (Exception e) {
            logger.error(e.getMessage());
            logger.error(ExceptionUtils.getStackTrace(e));
            throw e;
        }
    }

    public void triggerJob(){
        ResourceBundleProvider.updateLocale(new Locale("en_US"));
        ArrayList<RuleSetBean> ruleSets = ruleSetDao.findAllRunOnSchedules(true);
        for (RuleSetBean ruleSet : ruleSets) {
			if (Status.AVAILABLE != null && ruleSet.isRunSchedule()) {
                if(ruleSet.getItemId()!=null){ 
                     // item Specific Rule
                    ArrayList<RuleSetBean> ruleSetBeans = new ArrayList<>();
                    StudyBean currentStudy = (StudyBean) getStudyDao().findByPK(ruleSet.getStudyId());
                    ResourceBundleProvider.updateLocale(Locale.getDefault());
                    UserAccountBean ub = (UserAccountBean) getUserAccountDao().findByPK(1);
                    ruleSetBeans.add(ruleSet);
                    ruleSetService.runRulesInBulk(ruleSetBeans, false, currentStudy, ub, true);
                }else{
                    // Event Specific Rule        
                    StudyEventChangeDetails studyEventChangeDetails = new StudyEventChangeDetails(true, true);
                    ArrayList<RuleSetBean> ruleSetBeans = new ArrayList<>();
                    ExpressionBean eBean = new ExpressionBean();
                    eBean.setValue(ruleSet.getTarget().getValue()+".A.B");
                    
                    ruleSet.setTarget(eBean);
                    ruleSetBeans.add(ruleSet);
        
                    ruleSetService.runRulesInBeanProperty(ruleSetBeans ,1, studyEventChangeDetails);

                }
            }
        }
    }



    public StudySubjectDAO getStudySubjecdao() {
        return new StudySubjectDAO(ds);
    }

    public UserAccountDAO getUserAccountDao() {
        return new UserAccountDAO(ds);
    }

    public StudyDAO getStudyDao() {
        return new StudyDAO(ds);
    }

}