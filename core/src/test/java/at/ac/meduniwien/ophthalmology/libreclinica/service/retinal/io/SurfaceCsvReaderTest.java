/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class SurfaceCsvReaderTest {

    private Path fixture(String name) {
        return Paths.get("src/test/resources/retinal/io/" + name);
    }

    @Test
    public void parses_floats_nan_and_U_sentinels() throws Exception {
        SurfaceGrid g = SurfaceCsvReader.read(fixture("surface_small.csv"));

        assertEquals(3, g.nBscans());
        assertEquals(5, g.nAscans());
        assertEquals(3, g.yPerBscan().length);

        double[] r0 = g.yPerBscan()[0];
        assertEquals(1.0, r0[0], 0.0);
        assertEquals(2.5, r0[1], 0.0);
        assertTrue("row0 col2 nan", Double.isNaN(r0[2]));
        assertTrue("row0 col3 U", Double.isNaN(r0[3]));
        assertEquals(3.0, r0[4], 0.0);

        double[] r1 = g.yPerBscan()[1];
        assertTrue("row1 col0 empty", Double.isNaN(r1[0]));
        assertEquals(1.1, r1[1], 0.0);
        assertTrue("row1 col2 NaN", Double.isNaN(r1[2]));
        assertTrue("row1 col3 u", Double.isNaN(r1[3]));
        assertEquals(4.4, r1[4], 0.0);

        double[] r2 = g.yPerBscan()[2];
        assertEquals(0.0, r2[0], 0.0);
        assertEquals(3.5, r2[4], 0.0);
    }

    @Test
    public void rejects_ragged_row_with_row_number_in_message() {
        try {
            SurfaceCsvReader.read(fixture("surface_ragged.csv"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage();
            assertTrue("expected row index in message but got: " + msg,
                    msg.contains("row 1") || msg.contains("row1"));
        } catch (Exception other) {
            fail("expected IllegalArgumentException but got " + other);
        }
    }
}
