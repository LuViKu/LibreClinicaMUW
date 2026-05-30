/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2003 - 2011 Akaza Research
 * copyright (C) 2003 - 2019 OpenClinica
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action;

import java.util.ArrayList;

import jakarta.mail.internet.MimeMessage;
import javax.sql.DataSource;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.ParticipantDTO;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventDefinitionBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudySubjectBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.service.StudyParameterValueBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.EmailEngine;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.login.UserAccountDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.service.StudyParameterValueDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ExecutionMode;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.RuleRunner.RuleRunnerMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.BulkEmailSenderService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.pmanage.ParticipantPortalRegistrar;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;

public class NotificationActionProcessor implements ActionProcessor, Runnable {

	protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
	DataSource ds;
	EmailEngine emailEngine;
	JavaMailSenderImpl mailSender;
	RuleSetRuleBean ruleSetRule;
	StudySubjectDAO ssdao;
	UserAccountDAO udao;
	StudyParameterValueDAO spvdao;
	RuleSetService ruleSetService;
	RuleSetDao ruleSetDao;
	ParticipantDTO pDTO;
	RuleActionBean ruleActionBean;
	ParticipantPortalRegistrar participantPortalRegistrar;
	String email;
	String[] listOfEmails;
	StudySubjectBean ssBean;
	UserAccountBean uBean;
	StudyBean studyBean;
	String message;
	String url;
	String emailSubject;
	String participateStatus;

	public NotificationActionProcessor(DataSource ds, JavaMailSenderImpl mailSender, RuleActionBean ruleActionBean, ParticipantDTO pDTO, ParticipantPortalRegistrar participantPortalRegistrar,
			String email) {
		this.ds = ds;
		this.mailSender = mailSender;
		this.ruleActionBean = ruleActionBean;
		this.pDTO = pDTO;
		this.participantPortalRegistrar = participantPortalRegistrar;
		this.email = email;

	}

	public NotificationActionProcessor(String[] listOfEmails, UserAccountBean uBean, StudyBean studyBean, String message, String emailSubject, ParticipantPortalRegistrar participantPortalRegistrar,
			JavaMailSenderImpl mailSender , String participateStatus) {
		this.listOfEmails = listOfEmails;
		this.message = message;
		this.emailSubject = emailSubject;
		this.uBean = uBean;
		this.participantPortalRegistrar = participantPortalRegistrar;
		this.mailSender = mailSender;
		this.studyBean = studyBean;
		this.participateStatus=participateStatus;

	}

	public NotificationActionProcessor(DataSource ds, JavaMailSenderImpl mailSender, RuleSetRuleBean ruleSetRule) {
		this.ds = ds;
		this.mailSender = mailSender;
		this.ruleSetRule = ruleSetRule;
		ssdao = new StudySubjectDAO(ds);
		udao = new UserAccountDAO(ds);
  	   spvdao = new StudyParameterValueDAO(ds);



	}

	public RuleActionBean execute(ExecutionMode executionMode, RuleActionBean ruleActionBean, ParticipantDTO pDTO , String email  ) {
		switch (executionMode) {
		case DRY_RUN: {
			return ruleActionBean;
		}

		case SAVE: {
			createMimeMessagePreparator(pDTO, email);
			return null;
		}
		default:
			return null;
		}
	}

	
	private void createMimeMessagePreparator(final ParticipantDTO pDTO, final String email){
        MimeMessagePreparator preparator = new MimeMessagePreparator() {
            public void prepare(MimeMessage mimeMessage) throws Exception {
                MimeMessageHelper message = new MimeMessageHelper(mimeMessage);
                message.setFrom(EmailEngine.getAdminEmail());
                message.setTo(email);
                message.setSubject(pDTO.getEmailSubject());
                message.setText(pDTO.getMessage());
            }
        };
        BulkEmailSenderService.addMimeMessage(preparator);
    }
	
	@Override
	public RuleActionBean execute(RuleRunnerMode ruleRunnerMode, ExecutionMode executionMode, RuleActionBean ruleAction, ItemDataBean itemDataBean, String itemData, StudyBean currentStudy,
			UserAccountBean ub, Object... arguments) {
		// TODO Auto-generated method stub
		return null;
	}

