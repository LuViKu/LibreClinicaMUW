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
 * Browser smoke for the {@code ListEventsForSubjects} (events-for-
 * subjects matrix) page after the jmesa → vanilla-JS migration
 * (Phase B.4 jmesa PR 6b, cohort 4b).
 *
 * <p>The page requires {@code defId} (a study-event-definition id).
 * The Default Study install has no event definitions, so {@code
 * defId=1} resolves to the empty-event-def fallback in the data
 * servlet, which emits just the static columns (label / status /
 * site / gender / event.status / event.startDate / actions = 7).
 * We assert &ge; 6 to tolerate the degenerate case where one of the
 * static columns gets renamed.
 */
public class ListEventsForSubjectsSmokeIT extends SmokeIT {

    @Test
    public void eventsForSubjectsMatrixRendersHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("ListEventsForSubjects?defId=1&module=manage");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#listEventsForSubjectsTable thead tr")));
        wait.until(driver -> {
            WebElement body = driver.findElement(By.id("listEventsForSubjectsBody"));
            String text = body.getText();
            return text != null && !text.contains("Loading");
        });
        wait.until(driver -> driver.findElements(
                By.cssSelector("#listEventsForSubjectsTable thead tr th")).size() >= 6);

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#listEventsForSubjectsTable thead tr th"));
        assertTrue("Expected at least 6 columns; got " + headers.size(),
                headers.size() >= 6);

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#listEventsForSubjectsTable tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
