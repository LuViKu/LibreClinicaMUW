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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

// Fixtures under src/test/resources/retinal/io/ were generated with:
//   import numpy as np
//   np.save("npy_u1_3d.npy", np.array([[[1..12]],[[13..24]]], dtype=np.uint8))   # shape (2,3,4)
//   np.save("npy_fortran.npy", np.asfortranarray(np.array([[1,2],[3,4]], dtype=np.uint8)))
//   np.save("npy_f4.npy", np.array([1.5, 2.5], dtype=np.float32))
public class NpyReaderTest {

    private Path fixture(String name) {
        return Paths.get("src/test/resources/retinal/io/" + name);
    }

    @Test
    public void read_u1_3d() throws Exception {
        LabelVolume v = NpyReader.read(fixture("npy_u1_3d.npy"));
        assertEquals(2, v.dimZ());
        assertEquals(3, v.dimY());
        assertEquals(4, v.dimX());
        assertEquals(1, v.at(0, 0, 0));
        assertEquals(12, v.at(0, 2, 3));
        assertEquals(13, v.at(1, 0, 0));
        assertEquals(24, v.at(1, 2, 3));
    }

    @Test
    public void reject_fortran_order() {
        try {
            NpyReader.read(fixture("npy_fortran.npy"));
            fail("expected IOException for fortran_order=True");
        } catch (IOException ex) {
            assertTrue("expected 'fortran' in message: " + ex.getMessage(),
                    ex.getMessage().toLowerCase().contains("fortran"));
        }
    }

    @Test
    public void reject_unsupported_dtype() {
        try {
            NpyReader.read(fixture("npy_f4.npy"));
            fail("expected IOException for float32");
        } catch (IOException ex) {
            assertTrue("expected '<f4' in message: " + ex.getMessage(),
                    ex.getMessage().contains("<f4"));
        }
    }
}
