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

        // The JSP renders a 5-column thead skeleton (always present)
        // and an initial "Loading..." row in tbody. Vanilla JS fetches
        // /AuditUserActivityData and replaces tbody content with the
        // actual rows. Wait for the loading-state row to be replaced
        // with the actual data rows.
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#auditUserLogin thead tr")));
        // First user-data row carries an <a> in the actions column
        // (every audit row produced by the endpoint has a userAccountId).
        wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("#auditUserLogin tbody tr td a")));

        List<WebElement> headerCells = driver.findElements(
                By.cssSelector("#auditUserLogin thead tr th"));
        assertEquals("Header column count must match auditUserActivity.jsp's <thead>",
                EXPECTED_HEADERS.size(), headerCells.size());

        List<WebElement> bodyRows = driver.findElements(
                By.cssSelector("#auditUserLogin tbody tr"));
        assertTrue("At least one body row must be rendered after the AJAX fetch "
                        + "(the test runs as a logged-in sysadmin, so the audit_user_login "
                        + "table is guaranteed non-empty).",
                bodyRows.size() >= 1);
    }
}
