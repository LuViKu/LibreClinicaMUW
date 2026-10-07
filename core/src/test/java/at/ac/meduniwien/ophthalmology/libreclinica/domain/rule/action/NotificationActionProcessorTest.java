/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.domain.rule.action;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mail.javamail.MimeMessagePreparator;

import at.ac.meduniwien.ophthalmology.libreclinica.dao.core.CoreResources;
import at.ac.meduniwien.ophthalmology.libreclinica.service.BulkEmailSenderService;

/**
 * The e-mail of a rule's notification action.
 *
 * <p>Before sending, the action asked the participant-portal client for the
 * study's portal address. That client needs a library the WAR does not ship,
 * and it answered nothing when no portal was configured, so the sending
 * thread died and no recipient got the message. The portal is not part of
 * this build: the message goes to every listed address through the local mail
 * server, and the participant placeholders resolve to nothing.
 */
public class NotificationActionProcessorTest {

    private Properties datainfo;
    private Properties saved;

    @Before
    public void setAdminAddress() throws Exception {
        Field f = CoreResources.class.getDeclaredField("DATAINFO");
        f.setAccessible(true);
        datainfo = (Properties) f.get(null);
        if (datainfo == null) {
            datainfo = new Properties();
            f.set(null, datainfo);
        }
        saved = new Properties();
        saved.putAll(datainfo);
        datainfo.setProperty("adminEmail", "admin@example.org");
        queue().clear();
    }

    @After
    public void restore() throws Exception {
        datainfo.clear();
        datainfo.putAll(saved);
        queue().clear();
    }

    @SuppressWarnings("unchecked")
    private static Collection<MimeMessagePreparator> queue() throws Exception {
        Field f = BulkEmailSenderService.class.getDeclaredField("DEQUE");
        f.setAccessible(true);
        return (Collection<MimeMessagePreparator>) f.get(null);
    }

    @Test
    public void theNotificationIsQueuedForTheLocalMailServer() throws Exception {
        NotificationActionProcessor processor = new NotificationActionProcessor(
                new String[] {"dm@example.org", " ${participant}"},
                "Visit ${participant.url}due", "Reminder${participant.firstname}", null);

        processor.run();

        List<MimeMessagePreparator> queued = new ArrayList<>(queue());
        assertEquals(1, queued.size());
        MimeMessage mail = new MimeMessage((Session) null);
        queued.get(0).prepare(mail);
        assertEquals("dm@example.org", mail.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("Reminder", mail.getSubject());
        assertEquals("Visit due", mail.getContent());
    }
}
