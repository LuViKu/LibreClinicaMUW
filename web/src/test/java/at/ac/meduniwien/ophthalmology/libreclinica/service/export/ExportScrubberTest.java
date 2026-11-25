/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an image file carries out of the platform once the export has scrubbed
 * it: the DICOM Basic Application Level Confidentiality Profile, metadata
 * stripped from JPEG and PNG, and nothing at all of a file it cannot scrub.
 */
class ExportScrubberTest {

    @TempDir
    Path dir;

    private static String text(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
    }

    @Test
    void aDicomLeavesUnderTheBasicProfileWithTheStudyLabelAsItsPatient() throws Exception {
        Path source = dir.resolve("capture.dcm");
        TestImages.writeDicom(source, null);

        ExportScrubber.Scrubbed s = ExportScrubber.scrub(source, ExportScrubber.Kind.DICOM, "HAE-050");
        try {
            assertEquals(ExportScrubber.DICOM_BASIC_PROFILE, s.method());
            String raw = text(s.file());
            for (String gone : new String[] {"Doe", "MRN-4711", "OTHER-MRN-0815", TestImages.SECRET,
                    "Eye Clinic", "SN-123456", "CLARUS-01", "19700101", "056Y"}) {
                assertFalse(raw.contains(gone), "'" + gone + "' left the platform");
            }

            Attributes ds;
            try (DicomInputStream in = new DicomInputStream(s.file().toFile())) {
                assertNotNull(in.readFileMetaInformation(), "a Part 10 file with its meta information");
                ds = in.readDataset();
            }
            assertEquals("HAE-050", ds.getString(Tag.PatientName));
            assertEquals("HAE-050", ds.getString(Tag.PatientID));
            assertNull(ds.getString(Tag.PatientBirthDate));
            assertNull(ds.getString(Tag.PatientSex));
            assertEquals("YES", ds.getString(Tag.PatientIdentityRemoved));
            assertTrue(ds.contains(Tag.DeidentificationMethodCodeSequence), "the method is recorded");
            assertFalse(ds.contains(0x00991001), "private attributes are removed");
            assertNotEquals(TestImages.STUDY_UID, ds.getString(Tag.StudyInstanceUID), "UIDs are remapped");
            assertArrayEquals(new byte[] {1, 2, 3, 4}, ds.getBytes(Tag.PixelData), "the pixels are untouched");
            assertNull(ds.getString(Tag.PatientAge), "age goes with the birth date");

            // What an analysis needs stays: dates (the longitudinal option),
            // geometry and laterality, also inside functional groups.
            assertEquals("20261124", ds.getString(Tag.StudyDate));
            assertEquals("20261124", ds.getString(Tag.AcquisitionDate));
            assertEquals("101500", ds.getString(Tag.AcquisitionTime));
            assertEquals("20261124", ds.getString(Tag.ContentDate));
            assertEquals("UNMODIFIED", ds.getString(Tag.LongitudinalTemporalInformationModified));
            assertArrayEquals(new String[] {"0.0115", "0.0039"}, ds.getStrings(Tag.PixelSpacing));
            assertEquals("R", ds.getString(Tag.Laterality));
            assertEquals("R", ds.getString(Tag.ImageLaterality));
            assertEquals(45f, ds.getFloat(Tag.HorizontalFieldOfView, 0f));
            Attributes shared = ds.getNestedDataset(Tag.SharedFunctionalGroupsSequence);
            assertArrayEquals(new String[] {"0.0115", "0.0039"},
                    shared.getNestedDataset(Tag.PixelMeasuresSequence).getStrings(Tag.PixelSpacing));
            assertEquals("0.12", shared.getNestedDataset(Tag.PixelMeasuresSequence).getString(Tag.SliceThickness));
            assertEquals("R", shared.getNestedDataset(Tag.FrameAnatomySequence).getString(Tag.FrameLaterality));

            // The same original UID maps to the same replacement every time, so
            // the files of one study still belong together after export.
            ExportScrubber.Scrubbed again = ExportScrubber.scrub(source, ExportScrubber.Kind.DICOM, "HAE-050");
            try (DicomInputStream in = new DicomInputStream(again.file().toFile())) {
                in.readFileMetaInformation();
                assertEquals(ds.getString(Tag.StudyInstanceUID), in.readDataset().getString(Tag.StudyInstanceUID));
            } finally {
                Files.deleteIfExists(again.file());
            }
        } finally {
            Files.deleteIfExists(s.file());
        }
        assertTrue(text(source).contains("Doe"), "the stored file is never touched");
    }

    @Test
    void aDicomWithBurnedInAnnotationIsWithheld() throws Exception {
        Path source = dir.resolve("burned.dcm");
        TestImages.writeDicom(source, "YES");
        ExportScrubber.Withheld w = assertThrows(ExportScrubber.Withheld.class,
                () -> ExportScrubber.scrub(source, ExportScrubber.Kind.DICOM, "HAE-050"));
        assertTrue(w.reason().contains("burned-in"), w.reason());
    }