	public void runNotificationAction(RuleActionBean ruleActionBean, RuleSetBean ruleSet, int studySubjectBeanId, int eventOrdinal) {
		String emailList = ((NotificationActionBean) ruleActionBean).getTo();
		String message = ((NotificationActionBean) ruleActionBean).getMessage();
		String emailSubject = ((NotificationActionBean) ruleActionBean).getSubject();

		int sed_Id = ruleSet.getStudyEventDefinitionId();
		int studyId = ruleSet.getStudyId();

		String eventName = getStudyEventDefnBean(sed_Id).getName();
		if (eventOrdinal != 1)
			eventName = eventName + "(" + eventOrdinal + ")";

		String studyName = getStudyBean(studyId).getName();
		if (message==null) message="";
        if (emailSubject==null) emailSubject="";
		message = message.replaceAll("\\$\\{event.name}", eventName);
		message = message.replaceAll("\\$\\{study.name}", studyName);
		emailSubject = emailSubject.replaceAll("\\$\\{event.name}", eventName);
		emailSubject = emailSubject.replaceAll("\\$\\{study.name}", studyName);

		StudyBean studyBean = getStudyBean(studyId);
		String[] listOfEmails = emailList.split(",");
		StudySubjectBean ssBean = (StudySubjectBean) ssdao.findByPK(studySubjectBeanId);
		StudyBean parentStudyBean = getParentStudy(ds, studyBean);
		String pUserName = parentStudyBean.getOid() + "." + ssBean.getOid();
		UserAccountBean uBean = (UserAccountBean) udao.findByUserName(pUserName);

		StudyParameterValueBean pStatus = spvdao.findByHandleAndStudy(studyBean.getId(), "participantPortal");
		String participateStatus = pStatus.getValue().toString(); // enabled , disabled

		Thread thread = new Thread(new NotificationActionProcessor(listOfEmails, uBean, studyBean, message, emailSubject, participantPortalRegistrar, mailSender,participateStatus));
		thread.start();

	}

