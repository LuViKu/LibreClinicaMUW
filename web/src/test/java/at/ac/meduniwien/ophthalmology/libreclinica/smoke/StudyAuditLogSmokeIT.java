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
 * Browser smoke for the {@code StudyAuditLog} (View Study Log) page
 * after the jmesa → vanilla-JS migration (Phase B.4 jmesa PR 6a,
 * cohort 4a).
 *
 * <p>Pure 1-to-1 row layout, 8 static columns. Smoke account must
 * be sysadmin or study-director/coordinator/monitor.
 */
public class StudyAuditLogSmokeIT extends SmokeIT {

    private static final int EXPECTED_COLUMN_COUNT = 8;

    @Test
    public void auditLogRendersHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("StudyAuditLog");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#studyAuditLogTable thead tr")));
        wait.until(driver -> {
            WebElement body = driver.findElement(By.id("studyAuditLogBody"));
            String text = body.getText();
            return text != null && !text.contains("Loading");
        });
        wait.until(driver -> driver.findElements(
                By.cssSelector("#studyAuditLogTable thead tr th")).size()
                == EXPECTED_COLUMN_COUNT);

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#studyAuditLogTable thead tr th"));
        assertEquals("Expected " + EXPECTED_COLUMN_COUNT + " columns",
                EXPECTED_COLUMN_COUNT, headers.size());

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#studyAuditLogTable tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
