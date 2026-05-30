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
 * Browser smoke for the {@code ViewNotes} ("Notes & Discrepancies")
 * page after the jmesa → vanilla-JS migration (Phase B.4 jmesa PR 5a,
 * cohort 3a).
 *
 * <p>Pre-conditions:
 * <ol>
 *   <li>Stack reachable at {@code smoke.base.url}.</li>
 *   <li>The smoke account can view discrepancy notes in the active
 *       study (sysadmin or roles {@code SubmitDataServlet.mayViewData}
 *       accepts).</li>
 * </ol>
 *
 * <p>An empty install will return zero notes — that's still a valid
 * AJAX completion (placeholder "Loading..." is replaced by an
 * i18n empty-state row). We only assert that the placeholder is
 * gone after the request resolves and that the static column
 * count matches the {@link
 * at.ac.meduniwien.ophthalmology.libreclinica.control.managestudy.ViewNotesDataServlet}
 * configuration.
 */
public class ViewNotesSmokeIT extends SmokeIT {

    /** 21 static columns + actions = 21 (actions is column 21). */
    private static final int EXPECTED_COLUMN_COUNT = 21;

    @Test
    public void notesTableRendersHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("ViewNotes?module=manage");

        // Static thead placeholder is replaced by the real header row
        // once the AJAX response arrives.
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#viewNotesTable thead tr")));
        wait.until(driver -> {
            WebElement body = driver.findElement(By.id("viewNotesTableBody"));
            String text = body.getText();
            return text != null && !text.contains("Loading");
        });
        wait.until(driver -> driver.findElements(
                By.cssSelector("#viewNotesTable thead tr th")).size()
                == EXPECTED_COLUMN_COUNT);

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#viewNotesTable thead tr th"));
        assertTrue("Expected " + EXPECTED_COLUMN_COUNT + " columns; got " + headers.size(),
                headers.size() == EXPECTED_COLUMN_COUNT);

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#viewNotesTable tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