	@Override
	public void run() {

		String hostname = "";
		String url = "";
		participantPortalRegistrar = new ParticipantPortalRegistrar();

		try {
			hostname = participantPortalRegistrar.getStudyHost(studyBean.getOid());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	    url = hostname.substring(0,hostname.indexOf("/app/oauth2")) + "/#/plogin";

		message = message.replaceAll("\\$\\{participant.url}", url);
		emailSubject = emailSubject.replaceAll("\\$\\{participant.url}", url);

		pDTO = getParticipantInfo(uBean);
		if (pDTO != null) {
			String msg = null;
			String eSubject = null;
			msg = message.replaceAll("\\$\\{participant.accessCode}", pDTO.getAccessCode());
			msg = msg.replaceAll("\\$\\{participant.firstname}", pDTO.getfName());
			eSubject = emailSubject.replaceAll("\\$\\{participant.accessCode}", pDTO.getAccessCode());
			eSubject = eSubject.replaceAll("\\$\\{participant.firstname}", pDTO.getfName());

			String loginUrl = url + "?access_code=" + pDTO.getAccessCode() + "&auto_login=true";
			msg = msg.replaceAll("\\$\\{participant.loginurl}", loginUrl);
			eSubject = eSubject.replaceAll("\\$\\{participant.loginurl}", loginUrl);

			msg = msg.replaceAll("\\\\n", "\n");
			eSubject = eSubject.replaceAll("\\\\n", "\n");
			message = message.replaceAll("\\\\n", "\n");
			emailSubject = emailSubject.replaceAll("\\\\n", "\n");
			pDTO.setMessage(msg);
			pDTO.setEmailSubject(eSubject);
			pDTO.setUrl(url);
			pDTO.setOrigMessage(message);
			pDTO.setOrigEmailSubject(emailSubject);
			pDTO.setParticipantEmailAccount(pDTO.getEmailAccount());


		} else {
			pDTO = buildNewPDTO();
            message = message.replaceAll("\\\\n", "\n");
            emailSubject = emailSubject.replaceAll("\\\\n", "\n");
            pDTO.setOrigMessage(message);
            pDTO.setOrigEmailSubject(emailSubject);
		}

		
		
		for (String email : listOfEmails) {

			if (email.trim().equals("${participant}") || participateStatus.equals("enabled")) {
			    if (email.trim().equals("${participant}")){ 
				pDTO.setEmailAccount(pDTO.getParticipantEmailAccount());
			    pDTO.setEncryptedEmailAccount(Boolean.TRUE);
			    }else{
				pDTO.setEmailAccount(email.trim());
				pDTO.setPhone(null);
			    pDTO.setEncryptedEmailAccount(Boolean.FALSE);
			    }
				// Send Email thru Mandrill Mail Server
				try {
					participantPortalRegistrar.sendEmailThruMandrillViaOcui(pDTO,hostname);
				} catch (Exception e) {
					e.getStackTrace();
				}
				System.out.println(pDTO.getMessage() + "   (Email Send to Participant from Mandrill :  " + pDTO.getEmailAccount() + ")");

			} else {
				pDTO.setEmailAccount(email.trim());
			//	System.out.println();
				// Send Email thru Local Mail Server
				execute(ExecutionMode.SAVE, ruleActionBean, pDTO , email.trim());
				System.out.println(pDTO.getMessage() + "  (Email sent to Hard Coded email address from OC Mail Server :  " + pDTO.getEmailAccount() + ")");

			}
		}
	}

	public ParticipantDTO buildNewPDTO() {
		pDTO = new ParticipantDTO();
		String msg = null;
		msg = message.replaceAll("\\$\\{participant.accessCode}", "");
		msg = msg.replaceAll("\\$\\{participant.firstname}", "");
		msg = msg.replaceAll("\\$\\{participant.loginurl}", "");
		msg = msg.replaceAll("\\\\n", "\n");
		pDTO.setMessage(msg);
		String eSubject = null;
		eSubject = emailSubject.replaceAll("\\$\\{participant.accessCode}", "");
		eSubject = eSubject.replaceAll("\\$\\{participant.firstname}", "");
		eSubject = eSubject.replaceAll("\\$\\{participant.loginurl}", "");
		eSubject = eSubject.replaceAll("\\\\n", "\n");
		pDTO.setEmailSubject(eSubject);

		return pDTO;
	}

	public ParticipantDTO getParticipantInfo(UserAccountBean uBean) {
		ParticipantDTO pDTO = null;
		if (uBean != null && uBean.isActive()) {
			if (uBean.getEmail() == null)
				return null;
			pDTO = new ParticipantDTO();
			pDTO.setAccessCode(uBean.getAccessCode());
			pDTO.setfName(uBean.getFirstName());
			pDTO.setEmailAccount(uBean.getEmail());
			pDTO.setPhone(uBean.getPhone());
		} else {
			return null;
		}

		return pDTO;
	}

	public ArrayList<StudySubjectBean> getAllParticipantStudySubjectsPerStudy(int studyId, DataSource ds) {
		StudySubjectDAO ssdao = new StudySubjectDAO(ds);
		ArrayList<StudySubjectBean> ssBeans = ssdao.findAllByStudyId(studyId);
		return ssBeans;
	}

	public StudyEventBean getStudyEvent(StudySubjectBean ssBean, DataSource ds) {
		StudyEventDAO studyEventDao = new StudyEventDAO(ds);
		StudyEventBean seBean = (StudyEventBean) studyEventDao.getNextScheduledEvent(ssBean.getOid());
		return seBean;
	}

	private StudyBean getParentStudy(DataSource ds, StudyBean study) {
		StudyDAO sdao = new StudyDAO(ds);
		if (study.getParentStudyId() == 0) {
			return study;
		} else {
			StudyBean parentStudy = (StudyBean) sdao.findByPK(study.getParentStudyId());
			return parentStudy;
		}

	}

	public StudyEventDefinitionBean getStudyEventDefnBean(int sed_Id) {
		StudyEventDefinitionDAO sedao = new StudyEventDefinitionDAO(ds);
		return (StudyEventDefinitionBean) sedao.findByPK(sed_Id);
	};

	public StudyBean getStudyBean(int studyId) {
		StudyDAO sdao = new StudyDAO(ds);
		return (StudyBean) sdao.findByPK(studyId);

	}

	public RuleSetService getRuleSetService() {
		return ruleSetService;
	}

	public RuleSetDao getRuleSetDao() {
		return ruleSetDao;
	}

}
