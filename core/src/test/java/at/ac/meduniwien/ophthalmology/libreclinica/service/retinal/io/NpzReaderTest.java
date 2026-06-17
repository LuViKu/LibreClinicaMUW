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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.Test;

// Fixtures generated with:
//   import numpy as np
//   np.savez("fluidseg_like.npz", segmentation=np.ones((2,3,4), dtype=np.uint8))
//   np.savez("multi.npz",
//            a=np.ones((2,2), dtype=np.uint8),
//            b=np.full((3,3), 7, dtype=np.uint8))
public class NpzReaderTest {

    private Path fixture(String name) {
        return Paths.get("src/test/resources/retinal/io/" + name);
    }

    @Test
    public void read_named_entry() throws Exception {
        LabelVolume v = NpzReader.read(fixture("fluidseg_like.npz"), "segmentation");
        assertEquals(2, v.dimZ());
        assertEquals(3, v.dimY());
        assertEquals(4, v.dimX());
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 3; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(1, v.at(z, y, x));
                }
            }
        }
    }

    @Test
    public void read_first() throws Exception {
        LabelVolume v = NpzReader.readFirst(fixture("multi.npz"));
        // np.savez ordering preserves kwargs in CPython 3.7+; first kw is 'a'
        // which is a 2x2 array of ones (rank 2 → dimZ=1).
        assertEquals(1, v.dimZ());
        assertEquals(2, v.dimY());
        assertEquals(2, v.dimX());
        assertEquals(1, v.at(0, 0, 0));
        assertEquals(1, v.at(0, 1, 1));
    }

    @Test
    public void read_all() throws Exception {
        Map<String, LabelVolume> all = NpzReader.readAll(fixture("multi.npz"));
        assertEquals(2, all.size());
        assertTrue(all.containsKey("a"));
        assertTrue(all.containsKey("b"));
        LabelVolume a = all.get("a");
        LabelVolume b = all.get("b");
        assertEquals(2, a.dimY());
        assertEquals(2, a.dimX());
        assertEquals(1, a.at(0, 0, 0));
        assertEquals(3, b.dimY());
        assertEquals(3, b.dimX());
        assertEquals(7, b.at(0, 2, 2));
    }
}
