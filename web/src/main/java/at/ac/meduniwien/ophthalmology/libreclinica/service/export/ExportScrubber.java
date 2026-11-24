/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.export;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.deident.DeIdentifier;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;

/**
 * Scrubs an image file before it leaves the platform in an export.
 *
 * <p>The stores keep what arrived: an uploaded DICOM with its patient name and
 * ID replaced but its dates, device, station, UIDs and private tags intact, a
 * C-STORE file as the camera wrote it, a JPEG with whatever metadata the device
 * put in it. None of that leaves in an export:
 *
 * <ul>
 *   <li><b>DICOM</b> — the Basic Application Level Confidentiality Profile
 *       (DICOM PS3.15 Annex E) with the Retain Longitudinal Temporal
 *       Information with Full Dates Option, as dcm4che's {@link DeIdentifier}
 *       implements it. The study, series, acquisition and content dates and
 *       times stay, because a longitudinal study needs them; birth date and age
 *       go. What the analysis of an image needs is not on the profile's list
 *       and is kept as it is: pixel spacing, laterality, slice thickness,
 *       orientation, the ophthalmic acquisition attributes, and pixel
 *       measures and frame laterality inside functional groups. Otherwise:
 *       the profile's attributes removed,
 *       emptied or replaced in every nested sequence, private attributes,
 *       curves and overlays removed, UIDs remapped (the same UID always to the
 *       same replacement, so a study's files still belong together),
 *       {@code PatientIdentityRemoved} and the method recorded. Patient name
 *       and ID take the study label, the dummy value that keeps a file
 *       attributable within the study. A file that declares burned-in
 *       annotation is withheld: the profile does not clean pixel data.</li>
 *   <li><b>JPEG</b> — every metadata segment removed (EXIF, XMP, IPTC,
 *       comments, any APPn but JFIF, ICC profile and Adobe); the image data is
 *       copied unchanged.</li>
 *   <li><b>PNG</b> — text, time and EXIF chunks and any private chunk
 *       removed; the image and its colour chunks are kept.</li>
 *   <li>Anything else — an E2E, a PDF, a file whose type cannot be told — is
 *       withheld. A file that cannot be scrubbed does not leave.</li>
 * </ul>
 *
 * <p>The scrubbed copy is a temporary file the caller copies into the archive
 * and deletes; the stored file is never touched.
 */
public final class ExportScrubber {

    /** How a carried file was treated, as the manifest names it. */
    public static final String DICOM_BASIC_PROFILE = "dicom-basic-profile-retain-full-dates";
    public static final String METADATA_STRIPPED = "metadata-stripped";

    /** What the export can do with a file. */
    public enum Kind { DICOM, JPEG, PNG, OTHER }

    /** A scrubbed copy, and how it was scrubbed. */
    public record Scrubbed(Path file, String method) {}

    /** A file the export does not carry, and why. */
    public static final class Withheld extends Exception {
        private static final long serialVersionUID = 1L;

        Withheld(String reason) {
            super(reason);
        }

        public String reason() {
            return getMessage();
        }
    }

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** PNG chunks an image needs or that only say how to show its colours. */
    private static final Set<String> PNG_KEPT = Set.of(
            "IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA", "iCCP", "sBIT", "sRGB", "bKGD", "pHYs");

    private ExportScrubber() {}

    /**
     * What a file is, from its kind and name, before it is opened — so a
     * casebook rendered before the archive exists names only files the archive
     * will hold. The bytes decide at scrub time; a mismatch withholds.
     *
     * @param ingestKind the {@code ingest_item.kind}, or null for a file of no
     *                   ingest row (a CRF attachment, a pipeline artifact)
     */
    public static Kind kindOf(String ingestKind, String path) {
        String name = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (name.endsWith(".dcm") || name.endsWith(".dicom")) return Kind.DICOM;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return Kind.JPEG;
        if (name.endsWith(".png")) return Kind.PNG;
        if ("dicom".equals(ingestKind)) return Kind.DICOM;
        // A stored image without an extension: JPEG or PNG, told apart by its bytes.
        if ("image".equals(ingestKind) && name.lastIndexOf('.') <= name.lastIndexOf('/')) return Kind.JPEG;
        return Kind.OTHER;
    }

