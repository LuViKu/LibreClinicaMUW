/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).

 * For details see: https://libreclinica.org/license
 * copyright (C) 2020 - 2024 LibreClinica
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.otp;

import static at.ac.meduniwien.ophthalmology.libreclinica.domain.managestudy.MailNotificationType.DISABLED;
import static at.ac.meduniwien.ophthalmology.libreclinica.domain.managestudy.MailNotificationType.ENABLED;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyBean;
import at.ac.meduniwien.ophthalmology.libreclinica.dao.managestudy.StudyDAO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * JUnit test verifying some {@link MailNotificationService} class
 * functionality.
 * 
 * @author thillger
 */
@RunWith(MockitoJUnitRunner.class)
public class MailNotificationServiceTest {
    private MailNotificationService service;
    @Mock
    private StudyDAO studyDao;
    private StudyBean study;

    @Before
    public void setUp() throws Exception {
        service = new MailNotificationService();
        service.studyDao = studyDao;

        study = new StudyBean();
    }

    @Test
    public void testIsMailNotificationEnabled_IsEnabled() {
        study.setMailNotification(ENABLED.name());

        when(studyDao.findByPK(1)).thenReturn(study);
        
        assertThat(service.isMailNotificationEnabled(1), is(true));
    }

    @Test
    public void testIsMailNotificationEnabled_IsDisabled() {
        study.setMailNotification(DISABLED.name());

        when(studyDao.findByPK(1)).thenReturn(study);

        assertThat(service.isMailNotificationEnabled(1), is(false));
    }

    @Test
    public void testIsMailNotificationEnabled_IsDisabled_WhenNullValue() {
        study.setMailNotification(null);

        when(studyDao.findByPK(1)).thenReturn(study);

        assertThat(service.isMailNotificationEnabled(1), is(false));
    }
}
