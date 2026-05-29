/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.smoke;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * Browser smoke for the {@code ListDiscNotesForCRFServlet} ("View all
 * Discrepancy Notes" — per-event-CRF matrix) page after the jmesa →
 * vanilla-JS migration (Phase B.4 jmesa PR 5c, cohort 3c).
 *
 * <p>Pre-conditions:
 * <ol>
 *   <li>Stack reachable at {@code smoke.base.url}.</li>
 *   <li>The smoke account can view discrepancy notes in the active
 *       study (sysadmin or {@code SubmitDataServlet.mayViewData}
 *       roles).</li>
 *   <li>The active study has at least one study-event-definition with
 *       id 1 (a fresh install satisfies this — `defId=1` is the
 *       legacy default).</li>
 * </ol>
 *
 * <p>Column count is study-dependent (3 static + N CRFs + 1 actions).
 * A fresh install has at least one CRF, so the lower bound is 5
 * columns. We assert &ge; 4 to tolerate a no-CRF degenerate study.
 */
public class ListDiscNotesForCRFSmokeIT extends SmokeIT {

    @Test
    public void notesForCrfMatrixRendersHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("ListDiscNotesForCRFServlet?defId=1&module=manage");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#listDiscNotesForCRFTable thead tr")));
        wait.until(driver -> {
            WebElement body = driver.findElement(By.id("listDiscNotesForCRFBody"));
            String text = body.getText();
            return text != null && !text.contains("Loading");
        });
        wait.until(driver -> driver.findElements(
                By.cssSelector("#listDiscNotesForCRFTable thead tr th")).size() >= 4);

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#listDiscNotesForCRFTable thead tr th"));
        assertTrue("Expected at least 4 columns (3 static + actions); got " + headers.size(),
                headers.size() >= 4);

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#listDiscNotesForCRFTable tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
