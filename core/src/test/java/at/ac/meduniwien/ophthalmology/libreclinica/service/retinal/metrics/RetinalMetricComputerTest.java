/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.metrics;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Paths;

import org.junit.Test;

public class RetinalMetricComputerTest {

    @Test
    public void unknown_task_throws_illegal_argument() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        try {
            computer.compute("unknown",
                    Paths.get("src/test/resources/retinal/metrics/fluid"),
                    null, "OD");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            assertTrue("error message should name the unknown task: " + ex.getMessage(),
                    ex.getMessage().contains("unknown"));
        }
    }

    @Test
    public void null_task_throws_illegal_argument() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        try {
            computer.compute(null,
                    Paths.get("src/test/resources/retinal/metrics/fluid"),
                    null, "OD");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // OK
        }
    }

    @Test
    public void null_segdir_throws_illegal_argument() {
        RetinalMetricComputer computer = new RetinalMetricComputer();
        try {
            computer.compute("fluid", null, null, "OD");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            // OK
        }
    }
}
