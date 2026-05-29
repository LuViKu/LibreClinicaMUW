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

import java.util.List;

import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

/**
 * Browser smoke for the {@code /ViewRuleAssignment} page after the
 * jmesa → vanilla-JS migration (Phase B.4 jmesa PR 7a, cohort 5a).
 * 18 fixed columns.
 */
public class ViewRuleAssignmentSmokeIT extends SmokeIT {

    private static final int EXPECTED_COLUMN_COUNT = 18;

    @Test
    public void ruleAssignmentRendersHeadersAndAjaxCompletes() {
        loginAs(
                System.getProperty("smoke.username", DEFAULT_USERNAME),
                System.getProperty("smoke.password", DEFAULT_PASSWORD));

        goTo("ViewRuleAssignment?module=manage");

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("#viewRuleAssignmentTable thead tr")));
        wait.until(driver -> {
            WebElement body = driver.findElement(By.id("viewRuleAssignmentBody"));
            String text = body.getText();
            return text != null && !text.contains("Loading");
        });
        wait.until(driver -> driver.findElements(
                By.cssSelector("#viewRuleAssignmentTable thead tr th")).size()
                == EXPECTED_COLUMN_COUNT);

        List<WebElement> headers = driver.findElements(
                By.cssSelector("#viewRuleAssignmentTable thead tr th"));
        assertEquals(EXPECTED_COLUMN_COUNT, headers.size());

        List<WebElement> rows = driver.findElements(
                By.cssSelector("#viewRuleAssignmentTable tbody tr"));
        assertTrue("Tbody must contain at least one row after AJAX "
                        + "(either a data row or the empty-state row).",
                rows.size() >= 1);
    }
}
