/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

/**
 * A file is what its bytes say, not what the browser or the operator called it.
 */
public class FileKindSnifferTest {

    private static byte[] withMagic(byte[] magic, int length) {
        byte[] b = new byte[length];
        System.arraycopy(magic, 0, b, 0, magic.length);
        return b;
    }

    @Test
    public void pngAndJpegAreImages() {
        FileKindSniffer.Sniffed png = FileKindSniffer.sniff(withMagic(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 64));
        assertEquals(IngestArtifactStore.Kind.IMAGE, png.kind());
        assertEquals("image/png", png.contentType());
        assertEquals(".png", png.extension());
        assertEquals("png", png.format());

        FileKindSniffer.Sniffed jpeg = FileKindSniffer.sniff(withMagic(
                new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}, 64));
        assertEquals(IngestArtifactStore.Kind.IMAGE, jpeg.kind());
        assertEquals("image/jpeg", jpeg.contentType());
        assertEquals("jpeg", jpeg.format());
    }

    @Test
    public void aPart10FileIsDicom() {
        byte[] head = new byte[FileKindSniffer.HEAD_BYTES];
        Arrays.fill(head, (byte) 0);
        System.arraycopy("DICM".getBytes(StandardCharsets.US_ASCII), 0, head, 128, 4);
        FileKindSniffer.Sniffed s = FileKindSniffer.sniff(head);
        assertEquals(IngestArtifactStore.Kind.DICOM, s.kind());
        assertEquals("application/dicom", s.contentType());
        assertEquals(".dcm", s.extension());
        assertEquals("dicom", s.format());
    }

    @Test
    public void aSpectralisExportIsE2e() {
        for (String magic : new String[] {"CMDb", "MDbDir", "E2EMultipleVolumeFile"}) {
            FileKindSniffer.Sniffed s = FileKindSniffer.sniff(
                    withMagic(magic.getBytes(StandardCharsets.US_ASCII), 64));
            assertEquals(magic, IngestArtifactStore.Kind.E2E, s.kind());
            assertEquals(".e2e", s.extension());
        }
    }

    @Test
    public void theBrowsersClaimDoesNotCount() {
        // A "JPEG" that is really a PNG is a PNG.
        byte[] png = withMagic(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 64);
        assertEquals("image/png", FileKindSniffer.sniff(png, "photo.jpg").contentType());
        // A ".dcm" that is a text file is nothing.
        assertNull(FileKindSniffer.sniff("hello world".getBytes(StandardCharsets.US_ASCII), "scan.dcm"));
    }

    @Test
    public void anUnknownVendorMagicPassesOnlyWithTheE2eExtension() {
        byte[] head = withMagic("HRDb".getBytes(StandardCharsets.US_ASCII), 64);
        assertNull(FileKindSniffer.sniff(head));
        assertNull(FileKindSniffer.sniff(head, "scan.bin"));
        assertEquals(IngestArtifactStore.Kind.E2E, FileKindSniffer.sniff(head, "scan.E2E").kind());
        // Binary junk under an .e2e name still fails.
        byte[] junk = withMagic(new byte[] {0x01, 0x02, 0x03}, 64);
        assertNull(FileKindSniffer.sniff(junk, "scan.e2e"));
    }

    @Test
    public void shortAndEmptyInputsAreNothing() {
        assertNull(FileKindSniffer.sniff(null));
        assertNull(FileKindSniffer.sniff(new byte[0]));
        assertNull(FileKindSniffer.sniff(new byte[] {(byte) 0xFF}));
        // "DICM" cannot be seen in a file shorter than the preamble.
        assertNull(FileKindSniffer.sniff("DICM".getBytes(StandardCharsets.US_ASCII)));
    }
}