    @Test
    void aJpegLeavesWithoutItsMetadataAndStillDecodes() throws Exception {
        Path source = dir.resolve("fundus.jpg");
        Files.write(source, TestImages.jpegWithExif());
        assertTrue(text(source).contains(TestImages.SECRET), "fixture assumption");

        ExportScrubber.Scrubbed s = ExportScrubber.scrub(source, ExportScrubber.Kind.JPEG, "HAE-050");
        try {
            assertEquals(ExportScrubber.METADATA_STRIPPED, s.method());
            byte[] out = Files.readAllBytes(s.file());
            assertFalse(new String(out, StandardCharsets.ISO_8859_1).contains(TestImages.SECRET));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
            assertNotNull(img, "the image itself is intact");
            assertEquals(8, img.getWidth());
        } finally {
            Files.deleteIfExists(s.file());
        }
    }

    @Test
    void aPngLeavesWithoutItsTextAndStillDecodes() throws Exception {
        Path source = dir.resolve("mask.png");
        Files.write(source, TestImages.pngWithText());

        ExportScrubber.Scrubbed s = ExportScrubber.scrub(source, ExportScrubber.Kind.PNG, "HAE-050");
        try {
            byte[] out = Files.readAllBytes(s.file());
            assertFalse(new String(out, StandardCharsets.ISO_8859_1).contains(TestImages.SECRET));
            assertNotNull(ImageIO.read(new ByteArrayInputStream(out)), "the image itself is intact");
        } finally {
            Files.deleteIfExists(s.file());
        }
    }

    @Test
    void aFileThatCannotBeScrubbedDoesNotLeave() throws Exception {
        Path e2e = dir.resolve("volume.e2e");
        Files.write(e2e, new byte[64]);
        assertThrows(ExportScrubber.Withheld.class, () -> ExportScrubber.scrub(
                e2e, ExportScrubber.kindOf("e2e", e2e.toString()), "HAE-050"));

        // Named a JPEG, but it is not one: not passed on as it is.
        Path fake = dir.resolve("fake.jpg");
        Files.write(fake, "not an image".getBytes(StandardCharsets.US_ASCII));
        assertThrows(ExportScrubber.Withheld.class,
                () -> ExportScrubber.scrub(fake, ExportScrubber.Kind.JPEG, "HAE-050"));

        // A DICOM recorded as an image would skip the profile: refused.
        Path dicom = dir.resolve("capture.dcm");
        TestImages.writeDicom(dicom, null);
        assertThrows(ExportScrubber.Withheld.class,
                () -> ExportScrubber.scrub(dicom, ExportScrubber.Kind.JPEG, "HAE-050"));
    }

    @Test
    void theCasebookIsToldWhatTheScrubWillCarry() throws Exception {
        Path jpeg = dir.resolve("a.jpg");
        Files.write(jpeg, TestImages.jpegWithExif());
        assertTrue(ExportScrubber.wouldCarry(jpeg, ExportScrubber.Kind.JPEG));

        Path fake = dir.resolve("b.jpg");
        Files.write(fake, "not an image".getBytes(StandardCharsets.US_ASCII));
        assertFalse(ExportScrubber.wouldCarry(fake, ExportScrubber.Kind.JPEG));

        Path dicom = dir.resolve("c.dcm");
        TestImages.writeDicom(dicom, null);
        assertTrue(ExportScrubber.wouldCarry(dicom, ExportScrubber.Kind.DICOM));

        Path burned = dir.resolve("d.dcm");
        TestImages.writeDicom(burned, "YES");
        assertFalse(ExportScrubber.wouldCarry(burned, ExportScrubber.Kind.DICOM));

        assertFalse(ExportScrubber.wouldCarry(jpeg, ExportScrubber.Kind.OTHER));
    }

    @Test
    void theKindIsReadFromTheRecordAndTheName() {
        assertEquals(ExportScrubber.Kind.DICOM, ExportScrubber.kindOf("dicom", "/store/a/b"));
        assertEquals(ExportScrubber.Kind.DICOM, ExportScrubber.kindOf(null, "bscan.dcm"));
        assertEquals(ExportScrubber.Kind.JPEG, ExportScrubber.kindOf("image", "/store/x.JPG"));
        assertEquals(ExportScrubber.Kind.JPEG, ExportScrubber.kindOf("image", "/store/image/uuid"));
        assertEquals(ExportScrubber.Kind.PNG, ExportScrubber.kindOf(null, "fundus.png"));
        assertEquals(ExportScrubber.Kind.OTHER, ExportScrubber.kindOf("e2e", "/store/v.e2e"));
        assertEquals(ExportScrubber.Kind.OTHER, ExportScrubber.kindOf(null, "consent.pdf"));
        assertEquals(ExportScrubber.Kind.OTHER, ExportScrubber.kindOf(null, "geometry.json"));
    }
}
