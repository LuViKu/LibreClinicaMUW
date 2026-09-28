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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * DR-036 — the digest of a picture must not move when its metadata does.
 *
 * <p>Each format is exercised with the change a re-export makes — a JPEG
 * comment or EXIF block, a PNG text chunk, an .e2e patient record — and with
 * a change to the picture itself, which must move the digest. The .e2e
 * fixtures are built the way the upload page's test fixtures are
 * ({@code generate_fixtures.py}), plus image chunks, so the volume numbering
 * here is the page's numbering.
 */
public class ImageFingerprintTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /* ------------------------------------------------------------------ */
    /* JPEG                                                                */
    /* ------------------------------------------------------------------ */

    @Test
    public void aJpegCommentOrExifBlockDoesNotChangeTheDigest() throws IOException {
        byte[] scan = "entropy-coded-bytes".getBytes(StandardCharsets.US_ASCII);
        String plain = ImageFingerprint.ofImage(write(jpeg(scan, false, false)));
        assertEquals(plain, ImageFingerprint.ofImage(write(jpeg(scan, true, false))));
        assertEquals(plain, ImageFingerprint.ofImage(write(jpeg(scan, false, true))));
        assertEquals(plain, ImageFingerprint.ofImage(write(jpeg(scan, true, true))));
        assertEquals(64, plain.length());
    }

    @Test
    public void aDifferentJpegScanIsADifferentDigest() throws IOException {
        String a = ImageFingerprint.ofImage(write(jpeg("scan-A".getBytes(StandardCharsets.US_ASCII), true, false)));
        String b = ImageFingerprint.ofImage(write(jpeg("scan-B".getBytes(StandardCharsets.US_ASCII), true, false)));
        assertNotEquals(a, b);
    }

    @Test
    public void aJpegWithoutAScanFallsBackToTheWholeFile() throws IOException {
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x04, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9};
        assertEquals(sha256(truncated), ImageFingerprint.ofImage(write(truncated)));
    }

    /* ------------------------------------------------------------------ */
    /* PNG                                                                 */
    /* ------------------------------------------------------------------ */

    /** 1x1 transparent PNG, the same bytes the upload IT uses. */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
                    + "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    @Test
    public void aPngTextChunkDoesNotChangeTheDigest() throws IOException {
        String plain = ImageFingerprint.ofImage(write(PNG));
        byte[] annotated = withPngChunkAfterHeader(PNG, "tEXt", "Comment\0exported again".getBytes(StandardCharsets.ISO_8859_1));
        assertNotEquals(sha256(PNG), sha256(annotated));
        assertEquals(plain, ImageFingerprint.ofImage(write(annotated)));
        assertNotEquals("the picture digest is not the file digest", sha256(PNG), plain);
    }

    @Test
    public void aDifferentPngImageDataIsADifferentDigest() throws IOException {
        byte[] changed = PNG.clone();
        int idat = indexOf(changed, "IDAT".getBytes(StandardCharsets.US_ASCII));
        changed[idat + 4 + 5] ^= 0x01; // a byte inside the IDAT payload
        assertNotEquals(ImageFingerprint.ofImage(write(PNG)), ImageFingerprint.ofImage(write(changed)));
    }

    @Test
    public void anythingElseIsDigestedWhole() throws IOException {
        byte[] text = "not a picture at all".getBytes(StandardCharsets.US_ASCII);
        assertEquals(sha256(text), ImageFingerprint.ofImage(write(text)));
    }

    /* ------------------------------------------------------------------ */
    /* .e2e                                                                */
    /* ------------------------------------------------------------------ */

    @Test
    public void anE2eReExportedUnderAnotherPatientIdKeepsItsVolumeDigest() throws IOException {
        byte[] slo = pixels(8 * 8, 1);
        byte[] bscan = pixels(4 * 6 * 2, 2);
        Map<Integer, String> first = ImageFingerprint.ofE2e(write(e2e(
                patient(1, "TEST-001"),
                preData(1, 10, 100, 'R'),
                bscanMetadata(1, 10, 100, 0),
                image(1, 10, 100, -1, 0, FUNDUS, 8, 8, slo),
                image(1, 10, 100, 0, 1, OCT, 4, 6, bscan))));
        Map<Integer, String> second = ImageFingerprint.ofE2e(write(e2e(
                patient(7, "OTHER-99"),
                preData(7, 30, 300, 'R'),
                bscanMetadata(7, 30, 300, 0),
                image(7, 30, 300, -1, 0, FUNDUS, 8, 8, slo),
                image(7, 30, 300, 0, 1, OCT, 4, 6, bscan))));
        assertEquals(1, first.size());
        assertEquals(first.get(0), second.get(0));
        assertEquals(first.get(0), ImageFingerprint.ofE2eVolume(write(e2e(
                patient(1, "TEST-001"),
                preData(1, 10, 100, 'R'),
                bscanMetadata(1, 10, 100, 0),
                image(1, 10, 100, -1, 0, FUNDUS, 8, 8, slo),
                image(1, 10, 100, 0, 1, OCT, 4, 6, bscan))), 0));
    }

    @Test
    public void aChangedBscanChangesTheVolumeDigest() throws IOException {
        byte[] bscan = pixels(4 * 6 * 2, 2);
        byte[] other = bscan.clone();
        other[7] ^= 0x40;
        String a = ImageFingerprint.ofE2eVolume(write(e2e(
                preData(1, 10, 100, 'R'), image(1, 10, 100, 0, 1, OCT, 4, 6, bscan))), 0);
        String b = ImageFingerprint.ofE2eVolume(write(e2e(
                preData(1, 10, 100, 'R'), image(1, 10, 100, 0, 1, OCT, 4, 6, other))), 0);
        assertNotEquals(a, b);
    }

    @Test
    public void volumesAreNumberedAsTheUploadPageNumbersThem() throws IOException {
        byte[] od = pixels(4 * 4 * 2, 3);
        byte[] os = pixels(4 * 4 * 2, 4);
        // Volume B's laterality chunk comes first in the file, so the page
        // calls it volume 0; its images must be filed under 0 here too.
        Map<Integer, String> fps = ImageFingerprint.ofE2e(write(e2e(
                patient(2, "TEST-002"),
                preData(2, 20, 201, 'L'),
                preData(2, 20, 200, 'R'),
                bscanMetadata(2, 20, 200, 0),
                bscanMetadata(2, 20, 201, 0),
                image(2, 20, 200, 0, 1, OCT, 4, 4, od),
                image(2, 20, 201, 0, 1, OCT, 4, 4, os))));
        assertEquals(2, fps.size());
        String osAlone = ImageFingerprint.ofE2eVolume(write(e2e(
                preData(2, 20, 201, 'L'), image(2, 20, 201, 0, 1, OCT, 4, 4, os))), 0);
        String odAlone = ImageFingerprint.ofE2eVolume(write(e2e(
                preData(2, 20, 200, 'R'), image(2, 20, 200, 0, 1, OCT, 4, 4, od))), 0);
        assertEquals(osAlone, fps.get(0));
        assertEquals(odAlone, fps.get(1));
        assertNotEquals(fps.get(0), fps.get(1));
    }

    @Test
    public void theOrderOfChunksInTheFileDoesNotMatter() throws IOException {
        List<Chunk> chunks = new ArrayList<>(List.of(
                preData(1, 10, 100, 'R'),
                bscanMetadata(1, 10, 100, 0),
                image(1, 10, 100, 0, 1, OCT, 4, 4, pixels(32, 5)),
                image(1, 10, 100, 2, 1, OCT, 4, 4, pixels(32, 6)),
                image(1, 10, 100, 4, 1, OCT, 4, 4, pixels(32, 7)),
                image(1, 10, 100, -1, 0, FUNDUS, 4, 4, pixels(16, 8))));
        String ordered = ImageFingerprint.ofE2eVolume(write(e2e(chunks.toArray(new Chunk[0]))), 0);
        List<Chunk> shuffled = new ArrayList<>(chunks);
        Collections.reverse(shuffled);
        assertEquals(ordered, ImageFingerprint.ofE2eVolume(write(e2e(shuffled.toArray(new Chunk[0]))), 0));
    }

    @Test
    public void aMultiVolumeContainerIsReadPastItsSentinel() throws IOException {
        Chunk[] chunks = {preData(1, 10, 100, 'R'), image(1, 10, 100, 0, 1, OCT, 4, 4, pixels(32, 9))};
        String plain = ImageFingerprint.ofE2eVolume(write(e2e(chunks)), 0);
        assertEquals(plain, ImageFingerprint.ofE2eVolume(write(e2eMultiVolume(chunks)), 0));
    }

    @Test
    public void aVolumeWithoutImagesHasNoDigest() throws IOException {
        Map<Integer, String> fps = ImageFingerprint.ofE2e(write(e2e(
                patient(1, "TEST-001"), preData(1, 10, 100, 'R'), bscanMetadata(1, 10, 100, 0))));
        assertTrue(fps.isEmpty());
        assertNull(ImageFingerprint.ofE2eVolume(write(e2e(preData(1, 10, 100, 'R'))), 0));
    }

    @Test
    public void somethingThatIsNotAnE2eIsRefused() throws IOException {
        try {
            ImageFingerprint.ofE2e(write(PNG));
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not a Spectralis"));
        }
        try {
            ImageFingerprint.ofE2e(write(new byte[10]));
            fail("expected an IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("too short"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* fixture builders                                                    */
    /* ------------------------------------------------------------------ */

    private Path write(byte[] bytes) throws IOException {
        Path p = tmp.newFile().toPath();
        Files.write(p, bytes);
        return p;
    }

    private static String sha256(byte[] bytes) {
        try {
            return ImageFingerprint.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        throw new IllegalArgumentException("needle not found");
    }

    private static byte[] pixels(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((i * 31 + seed * 17) & 0xFF);
        return b;
    }

    /** A JPEG's marker skeleton: SOI, APP0, optional APP1 and COM, DQT, SOS + scan, EOI. */
    private static byte[] jpeg(byte[] scan, boolean withExif, boolean withComment) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0xFF, (byte) 0xD8});
        segment(out, 0xE0, "JFIF\0\1\1\0\0\1\0\1\0\0".getBytes(StandardCharsets.ISO_8859_1));
        if (withExif) {
            segment(out, 0xE1, "Exif\0\0MM\0*\0\0\0\10camera-and-date".getBytes(StandardCharsets.ISO_8859_1));
        }
        if (withComment) {
            segment(out, 0xFE, "exported again".getBytes(StandardCharsets.ISO_8859_1));
        }
        byte[] dqt = new byte[65];
        for (int i = 1; i < dqt.length; i++) dqt[i] = (byte) i;
        segment(out, 0xDB, dqt);
        segment(out, 0xDA, new byte[] {1, 1, 0, 0, 0x3F, 0});
        out.write(scan);
        out.write(new byte[] {(byte) 0xFF, (byte) 0xD9});
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) throws IOException {
        out.write(0xFF);
        out.write(marker);
        int len = payload.length + 2;
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(payload);
    }

    /** Insert a chunk right after IHDR, with a correct CRC. */
    private static byte[] withPngChunkAfterHeader(byte[] png, String type, byte[] data) throws IOException {
        int afterHeader = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterHeader);
        ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.length);
        out.write(len.array());
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc.getValue()).array());
        out.write(png, afterHeader, png.length - afterHeader);
        return out.toByteArray();
    }

    /* ---- .e2e, after generate_fixtures.py ---- */

    private static final int FILE_HEADER_SIZE = 36;
    private static final int MAIN_DIRECTORY_SIZE = 52;
    private static final int SUB_DIRECTORY_ENTRY_SIZE = 44;
    private static final int CHUNK_HEADER_SIZE = 60;

    private static final int TYPE_PRE_DATA = 3;
    private static final int TYPE_PATIENT_DATA = 9;
    private static final int TYPE_BSCAN_METADATA = 10004;
    private static final int TYPE_IMAGE = 0x40000000;
    private static final int FUNDUS = 33620481;
    private static final int OCT = 35652097;

    private record Chunk(int type, int patientDbId, int studyId, int seriesId, int sliceId, int ind, byte[] payload) {}

    private static Chunk patient(int patientDbId, String patientId) {
        byte[] payload = new byte[127];
        byte[] id = patientId.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(id, 0, payload, 102, id.length);
        return new Chunk(TYPE_PATIENT_DATA, patientDbId, 0, 0, -1, 0, payload);
    }

    private static Chunk preData(int patientDbId, int studyId, int seriesId, char laterality) {
        ByteBuffer b = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(0).put((byte) laterality);
        return new Chunk(TYPE_PRE_DATA, patientDbId, studyId, seriesId, -1, 0, b.array());
    }

    private static Chunk bscanMetadata(int patientDbId, int studyId, int seriesId, int sliceId) {
        ByteBuffer b = ByteBuffer.allocate(104).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(64, 49);
        b.putLong(88, 133_500_000_000_000_000L);
        return new Chunk(TYPE_BSCAN_METADATA, patientDbId, studyId, seriesId, sliceId, 1, b.array());
    }

    private static Chunk image(int patientDbId, int studyId, int seriesId, int sliceId, int ind,
                               int imageType, int width, int height, byte[] pixels) {
        ByteBuffer b = ByteBuffer.allocate(20 + pixels.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(pixels.length).putInt(imageType).putInt(0).putInt(width).putInt(height).put(pixels);
        return new Chunk(TYPE_IMAGE, patientDbId, studyId, seriesId, sliceId, ind, b.array());
    }

    private static byte[] e2e(Chunk... chunks) throws IOException {
        return assemble(0, chunks);
    }

    private static byte[] e2eMultiVolume(Chunk... chunks) throws IOException {
        return assemble(64, chunks);
    }

    /**
     * File header, one main directory pointing at itself, one sub-directory
     * entry per chunk, then the chunks — exactly generate_fixtures.py's
     * layout, optionally behind the 64-byte multi-volume sentinel block.
     */
    private static byte[] assemble(int skip, Chunk[] chunks) throws IOException {
        int mainDirOffset = FILE_HEADER_SIZE;
        int subDirsStart = mainDirOffset + MAIN_DIRECTORY_SIZE;
        int chunksStart = subDirsStart + SUB_DIRECTORY_ENTRY_SIZE * chunks.length;
        int[] starts = new int[chunks.length];
        int cursor = chunksStart;
        for (int i = 0; i < chunks.length; i++) {
            starts[i] = cursor;
            cursor += CHUNK_HEADER_SIZE + chunks[i].payload().length;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (skip > 0) {
            byte[] sentinel = new byte[skip];
            byte[] magic = "E2EMultipleVolumeFile".getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(magic, 0, sentinel, 0, magic.length);
            out.write(sentinel);
        }
        // file header: 12-byte magic, u32 version, 20 zero bytes
        out.write(padded("CMDb", 12));
        out.write(le(1));
        out.write(new byte[20]);
        // main directory
        out.write(padded("MDbDir", 12));
        out.write(le(1));
        out.write(new byte[20]);
        out.write(le(chunks.length));
        out.write(le(mainDirOffset));
        out.write(le(0));
        out.write(new byte[4]);
        // sub-directory entries
        for (int i = 0; i < chunks.length; i++) {
            Chunk c = chunks[i];
            ByteBuffer e = ByteBuffer.allocate(SUB_DIRECTORY_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            e.putInt(1).putInt(starts[i]).putInt(CHUNK_HEADER_SIZE + c.payload().length).putInt(0)
                    .putInt(c.patientDbId()).putInt(c.studyId()).putInt(c.seriesId()).putInt(c.sliceId())
                    .putShort((short) c.ind()).putShort((short) 0).putInt(c.type()).putInt(0);
            out.write(e.array());
        }
        // chunks
        for (int i = 0; i < chunks.length; i++) {
            Chunk c = chunks[i];
            ByteBuffer h = ByteBuffer.allocate(CHUNK_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            h.put(padded("MDbChunk", 12)).putInt(0).putInt(0).putInt(1)
                    .putInt(CHUNK_HEADER_SIZE + c.payload().length).putInt(0)
                    .putInt(c.patientDbId()).putInt(c.studyId()).putInt(c.seriesId()).putInt(c.sliceId())
                    .putShort((short) c.ind()).putShort((short) 0).putInt(c.type()).putInt(0);
            out.write(h.array());
            out.write(c.payload());
        }
        return out.toByteArray();
    }

    private static byte[] padded(String s, int n) {
        return Arrays.copyOf(s.getBytes(StandardCharsets.US_ASCII), n);
    }

    private static byte[] le(int v) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
    }
}
