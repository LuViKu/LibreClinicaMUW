/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.helper;

import java.util.List;
import java.util.ResourceBundle;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.admin.CRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.CRFVersionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.CrfVersionDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.EventCrfDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.StudyEventDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.StudySubjectDao;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

public class HelperObject {
    List<EventCRFBean> eventCrfListToMigrate;
    CRFVersionBean sourceCrfVersionBean;
    CRFVersionBean targetCrfVersionBean;
    ReportLog reportLog;
    StudyBean stBean;
    CRFBean cBean;
    HttpServletRequest request;
    DataSource dataSource;
    UserAccountBean userAccountBean;
    ResourceBundle resterms;
    String urlBase;
    OpenClinicaMailSender openClinicaMailSender;
    EventCrfDao eventCrfDao;
    StudyEventDao studyEventDao;
    StudySubjectDao studySubjectDao;
    CrfVersionDao crfVersionDao;
    SessionFactory sessionFactory;
    Session session;

    public HelperObject() {
        // TODO Auto-generated constructor stub
    }

    public CRFVersionBean getSourceCrfVersionBean() {
        return sourceCrfVersionBean;
    }

    public void setSourceCrfVersionBean(CRFVersionBean sourceCrfVersionBean) {
        this.sourceCrfVersionBean = sourceCrfVersionBean;
    }

    public CRFVersionBean getTargetCrfVersionBean() {
        return targetCrfVersionBean;
    }

    public void setTargetCrfVersionBean(CRFVersionBean targetCrfVersionBean) {
        this.targetCrfVersionBean = targetCrfVersionBean;
    }

    public ReportLog getReportLog() {
        return reportLog;
    }

    public void setReportLog(ReportLog reportLog) {
        this.reportLog = reportLog;
    }

    public StudyBean getStBean() {
        return stBean;
    }

    public void setStBean(StudyBean stBean) {
        this.stBean = stBean;
    }

    public CRFBean getcBean() {
        return cBean;
    }

    public void setcBean(CRFBean cBean) {
        this.cBean = cBean;
    }

    public HttpServletRequest getRequest() {
        return request;
    }

    public void setRequest(HttpServletRequest request) {
        this.request = request;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public UserAccountBean getUserAccountBean() {
        return userAccountBean;
    }

    public void setUserAccountBean(UserAccountBean userAccountBean) {
        this.userAccountBean = userAccountBean;
    }

    public ResourceBundle getResterms() {
        return resterms;
    }

    public void setResterms(ResourceBundle resterms) {
        this.resterms = resterms;
    }

    public String getUrlBase() {
        return urlBase;
    }

    public void setUrlBase(String urlBase) {
        this.urlBase = urlBase;
    }

    public OpenClinicaMailSender getOpenClinicaMailSender() {
        return openClinicaMailSender;
    }

    public void setOpenClinicaMailSender(OpenClinicaMailSender openClinicaMailSender) {
        this.openClinicaMailSender = openClinicaMailSender;
    }

    public List<EventCRFBean> getEventCrfListToMigrate() {
        return eventCrfListToMigrate;
    }

    public void setEventCrfListToMigrate(List<EventCRFBean> eventCrfListToMigrate) {
        this.eventCrfListToMigrate = eventCrfListToMigrate;
    }

    public EventCrfDao getEventCrfDao() {
        return eventCrfDao;
    }

    public void setEventCrfDao(EventCrfDao eventCrfDao) {
        this.eventCrfDao = eventCrfDao;
    }

    public StudyEventDao getStudyEventDao() {
        return studyEventDao;
    }

    public void setStudyEventDao(StudyEventDao studyEventDao) {
        this.studyEventDao = studyEventDao;
    }

    public StudySubjectDao getStudySubjectDao() {
        return studySubjectDao;
    }

    public void setStudySubjectDao(StudySubjectDao studySubjectDao) {
        this.studySubjectDao = studySubjectDao;
    }

    public CrfVersionDao getCrfVersionDao() {
        return crfVersionDao;
    }

    public void setCrfVersionDao(CrfVersionDao crfVersionDao) {
        this.crfVersionDao = crfVersionDao;
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public void setSessionFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }


}