    /**
     * A scrubbed copy of {@code source} in a new temporary file.
     *
     * @param label the participant's study label, for the DICOM patient name and ID
     * @throws Withheld when the file must not leave: of a type that cannot be
     *         scrubbed, not what it claims to be, burned-in annotation
     */
    public static Scrubbed scrub(Path source, Kind expected, String label) throws Withheld, IOException {
        if (expected == Kind.OTHER) {
            throw new Withheld("not exported: a file of this type cannot be de-identified");
        }
        Kind actual = sniff(source);
        if (actual == Kind.OTHER || (expected == Kind.DICOM) != (actual == Kind.DICOM)) {
            throw new Withheld("not exported: the file is not the image type it is recorded as");
        }
        Path out = Files.createTempFile("export-scrub-", ".tmp");
        boolean done = false;
        try {
            String method = switch (actual) {
                case DICOM -> {
                    scrubDicom(source, out, label);
                    yield DICOM_BASIC_PROFILE;
                }
                case JPEG -> {
                    stripJpeg(source, out);
                    yield METADATA_STRIPPED;
                }
                case PNG -> {
                    stripPng(source, out);
                    yield METADATA_STRIPPED;
                }
                default -> throw new Withheld("not exported: a file of this type cannot be de-identified");
            };
            done = true;
            return new Scrubbed(out, method);
        } catch (Withheld w) {
            throw w;
        } catch (IOException | RuntimeException e) {
            // A file the scrubber cannot read is not passed on as it is.
            throw new Withheld("not exported: the file could not be de-identified");
        } finally {
            if (!done) Files.deleteIfExists(out);
        }
    }

