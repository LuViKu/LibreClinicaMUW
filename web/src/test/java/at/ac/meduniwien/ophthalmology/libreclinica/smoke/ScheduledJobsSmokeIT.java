/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * Browser smoke for the {@code /pages/listCurrentScheduledJobs} page
 * after the jmesa → vanilla-JS migration (Phase B.4 jmesa PR 7c,
 * cohort 5c). 5 fixed columns; empty install will return zero jobs.
 */
public class ScheduledJobsSmokeIT extends SmokeIT {

    private static final int EXPECTED_COLUMN_COUNT = 5;

    @Test
    public void scheduledJobsRendersHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("pages/listCurrentScheduledJobs");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#scheduledJobsTable thead tr")));
        wait.until(driver -> {
            WebElement body = driver.findElement(By.id("scheduledJobsBody"));
            String text = body.getText();
            return text != null && !text.contains("Loading");
        });
        wait.until(driver -> driver.findElements(
                By.cssSelector("#scheduledJobsTable thead tr th")).size()
                == EXPECTED_COLUMN_COUNT);

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#scheduledJobsTable thead tr th"));
        assertEquals(EXPECTED_COLUMN_COUNT, headers.size());

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#scheduledJobsTable tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
