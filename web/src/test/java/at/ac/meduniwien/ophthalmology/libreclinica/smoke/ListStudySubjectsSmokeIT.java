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
 * Browser smoke for the {@code ListStudySubjects} page after the
 * jmesa → vanilla-JS migration (Phase B.4 jmesa PR 4c).
 *
 * <p>Pre-conditions:
 * <ol>
 *   <li>Stack reachable at {@code smoke.base.url} (default
 *       {@code http://localhost:8080/LibreClinica/}).</li>
 *   <li>The smoke account can read study-subjects in the active study
 *       (sysadmin or any role that {@code SubmitDataServlet.mayViewData}
 *       accepts).</li>
 *   <li>A study is selected. A fresh install with the seeded default
 *       study satisfies this.</li>
 * </ol>
 *
 * <p>The column count is study-dependent (7 static + N group-class +
 * M event-def + 1 actions), so the assertion checks a lower bound
 * rather than an exact number. An empty-install study with no
 * group-classes / event-defs still has the 7 static + actions = 8
 * columns.
 */
public class ListStudySubjectsSmokeIT extends SmokeIT {

    @Test
    public void tableRendersDynamicHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("ListStudySubjects");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#findSubjectsTable thead tr")));
        // The "Loading..." placeholder lives in BOTH the thead and
        // tbody until the XHR returns. Wait for the body placeholder
        // to be replaced by either data rows or the i18n'd empty-state
        // row.
        wait.until(driver -> {
            WebElement body = driver.findElement(By.id("findSubjectsTableBody"));
            String text = body.getText();
            return text != null && !text.contains("Loading");
        });
        // And likewise wait for the dynamic header row to have arrived
        // — the placeholder thead has a single <th>, the real one has
        // >= 8.
        wait.until(driver -> {
            List<WebElement> ths = driver.findElements(
                    By.cssSelector("#findSubjectsTable thead tr th"));
            return ths.size() >= 8;
        });

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#findSubjectsTable thead tr th"));
        assertTrue("Expected at least 8 columns (7 static + actions); got " + headers.size(),
                headers.size() >= 8);

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#findSubjectsTable tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
