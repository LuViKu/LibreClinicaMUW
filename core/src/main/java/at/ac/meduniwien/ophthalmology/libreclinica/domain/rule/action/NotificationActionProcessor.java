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
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.ItemDataBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.EmailEngine;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.hibernate.RuleSetDao;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyEventDefinitionDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudySubjectDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetBean;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.RuleSetRuleBean;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.ExecutionMode;
import at.ac.meduniwien.ophthalmology.libreclinica.logic.rulerunner.RuleRunner.RuleRunnerMode;
import at.ac.meduniwien.ophthalmology.libreclinica.service.BulkEmailSenderService;
import at.ac.meduniwien.ophthalmology.libreclinica.service.rule.RuleSetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;

@SuppressWarnings("all")

public class NotificationActionProcessor implements ActionProcessor, Runnable {

	protected final Logger logger = LoggerFactory.getLogger(getClass().getName());
	DataSource ds;
	EmailEngine emailEngine;
	JavaMailSenderImpl mailSender;
	RuleSetRuleBean ruleSetRule;
	RuleSetService ruleSetService;
	RuleSetDao ruleSetDao;
	ParticipantDTO pDTO;
	RuleActionBean ruleActionBean;
	String email;
	String[] listOfEmails;
	StudySubjectBean ssBean;
	String message;
	String url;
	String emailSubject;

	public NotificationActionProcessor(String[] listOfEmails, String message, String emailSubject, JavaMailSenderImpl mailSender) {
		this.listOfEmails = listOfEmails;
		this.message = message;
		this.emailSubject = emailSubject;
		this.mailSender = mailSender;
	}

	public NotificationActionProcessor(DataSource ds, JavaMailSenderImpl mailSender, RuleSetRuleBean ruleSetRule) {
		this.ds = ds;
		this.mailSender = mailSender;
		this.ruleSetRule = ruleSetRule;
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
            @Override
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

		String[] listOfEmails = emailList.split(",");

		Thread thread = new Thread(new NotificationActionProcessor(listOfEmails, message, emailSubject, mailSender));
		thread.start();

	}

	@Override
	public void run() {
		// The participant placeholders and the "${participant}" recipient
		// belonged to OpenClinica's hosted participant portal, which this
		// build does not include: the placeholders resolve to nothing and a
		// "${participant}" recipient is skipped. Every other recipient gets
		// the message through the local mail server.
		message = message.replaceAll("\\$\\{participant.url}", "");
		emailSubject = emailSubject.replaceAll("\\$\\{participant.url}", "");

		pDTO = buildNewPDTO();
		message = message.replaceAll("\\\\n", "\n");
		emailSubject = emailSubject.replaceAll("\\\\n", "\n");
		pDTO.setOrigMessage(message);
		pDTO.setOrigEmailSubject(emailSubject);

		for (String email : listOfEmails) {
			if (email.trim().equals("${participant}")) {
				continue;
			}
			pDTO.setEmailAccount(email.trim());
			// Send Email thru Local Mail Server
			execute(ExecutionMode.SAVE, ruleActionBean, pDTO , email.trim());
			logger.info("Queued rule notification email for the local mail server: account={}", pDTO.getEmailAccount());
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
