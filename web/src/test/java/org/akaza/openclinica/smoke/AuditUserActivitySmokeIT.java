/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package org.akaza.openclinica.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * Browser smoke for the DataTables-backed audit-user-activity page.
 * Retroactively verifies <a href="https://github.com/LuViKu/LibreClinicaMUW/pull/36">PR #36</a>
 * (jmesa cohort 2a), which shipped without browser verification.
 *
 * <p>Pre-conditions for this test to pass:
 * <ol>
 *   <li>The LibreClinica stack is reachable at {@code smoke.base.url}
 *       (default {@code http://localhost:8080/LibreClinica/}).</li>
 *   <li>A sysadmin account exists; the test logs in as
 *       {@code root}/{@code password} by default. Override via
 *       {@code -Dsmoke.username=}, {@code -Dsmoke.password=}.</li>
 *   <li>The DataTables.net 2.x JS+CSS bundle is present at
 *       {@code /includes/js/datatables/} (per the README dropped in
 *       jmesa PR 2). Without the bundle the assertions on rendered
 *       rows fail — that's the expected signal for "operator forgot
 *       to drop the bundle".</li>
 * </ol>
 */
public class AuditUserActivitySmokeIT extends SmokeIT {

    /** The expected column headers, in order, from the JSP DataTables init. */
    private static final List<String> EXPECTED_HEADERS = Arrays.asList(
            "user_name", "attempt_date", "status", "details", "actions");

    @Test
    public void datatableRendersWithExpectedColumnsAndAtLeastOneRow() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("AuditUserActivity");

        // DataTables initialisation runs on DOMContentLoaded and fires
        // a JSON request to /AuditUserActivityData. Wait until the
        // table has thead + at least one <tr> in the body (DataTables
        // renders an empty-state row even on zero data, so a row count
        // ≥ 1 is the right floor).
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#auditUserLogin thead tr")));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("#auditUserLogin tbody tr")));

        List<WebElement> headerCells = driver.findElements(
                By.cssSelector("#auditUserLogin thead tr th"));
        assertEquals("Header column count must match the DataTables init in auditUserActivity.jsp",
                EXPECTED_HEADERS.size(), headerCells.size());

        List<WebElement> bodyRows = driver.findElements(
                By.cssSelector("#auditUserLogin tbody tr"));
        assertTrue("DataTables must render at least one body row "
                        + "(even an empty-state row); none means the AJAX call to "
                        + "/AuditUserActivityData did not deliver JSON.",
                bodyRows.size() >= 1);
    }

    @Test
    public void datatableSearchInputAppearsAndAccepts() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("AuditUserActivity");

        // DataTables injects its own global search <input> next to the
        // table. Locator is brittle to DataTables version changes —
        // currently 2.x uses an input with type=search inside the
        // _filter wrapper. Adjust when DataTables JS major-bumps.
        WebElement search = wait.until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("div.dt-search input[type='search'], div.dataTables_filter input[type='search']")));
        search.sendKeys("zzz-no-match-expected");

        // After a column-search keystroke, DataTables re-issues the
        // AJAX call. We can't reliably assert on filtered row counts
        // without test fixtures, but we can assert the field accepts
        // input and the table is still visible (no JS exception
        // crashed the page).
        assertTrue("Search input must accept typing",
                "zzz-no-match-expected".equals(search.getAttribute("value")));
        assertTrue("Table must remain visible after typing in the search box",
                driver.findElement(By.id("auditUserLogin")).isDisplayed());
    }
}