    /**
     * Would {@link #scrub} carry this file? The same checks, without writing:
     * its type, its leading bytes and, for a DICOM, its header (transfer
     * syntax, burned-in annotation). For a casebook rendered before the
     * archive exists, so that it names only files the archive will hold. A
     * file that still fails while it is scrubbed (truncated half-way) is
     * withheld and named in the manifest's omissions.
     */
    public static boolean wouldCarry(Path source, Kind expected) {
        if (expected == Kind.OTHER) return false;
        try {
            Kind actual = sniff(source);
            if (actual == Kind.OTHER || (expected == Kind.DICOM) != (actual == Kind.DICOM)) return false;
            if (actual != Kind.DICOM) return true;
            try (DicomInputStream in = new DicomInputStream(source.toFile())) {
                in.readFileMetaInformation();
                if (UID.DeflatedExplicitVRLittleEndian.equals(in.getTransferSyntax())) return false;
                Attributes head = in.readDatasetUntilPixelData();
                return !"YES".equalsIgnoreCase(head.getString(Tag.BurnedInAnnotation, ""));
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** JPEG, PNG or DICOM by the file's own bytes; OTHER when none fits. */
    static Kind sniff(Path p) throws IOException {
        byte[] head = new byte[132];
        int n;
        try (InputStream in = Files.newInputStream(p)) {
            n = in.readNBytes(head, 0, head.length);
        }
        if (n >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) {
            return Kind.JPEG;
        }
        if (n >= 8) {
            boolean png = true;
            for (int i = 0; i < 8; i++) png &= head[i] == PNG_SIGNATURE[i];
            if (png) return Kind.PNG;
        }
        if (n >= 132 && head[128] == 'D' && head[129] == 'I' && head[130] == 'C' && head[131] == 'M') {
            return Kind.DICOM;
        }
        return Kind.OTHER;
    }

    /* ---------------- DICOM ---------------- */

    static void scrubDicom(Path source, Path out, String label) throws IOException, Withheld {
        Attributes dataset;
        String tsuid;
        try (DicomInputStream in = new DicomInputStream(source.toFile())) {
            // Pixel data stays a reference into the source and is streamed on
            // write: an OCT volume is never held in memory.
            in.setIncludeBulkData(DicomInputStream.IncludeBulkData.URI);
            in.readFileMetaInformation();
            tsuid = in.getTransferSyntax();
            dataset = in.readDataset();
        }
        if (UID.DeflatedExplicitVRLittleEndian.equals(tsuid)) {
            // Bulk data cannot be referenced inside a deflated stream; such
            // files are rare enough to be withheld rather than buffered.
            throw new Withheld("not exported: a deflated DICOM file cannot be de-identified on export");
        }
        if ("YES".equalsIgnoreCase(dataset.getString(Tag.BurnedInAnnotation, ""))) {
            throw new Withheld("not exported: the image declares burned-in annotation, "
                    + "which the Basic Profile does not remove");
        }
        DeIdentifier deid = new DeIdentifier(
                DeIdentifier.Option.RetainLongitudinalTemporalInformationFullDatesOption);
        String pseudonym = label == null || label.isBlank() ? "ANONYMOUS" : label;
        deid.setDummyValue(Tag.PatientName, VR.PN, pseudonym);
        deid.setDummyValue(Tag.PatientID, VR.LO, pseudonym);
        deid.deidentify(dataset);

        // A fresh file meta information: the old one names the sending
        // application and the original instance UID.
        Attributes fmi = dataset.createFileMetaInformation(tsuid);
        try (DicomOutputStream dos = new DicomOutputStream(out.toFile())) {
            dos.writeDataset(fmi, dataset);
        }
    }

    /* ---------------- JPEG ---------------- */

    /**
     * Copies a JPEG without its metadata segments. Everything before the
     * first scan is a sequence of marker segments; what follows is image data,
     * copied as it is.
     */
    static void stripJpeg(Path source, Path out) throws IOException, Withheld {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)));
             OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            if (in.readUnsignedByte() != 0xff || in.readUnsignedByte() != 0xd8) {
                throw new Withheld("not exported: the file is not a JPEG");
            }
            os.write(0xff);
            os.write(0xd8);
            while (true) {
                int b = in.readUnsignedByte();
                if (b != 0xff) throw new Withheld("not exported: the JPEG could not be read");
                int marker = in.readUnsignedByte();
                while (marker == 0xff) marker = in.readUnsignedByte(); // fill bytes
                if (marker == 0xd9) { // EOI before any scan
                    os.write(0xff);
                    os.write(marker);
                    return;
                }
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { // no length
                    os.write(0xff);
                    os.write(marker);
                    continue;
                }
                int length = in.readUnsignedShort();
                if (length < 2) throw new Withheld("not exported: the JPEG could not be read");
                byte[] body = new byte[length - 2];
                in.readFully(body);
                if (keepJpegSegment(marker, body)) {
                    os.write(0xff);
                    os.write(marker);
                    os.write(length >> 8);
                    os.write(length & 0xff);
                    os.write(body);
                }
                if (marker == 0xda) { // SOS: the image data follows, as it is
                    in.transferTo(os);
                    return;
                }
            }
        } catch (EOFException e) {
            throw new Withheld("not exported: the JPEG is truncated");
        }
    }

    private static boolean keepJpegSegment(int marker, byte[] body) {
        if (marker == 0xfe) return false;                       // COM
        if (marker >= 0xe0 && marker <= 0xef) {                 // APPn
            String id = new String(body, 0, Math.min(body.length, 12), StandardCharsets.ISO_8859_1);
            if (marker == 0xe0) return id.startsWith("JFIF") || id.startsWith("JFXX");
            if (marker == 0xe2) return id.startsWith("ICC_PROFILE");
            if (marker == 0xee) return id.startsWith("Adobe");  // colour transform, needed to decode
            return false;                                       // EXIF, XMP, IPTC, vendor data
        }
        return true;                                            // tables, frame and scan headers
    }

    /* ---------------- PNG ---------------- */

    /** Copies a PNG with only the chunks that make up the image. */
    static void stripPng(Path source, Path out) throws IOException, Withheld {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)));
             OutputStream os = new BufferedOutputStream(Files.newOutputStream(out))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            for (int i = 0; i < 8; i++) {
                if (sig[i] != PNG_SIGNATURE[i]) throw new Withheld("not exported: the file is not a PNG");
            }
            os.write(sig);
            while (true) {
                int length = in.readInt();
                if (length < 0) throw new Withheld("not exported: the PNG could not be read");
                byte[] type = new byte[4];
                in.readFully(type);
                String name = new String(type, StandardCharsets.ISO_8859_1);
                boolean keep = PNG_KEPT.contains(name);
                // Data and CRC are copied or skipped together.
                if (keep) {
                    writeInt(os, length);
                    os.write(type);
                    copyExactly(in, os, length + 4L);
                } else {
                    in.skipNBytes(length + 4L);
                }
                if ("IEND".equals(name)) return;
            }
        } catch (EOFException e) {
            throw new Withheld("not exported: the PNG is truncated");
        }
    }

    private static void writeInt(OutputStream os, int v) throws IOException {
        os.write(v >>> 24);
        os.write(v >>> 16);
        os.write(v >>> 8);
        os.write(v);
    }

    private static void copyExactly(InputStream in, OutputStream os, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new EOFException();
            os.write(buf, 0, r);
            left -= r;
        }
    }
}
