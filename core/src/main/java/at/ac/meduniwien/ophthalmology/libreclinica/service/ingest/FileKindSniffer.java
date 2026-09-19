/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * DR-029 — what an uploaded file is, decided by its bytes.
 *
 * <p>The two upload pages each trusted a different claim about the file: the
 * image page believed the browser's content type, the OCT page believed the
 * extension. Neither is the file. A combined uploader that routes a file to
 * the retinal pipeline, the DICOM sidecar or straight to storage on the
 * strength of a claim would route wrongly on the first mislabelled export, so
 * the kind is read off the leading bytes and the claim is ignored.
 *
 * <p>Deliberately a short list. A kind not recognised here is refused with a
 * message, not stored as "other": a file the platform cannot name is a file
 * nobody can later find.
 */
public final class FileKindSniffer {

    /** How many leading bytes {@link #sniff(byte[])} needs to see: the DICOM marker sits at 128. */
    public static final int HEAD_BYTES = 132;

    /** The verdict — and, for the record the store writes, what to call the file. */
    public record Sniffed(IngestArtifactStore.Kind kind, String contentType, String extension) {

        /** The wire name a caller can show or match on: e2e, dicom, jpeg, png. */
        public String format() {
            return switch (contentType) {
                case "image/jpeg" -> "jpeg";
                case "image/png" -> "png";
                case "application/dicom" -> "dicom";
                default -> kind.dir();
            };
        }
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] DICM = {'D', 'I', 'C', 'M'};
    private static final int DICOM_PREAMBLE = 128;

    /**
     * Heidelberg Spectralis export magics: the single-volume header and the
     * multi-volume container. Nothing else is accepted as OCT.
     */
    private static final String[] E2E_MAGICS = {"CMDb", "MDbDir", "E2EMultipleVolumeFile"};

    private FileKindSniffer() {}

    /**
     * @param head the leading bytes of the file, at least {@link #HEAD_BYTES}
     *             when the file is that long
     * @return the kind, or null when the bytes are none of the supported formats
     */
    public static Sniffed sniff(byte[] head) {
        if (head == null || head.length == 0) return null;
        if (startsWith(head, PNG)) {
            return new Sniffed(IngestArtifactStore.Kind.IMAGE, "image/png", ".png");
        }
        if (startsWith(head, JPEG)) {
            return new Sniffed(IngestArtifactStore.Kind.IMAGE, "image/jpeg", ".jpg");
        }
        if (head.length >= DICOM_PREAMBLE + DICM.length
                && regionMatches(head, DICOM_PREAMBLE, DICM)) {
            return new Sniffed(IngestArtifactStore.Kind.DICOM, "application/dicom", ".dcm");
        }
        for (String magic : E2E_MAGICS) {
            if (startsWith(head, magic.getBytes(StandardCharsets.US_ASCII))) {
                return new Sniffed(IngestArtifactStore.Kind.E2E, "application/octet-stream", ".e2e");
            }
        }
        return null;
    }

    /**
     * As {@link #sniff(byte[])}, with the one concession the OCT page always
     * made: a file the operator named {@code .e2e} whose header starts with
     * printable ASCII is taken for a Spectralis export even when its magic is
     * not one of the known three, because the vendor has used more than three.
     */
    public static Sniffed sniff(byte[] head, String originalFilename) {
        Sniffed byBytes = sniff(head);
        if (byBytes != null) return byBytes;
        if (originalFilename != null
                && originalFilename.toLowerCase(Locale.ROOT).endsWith(".e2e")
                && head != null && head.length >= 12 && printableAscii(head, 12)) {
            return new Sniffed(IngestArtifactStore.Kind.E2E, "application/octet-stream", ".e2e");
        }
        return null;
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        return head.length >= magic.length && regionMatches(head, 0, magic);
    }

    private static boolean regionMatches(byte[] head, int offset, byte[] magic) {
        for (int i = 0; i < magic.length; i++) {
            if (head[offset + i] != magic[i]) return false;
        }
        return true;
    }

    private static boolean printableAscii(byte[] head, int n) {
        int c = head[0] & 0xFF;
        if (c < 0x20 || c >= 0x7F) return false;
        for (int i = 0; i < n; i++) {
            int b = head[i] & 0xFF;
            if (b != 0 && (b < 0x20 || b >= 0x7F)) return false;
        }
        return true;
    }
}
