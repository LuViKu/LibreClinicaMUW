/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.export;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;

/**
 * Real image files carrying identifying data where devices put it, for the
 * export's scrubbing tests: a JPEG with an EXIF segment, a PNG with a text
 * chunk, a DICOM with identity in its header, a nested sequence and a private
 * tag.
 */
public final class TestImages {

    /** What every fixture hides somewhere it must not leave. */
    public static final String SECRET = "Jane Doe 1970-01-01";

    /** The fixture DICOM's original Study Instance UID. */
    public static final String STUDY_UID = "1.2.826.0.1.3680043.2.1125.99.1";

    private TestImages() {}

    /** A JPEG whose first segment after SOI is an EXIF APP1 naming {@link #SECRET}. */
    public static byte[] jpegWithExif() throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", plain);
        byte[] img = plain.toByteArray();
        byte[] exif = ("Exif\0\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(img, 0, 2); // SOI
        out.write(0xff);
        out.write(0xe1);
        int len = exif.length + 2;
        out.write(len >> 8);
        out.write(len & 0xff);
        out.write(exif);
        out.write(0xff); // a comment segment as well
        out.write(0xfe);
        byte[] com = SECRET.getBytes(StandardCharsets.ISO_8859_1);
        out.write((com.length + 2) >> 8);
        out.write((com.length + 2) & 0xff);
        out.write(com);
        out.write(img, 2, img.length - 2);
        return out.toByteArray();
    }

    /** A PNG with a tEXt chunk naming {@link #SECRET} after IHDR. */
    public static byte[] pngWithText() throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY), "png", plain);
        byte[] img = plain.toByteArray();
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        byte[] data = ("Author\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(img, 0, afterIhdr);
        writeInt(out, data.length);
        byte[] type = "tEXt".getBytes(StandardCharsets.ISO_8859_1);
        out.write(type);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
        out.write(img, afterIhdr, img.length - afterIhdr);
        return out.toByteArray();
    }

    /**
     * A small secondary-capture DICOM with identity in the top level, a nested
     * sequence and a private tag, and dates, institution and device.
     *
     * @param burnedIn the value of Burned In Annotation, or null for none
     */
    public static void writeDicom(Path file, String burnedIn) throws IOException {
        Attributes ds = new Attributes();
        ds.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
        ds.setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1125.99.3");
        ds.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
        ds.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.1.3680043.2.1125.99.2");
        ds.setString(Tag.PatientName, VR.PN, "Doe^Jane");
        ds.setString(Tag.PatientID, VR.LO, "MRN-4711");
        ds.setString(Tag.PatientBirthDate, VR.DA, "19700101");
        ds.setString(Tag.PatientSex, VR.CS, "F");
        ds.setString(Tag.StudyDate, VR.DA, "20261124");
        ds.setString(Tag.AcquisitionDate, VR.DA, "20261124");
        ds.setString(Tag.AcquisitionTime, VR.TM, "101500");
        ds.setString(Tag.ContentDate, VR.DA, "20261124");
        ds.setString(Tag.PatientAge, VR.AS, "056Y");
        // What an analysis of the image needs, top level and in functional groups.
        ds.setString(Tag.PixelSpacing, VR.DS, "0.0115", "0.0039");
        ds.setString(Tag.Laterality, VR.CS, "R");
        ds.setString(Tag.ImageLaterality, VR.CS, "R");
        ds.setString(Tag.HorizontalFieldOfView, VR.FL, "45");
        Attributes measures = new Attributes();
        measures.setString(Tag.PixelSpacing, VR.DS, "0.0115", "0.0039");
        measures.setString(Tag.SliceThickness, VR.DS, "0.12");
        Attributes anatomy = new Attributes();
        anatomy.setString(Tag.FrameLaterality, VR.CS, "R");
        Attributes shared = new Attributes();
        shared.newSequence(Tag.PixelMeasuresSequence, 1).add(measures);
        shared.newSequence(Tag.FrameAnatomySequence, 1).add(anatomy);
        ds.newSequence(Tag.SharedFunctionalGroupsSequence, 1).add(shared);
        ds.setString(Tag.InstitutionName, VR.LO, "Eye Clinic Vienna");
        ds.setString(Tag.DeviceSerialNumber, VR.LO, "SN-123456");
        ds.setString(Tag.StationName, VR.SH, "CLARUS-01");
        ds.setString(Tag.Modality, VR.CS, "OP");
        if (burnedIn != null) ds.setString(Tag.BurnedInAnnotation, VR.CS, burnedIn);
        Sequence other = ds.newSequence(Tag.OtherPatientIDsSequence, 1);
        Attributes item = new Attributes();
        item.setString(Tag.PatientID, VR.LO, "OTHER-MRN-0815");
        other.add(item);
        ds.setString(0x00990010, VR.LO, "VENDOR");
        ds.setString(0x00991001, VR.LO, SECRET);
        ds.setInt(Tag.Rows, VR.US, 2);
        ds.setInt(Tag.Columns, VR.US, 2);
        ds.setInt(Tag.SamplesPerPixel, VR.US, 1);
        ds.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
        ds.setInt(Tag.BitsAllocated, VR.US, 8);
        ds.setInt(Tag.BitsStored, VR.US, 8);
        ds.setInt(Tag.HighBit, VR.US, 7);
        ds.setInt(Tag.PixelRepresentation, VR.US, 0);
        ds.setBytes(Tag.PixelData, VR.OB, new byte[] {1, 2, 3, 4});
        Attributes fmi = ds.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        try (DicomOutputStream dos = new DicomOutputStream(file.toFile())) {
            dos.writeDataset(fmi, ds);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v >>> 24);
        out.write(v >>> 16);
        out.write(v >>> 8);
        out.write(v);
    }
}
