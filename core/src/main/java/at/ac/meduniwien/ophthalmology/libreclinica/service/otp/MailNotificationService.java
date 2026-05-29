/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.otp;

import static java.util.Locale.ENGLISH;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.TimeZone;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.login.UserAccountBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.core.OpenClinicaMailSender;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import at.ac.meduniwien.ophthalmology.libreclinica.domain.managestudy.MailNotificationType;
import at.ac.meduniwien.ophthalmology.libreclinica.exception.MailNotificationException;
import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.google.common.annotations.VisibleForTesting;

/**
 * Service class providing access to E-Mail notification related use cases.
 * 
 * @author jbley
 */
@Component("mailNotificationService")
public class MailNotificationService {
    @Autowired
    private OpenClinicaMailSender mailSender;
	@Autowired
	@Qualifier("dataSource")
	private BasicDataSource dataSource;
    @VisibleForTesting
    StudyDAO studyDao;

	public void setMailSender(OpenClinicaMailSender mailSender) {
		this.mailSender = mailSender;
	}

	/**
	 * Checks whether mail notification is enabled or disabled for study. Returns true if enabled - false otherwise.
	 * 
	 * @param studyId The study unique identifier.
	 */
	public boolean isMailNotificationEnabled(int studyId) {
		StudyBean studyBean = getStudyDao().findByPK(studyId);
        return MailNotificationType.ENABLED.name().equals(studyBean.getMailNotification());
	}

	/**
     * Sends mail notification for successful login attempts.
     * 
     * @param bean Bean with addressee information.
     */
    public void sendSuccessfulLoginMail(UserAccountBean bean) {
        sendMail(bean, LoginResult.SUCCESSFUL_LOGIN);
	}
	
	/**
	 * Sends mail notification for denied login attempts.
	 * 
	 * @param bean Bean with addressee information.
	 */
    public void sendDeniedLoginMail(UserAccountBean bean) {
        sendMail(bean, LoginResult.DENIED_LOGIN);
	}

    private void sendMail(UserAccountBean bean, LoginResult loginResult) {
        StudyBean studyBean = getStudyDao().findByPK(bean.getActiveStudyId());

        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("successfulLoginMail.txt")) {
	        // @formatter:off
	        String message = String.format(IOUtils.toString(inputStream, "UTF-8"), 
	                bean.getFirstName(),
	                bean.getLastName(), 
	                getStudyName(bean.getActiveStudyId()), 
	                new Date(),
	                TimeZone.getDefault().getDisplayName(ENGLISH), 
	                loginResult.textual(),
	                studyBean.getContactEmail());
	        // @formatter:off
	        
	        mailSender.sendEmail(bean.getEmail(), "Login Notification", message, false);
	    } catch (IOException e) {
	        new MailNotificationException(e.getMessage(), e.getCause());
	    }
    }

    private String getStudyName(int studyId) {
        StudyBean studyBean = getStudyDao().findByPK(studyId);
        return studyBean.getName();
    }

	private StudyDAO getStudyDao() {
		studyDao = studyDao != null ? studyDao : new StudyDAO(dataSource);
		return studyDao;
	}
}
