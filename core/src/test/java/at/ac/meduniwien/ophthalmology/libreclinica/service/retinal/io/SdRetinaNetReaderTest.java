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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class SdRetinaNetReaderTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/retinal/metrics/sdretinanet");

    @Test
    public void reads_layers_and_lesions_from_the_archive() throws IOException {
        SdRetinaNetVolume v = SdRetinaNetReader.read(FIXTURE);
        assertEquals(33, v.nBscans());
        assertEquals(64, v.width());
        assertEquals(80, v.height());
        assertEquals(List.of("ILM", "RNFL-GCL", "GCL-IPL", "IPL-INL", "INL-OPL", "OPL-HFL",
                "OB_ELM", "BMEIS", "IB_RPE", "OB_RPE", "BM", "HL-S"), v.layerNames());
        assertEquals(List.of("Cyst", "SRF", "PED", "SHRM", "Pseudodrusen", "ORT"), v.mainLesions());
        assertEquals(List.of("HRF"), v.overlayLesions());

        // confidence 0 at the two left-most A-scans and the BM gap in B-scan 3
        assertTrue(Float.isNaN(v.boundary(0, 0, 0)));
        assertTrue(Float.isNaN(v.boundary(3, 10, 22)));
        assertTrue(v.boundary(3, 10, 30) > 50);

        // a cyst pixel under an HRF dot keeps both: main id 1 plus the overlay bit
        int both = v.packed(18, 30, 36);
        assertEquals(1, both & v.mainMask());
        assertEquals(SdRetinaNetVolume.overlayBit(0), both & SdRetinaNetVolume.overlayBit(0));
        // SRF only
        assertEquals(2, v.packed(18, 49, 31));
        assertEquals(0, v.packed(0, 0, 0));
    }

    @Test
    public void reads_an_extracted_folder_the_same_way() throws IOException {
        Path dir = Files.createTempDirectory("sdrn");
        try (InputStream in = Files.newInputStream(FIXTURE.resolve(SdRetinaNetReader.ARCHIVE));
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = dir.resolve(e.getName());
                Files.createDirectories(out.getParent());
                Files.write(out, zin.readAllBytes());
            }
        }
        SdRetinaNetVolume fromDir = SdRetinaNetReader.read(dir);
        SdRetinaNetVolume fromZip = SdRetinaNetReader.read(FIXTURE);
        assertEquals(fromZip.nBscans(), fromDir.nBscans());
        assertTrue(java.util.Arrays.equals(fromZip.packedLesions(), fromDir.packedLesions()));
        assertTrue(java.util.Arrays.equals(fromZip.boundaries(), fromDir.boundaries()));
    }

    @Test
    public void a_missing_lesion_file_is_an_error() throws IOException {
        Path dir = Files.createTempDirectory("sdrn");
        try (InputStream in = Files.newInputStream(FIXTURE.resolve(SdRetinaNetReader.ARCHIVE));
             ZipInputStream zin = new ZipInputStream(in);
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(dir.resolve(SdRetinaNetReader.ARCHIVE)))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals("lesions/007.png")) continue;
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(zin.readAllBytes());
                zout.closeEntry();
            }
        }
        try {
            SdRetinaNetReader.read(dir);
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("incomplete"));
        }
    }
}
