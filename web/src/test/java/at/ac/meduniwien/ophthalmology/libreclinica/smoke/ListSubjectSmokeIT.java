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

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * Browser smoke for the admin "List Subjects" page after the jmesa →
 * vanilla-JS migration (Phase B.4 jmesa PR 4b). Same shape as
 * {@link AuditUserActivitySmokeIT}: load page, wait for AJAX-rendered
 * rows, assert column count + body row count.
 *
 * <p>Pre-conditions for this test to pass:
 * <ol>
 *   <li>The LibreClinica stack is reachable at {@code smoke.base.url}
 *       (default {@code http://localhost:8080/LibreClinica/}).</li>
 *   <li>The smoke account is a sysadmin (the {@code ListSubjectServlet}
 *       check requires {@code ub.isSysAdmin()}).</li>
 *   <li>At least one subject row exists in the current study. A clean
 *       install satisfies this — the install seed populates the
 *       Default Study with a sample subject.</li>
 * </ol>
 */
public class ListSubjectSmokeIT extends SmokeIT {

    /** Expected column headers, in order. */
    private static final List<String> EXPECTED_HEADERS = Arrays.asList(
            "person_ID", "Protocol_Study_subject_IDs", "gender",
            "date_created", "owner", "date_updated", "last_updated_by",
            "status", "actions");

    @Test
    public void tableRendersHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("ListSubject");

        // Static thead is present immediately. tbody starts with a
        // "Loading..." placeholder row that the AJAX response replaces
        // with EITHER the real subject rows OR an i18n'd "no data"
        // empty-state row. Both signal AJAX completion; we accept
        // either as success.
        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#listSubjects thead tr")));
        wait.until(driver -> {
            WebElement tbody = driver.findElement(By.id("listSubjectsBody"));
            String text = tbody.getText();
            return text != null && !text.contains("Loading");
        });

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#listSubjects thead tr th"));
        assertEquals("Header column count must match listSubject.jsp",
                EXPECTED_HEADERS.size(), headers.size());

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#listSubjects tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
