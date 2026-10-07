/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.bean.extract;

import static org.junit.Assert.assertEquals;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;

import org.junit.Test;

import at.ac.meduniwien.ophthalmology.libreclinica.bean.managestudy.StudyEventBean;
import at.ac.meduniwien.ophthalmology.libreclinica.bean.submit.EventCRFBean;

/**
 * The CRF completion-date column of a dataset export must be empty, not an
 * exception, for an event that has no CRF yet or whose CRF is not complete.
 */
public class ExtractBeanCompletionDateTest {

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

    private static StudyEventBean event(EventCRFBean... crfs) {
        StudyEventBean seb = new StudyEventBean();
        ArrayList<EventCRFBean> list = new ArrayList<>();
        for (EventCRFBean crf : crfs) {
            list.add(crf);
        }
        seb.setEventCRFs(list);
        return seb;
    }

    @Test
    public void anEventWithoutACrfHasNoCompletionDate() {
        assertEquals("", ExtractBean.crfCompletionDate(event(), sdf));
    }

    @Test
    public void anIncompleteCrfHasNoCompletionDate() {
        assertEquals("", ExtractBean.crfCompletionDate(event(new EventCRFBean()), sdf));
    }

    @Test
    public void theValidationDateWinsOverTheFirstCompletion() {
        EventCRFBean crf = new EventCRFBean();
        crf.setDateCompleted(new GregorianCalendar(2026, 0, 5).getTime());
        assertEquals("2026-01-05", ExtractBean.crfCompletionDate(event(crf), sdf));
        crf.setDateValidateCompleted(new GregorianCalendar(2026, 0, 9).getTime());
        assertEquals("2026-01-09", ExtractBean.crfCompletionDate(event(crf), sdf));
    }
}
