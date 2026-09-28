/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.ingest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DR-036 — a digest of what a file <em>shows</em>, not of the file.
 *
 * <p>The exact-bytes digest ({@code ingest_item.sha256}) catches a file sent
 * twice. It does not catch the case the study team asked about: the same
 * capture exported a second time under a different patient label. A Clarus or
 * Spectralis re-export rewrites the patient tags, and a JPEG re-saved from a
 * phone gallery gets new EXIF, so the bytes differ while the picture is the
 * same. This class hashes only the picture.
 *
 * <ul>
 *   <li><strong>JPEG</strong> — SHA-256 from the first SOS marker to the end
 *       of the file: the entropy-coded scan(s) and nothing before them, where
 *       the APPn (EXIF, XMP) and COM segments live.</li>
 *   <li><strong>PNG</strong> — SHA-256 over the concatenated IDAT payloads;
 *       tEXt, iTXt, tIME and the rest are skipped.</li>
 *   <li><strong>Spectralis .e2e</strong> — one digest per OCT volume, over the
 *       image chunks (SLO and B-scans) of that volume ordered by slice and
 *       kind, so the patient-data and directory chunks, which a re-export
 *       rewrites, play no part. Volumes are grouped and numbered exactly as
 *       the upload page's own header reader does it, so the digest of
 *       {@code scanIndex} n here is the digest of the volume the page called
 *       n.</li>
 *   <li>Anything else — the whole file.</li>
 * </ul>
 *
 * <p>DICOM files are not read here: the app has no DICOM parser, and the
 * sidecar that describes an uploaded file answers the same question over the
 * decoded pixel array ({@code pixelSha256}).
 *
 * <p>Nothing is loaded into memory as a whole. A widefield export or a 200 MB
 * OCT file is walked by position and digested through a 64 KiB buffer, the
 * same discipline the upload path already follows for the exact-bytes digest.
 * Every offset read from the file is bounds-checked against the file's
 * length; a malformed file yields a shorter digest input, never an exception
 * from an out-of-range read.
 */
public final class ImageFingerprint {

    private static final int BUFFER = 64 * 1024;

    /* ---------------- .e2e layout, mirroring web/src/spa/src/lib/e2eParser.ts ---------------- */

    private static final byte[] MULTI_VOLUME_MAGIC = "E2EMultipleVolumeFile".getBytes(StandardCharsets.ISO_8859_1);
    private static final int FILE_HEADER_BYTES = 36;
    private static final int MAIN_DIRECTORY_BYTES = 52;
    private static final int SUB_DIRECTORY_ENTRY_BYTES = 44;
    private static final int CHUNK_HEADER_BYTES = 60;

    private static final int MAIN_DIR_NUM_ENTRIES_OFFSET = 36;
    private static final int MAIN_DIR_CURRENT_OFFSET = 40;
    private static final int MAIN_DIR_PREV_OFFSET = 44;

    private static final int CHUNK_PATIENT_DB_ID_OFFSET = 32;
    private static final int CHUNK_STUDY_ID_OFFSET = 36;
    private static final int CHUNK_SERIES_ID_OFFSET = 40;
    private static final int CHUNK_SLICE_ID_OFFSET = 44;
    private static final int CHUNK_IND_OFFSET = 48;
    private static final int CHUNK_TYPE_OFFSET = 52;

    private static final long CHUNK_TYPE_PRE_DATA = 3;
    private static final long CHUNK_TYPE_LATERALITY = 11;
    private static final long CHUNK_TYPE_BSCAN_METADATA = 10004;
    /** 0x40000000 — the chunk type that carries pixels (SLO and B-scans alike). */
    private static final long CHUNK_TYPE_IMAGE = 1073741824L;

    private static final int PRE_DATA_LATERALITY_OFFSET = 4;
    private static final int LAT_STRUCT_LATERALITY_OFFSET = 14;
    private static final int BSCAN_ACQUISITION_TIME_OFFSET = 88;

    /** Image payload: size u32, type u32, unknown u32, width u32, height u32, then pixels. */
    private static final int IMAGE_PAYLOAD_TYPE_OFFSET = 4;
    private static final int IMAGE_PAYLOAD_WIDTH_OFFSET = 12;
    private static final int IMAGE_PAYLOAD_PIXELS_OFFSET = 20;
    /** Image sub-types, as OCT-Converter reads them: 8-bit fundus (SLO), 16-bit OCT B-scan. */
    private static final long IMAGE_TYPE_FUNDUS = 33620481L;
    private static final long IMAGE_TYPE_OCT = 35652097L;

    /** A directory list longer than this is a malformed pointer chain, not a scan. */
    private static final int MAX_DIRECTORIES = 100_000;

    private ImageFingerprint() {}

    /* ------------------------------------------------------------------ */
    /* images                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * The picture digest of a JPEG or PNG file; the whole-file digest of
     * anything else, and of a JPEG or PNG whose structure cannot be walked.
     *
     * @return 64 lower-case hex characters
     */
    public static String ofImage(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            Reader r = new Reader(ch);
            String fp = null;
            if (r.length >= 3 && r.u8(0) == 0xFF && r.u8(1) == 0xD8 && r.u8(2) == 0xFF) {
                fp = jpeg(r);
            } else if (r.length >= 8 && r.u8(0) == 0x89 && r.u8(1) == 'P' && r.u8(2) == 'N' && r.u8(3) == 'G') {
                fp = png(r);
            }
            return fp != null ? fp : wholeFile(r);
        }
    }

    /** From the first SOS marker to the end of the file; null when there is no SOS. */
    private static String jpeg(Reader r) throws IOException {
        long pos = 2;
        while (pos + 4 <= r.length) {
            if (r.u8(pos) != 0xFF) return null;
            int marker = r.u8(pos + 1);
            if (marker == 0xFF) {
                // Fill byte before a marker.
                pos++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                // Standalone markers carry no length.
                pos += 2;
                continue;
            }
            if (marker == 0xD9) return null; // EOI before any scan
            if (marker == 0xDA) {
                MessageDigest md = sha256();
                r.digest(md, pos, r.length - pos);
                return hex(md.digest());
            }
            int segmentLength = r.u16be(pos + 2);
            if (segmentLength < 2) return null;
            pos += 2 + segmentLength;
        }
        return null;
    }

    /** The IDAT payloads in order; null when there is none. */
    private static String png(Reader r) throws IOException {
        MessageDigest md = sha256();
        boolean any = false;
        long pos = 8;
        while (pos + 8 <= r.length) {
            long length = r.u32be(pos);
            String type = r.ascii(pos + 4, 4);
            if (pos + 8 + length + 4 > r.length) break;
            if ("IDAT".equals(type)) {
                r.digest(md, pos + 8, length);
                any = true;
            } else if ("IEND".equals(type)) {
                break;
            }
            pos += 8 + length + 4;
        }
        return any ? hex(md.digest()) : null;
    }

    private static String wholeFile(Reader r) throws IOException {
        MessageDigest md = sha256();
        r.digest(md, 0, r.length);
        return hex(md.digest());
    }

    /* ------------------------------------------------------------------ */
    /* .e2e                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * One digest per OCT volume, keyed by the volume's {@code scanIndex} as
     * the upload page numbers them. A volume with no image chunks has no
     * entry.
     *
     * @throws IOException when the file cannot be read or is not a Spectralis export
     */
    public static Map<Integer, String> ofE2e(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return e2e(new Reader(ch));
        }
    }

    /** The digest of one volume of a multi-volume file; null when that volume has no image chunks. */
    public static String ofE2eVolume(Path file, int scanIndex) throws IOException {
        return ofE2e(file).get(scanIndex);
    }

    /** Where a volume's image chunks are, in the order they are digested. */
    private record ImageChunk(String volumeKey, int sliceId, int ind, int directoryOrder,
                              long payloadOffset, long pixelBytes) {}

    private static Map<Integer, String> e2e(Reader r) throws IOException {
        if (r.length < FILE_HEADER_BYTES + MAIN_DIRECTORY_BYTES) {
            throw new IOException("not a Spectralis .e2e file: too short");
        }
        long skip = r.startsWith(MULTI_VOLUME_MAGIC) ? 64 : 0;
        if (skip + FILE_HEADER_BYTES + MAIN_DIRECTORY_BYTES > r.length) {
            throw new IOException("not a Spectralis .e2e file: too short");
        }
        int first = r.u8(skip);
        if (first < 0x20 || first >= 0x7f) {
            throw new IOException("not a Spectralis .e2e file: no header magic");
        }

        // The directory linked list: the first main directory's `current`
        // starts it, each one's `prev` continues it, 0 ends it.
        List<Long> directories = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long current = r.u32le(skip + FILE_HEADER_BYTES + MAIN_DIR_CURRENT_OFFSET);
        while (current != 0) {
            long absolute = current + skip;
            if (absolute + MAIN_DIRECTORY_BYTES > r.length) break;
            if (!seen.add(absolute) || directories.size() >= MAX_DIRECTORIES) break;
            directories.add(absolute);
            long prev = r.u32le(absolute + MAIN_DIR_PREV_OFFSET);
            if (prev == current) break;
            current = prev;
        }

        // Every chunk's start, from the sub-directory entries. Entries whose
        // start does not lie past their pos are directory back-pointers.
        List<Long> chunkStarts = new ArrayList<>();
        for (long dir : directories) {
            long numEntries = r.u32le(dir + MAIN_DIR_NUM_ENTRIES_OFFSET);
            long entry = dir + MAIN_DIRECTORY_BYTES;
            for (long i = 0; i < numEntries; i++) {
                if (entry + SUB_DIRECTORY_ENTRY_BYTES > r.length) break;
                long pos = r.u32le(entry);
                long start = r.u32le(entry + 4);
                if (start > pos && start > 0) chunkStarts.add(start);
                entry += SUB_DIRECTORY_ENTRY_BYTES;
            }
        }

        // Volumes, created in the order the page's reader creates them, and
        // the image chunks found along the way.
        Map<String, Integer> volumeOrder = new LinkedHashMap<>();
        List<ImageChunk> images = new ArrayList<>();
        int directoryOrder = 0;
        for (long start : chunkStarts) {
            long chunk = start + skip;
            if (chunk + CHUNK_HEADER_BYTES > r.length) continue;
            long patientDbId = r.u32le(chunk + CHUNK_PATIENT_DB_ID_OFFSET);
            long studyId = r.u32le(chunk + CHUNK_STUDY_ID_OFFSET);
            long seriesId = r.u32le(chunk + CHUNK_SERIES_ID_OFFSET);
            int sliceId = r.i32le(chunk + CHUNK_SLICE_ID_OFFSET);
            int ind = r.u16le(chunk + CHUNK_IND_OFFSET);
            long type = r.u32le(chunk + CHUNK_TYPE_OFFSET);
            long payload = chunk + CHUNK_HEADER_BYTES;
            String key = patientDbId + "_" + studyId + "_" + seriesId;

            if (type == CHUNK_TYPE_PRE_DATA) {
                if (payload + PRE_DATA_LATERALITY_OFFSET + 1 > r.length) continue;
                if (isLateralityChar(r.u8(payload + PRE_DATA_LATERALITY_OFFSET))) {
                    volumeOrder.putIfAbsent(key, volumeOrder.size());
                }
            } else if (type == CHUNK_TYPE_LATERALITY) {
                if (payload + LAT_STRUCT_LATERALITY_OFFSET + 1 > r.length) continue;
                if (isLateralityChar(r.u8(payload + LAT_STRUCT_LATERALITY_OFFSET))) {
                    volumeOrder.putIfAbsent(key, volumeOrder.size());
                }
            } else if (type == CHUNK_TYPE_BSCAN_METADATA) {
                if (payload + BSCAN_ACQUISITION_TIME_OFFSET + 8 > r.length) continue;
                volumeOrder.putIfAbsent(key, volumeOrder.size());
            } else if (type == CHUNK_TYPE_IMAGE) {
                if (payload + IMAGE_PAYLOAD_PIXELS_OFFSET > r.length) continue;
                long imageType = r.u32le(payload + IMAGE_PAYLOAD_TYPE_OFFSET);
                long width = r.u32le(payload + IMAGE_PAYLOAD_WIDTH_OFFSET);
                long height = r.u32le(payload + IMAGE_PAYLOAD_WIDTH_OFFSET + 4);
                long pixelBytes;
                if (imageType == IMAGE_TYPE_FUNDUS) {
                    pixelBytes = width * height;
                } else if (imageType == IMAGE_TYPE_OCT) {
                    pixelBytes = width * height * 2;
                } else {
                    // An image type this reader does not know: take the
                    // chunk's own size, which counts the bytes after the header.
                    pixelBytes = Math.max(0, r.u32le(chunk + 24) - IMAGE_PAYLOAD_PIXELS_OFFSET);
                }
                long available = r.length - (payload + IMAGE_PAYLOAD_PIXELS_OFFSET);
                if (pixelBytes > available) pixelBytes = available;
                images.add(new ImageChunk(key, sliceId, ind, directoryOrder, payload, pixelBytes));
            }
            directoryOrder++;
        }

        // Digest each volume's images in an order the file's layout does not
        // decide: by slice, then kind, then (only for ties) directory order.
        images.sort(Comparator.comparingInt(ImageChunk::sliceId)
                .thenComparingInt(ImageChunk::ind)
                .thenComparingInt(ImageChunk::directoryOrder));
        Map<String, MessageDigest> digests = new HashMap<>();
        for (ImageChunk img : images) {
            if (!volumeOrder.containsKey(img.volumeKey())) continue;
            MessageDigest md = digests.computeIfAbsent(img.volumeKey(), k -> sha256());
            // Type, width and height, then the pixels; `size` and the unknown
            // word before the dimensions are left out.
            r.digest(md, img.payloadOffset() + IMAGE_PAYLOAD_TYPE_OFFSET, 4);
            r.digest(md, img.payloadOffset() + IMAGE_PAYLOAD_WIDTH_OFFSET, 8);
            r.digest(md, img.payloadOffset() + IMAGE_PAYLOAD_PIXELS_OFFSET, img.pixelBytes());
        }
        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> v : volumeOrder.entrySet()) {
            MessageDigest md = digests.get(v.getKey());
            if (md != null) out.put(v.getValue(), hex(md.digest()));
        }
        return out;
    }

    private static boolean isLateralityChar(int c) {
        return c == 'R' || c == 'r' || c == 'L' || c == 'l';
    }

    /* ------------------------------------------------------------------ */
    /* plumbing                                                            */
    /* ------------------------------------------------------------------ */

    /** Positional reads over a channel, each checked against the file's length. */
    private static final class Reader {
        private final FileChannel ch;
        final long length;
        private final ByteBuffer small = ByteBuffer.allocate(8);
        private ByteBuffer big;

        Reader(FileChannel ch) throws IOException {
            this.ch = ch;
            this.length = ch.size();
        }

        boolean startsWith(byte[] magic) throws IOException {
            if (length < magic.length) return false;
            ByteBuffer b = ByteBuffer.allocate(magic.length);
            readFully(b, 0);
            for (int i = 0; i < magic.length; i++) {
                if (b.get(i) != magic[i]) return false;
            }
            return true;
        }

        int u8(long pos) throws IOException {
            return read(pos, 1).get(0) & 0xFF;
        }

        int u16be(long pos) throws IOException {
            return read(pos, 2).order(ByteOrder.BIG_ENDIAN).getShort(0) & 0xFFFF;
        }

        int u16le(long pos) throws IOException {
            return read(pos, 2).order(ByteOrder.LITTLE_ENDIAN).getShort(0) & 0xFFFF;
        }

        long u32be(long pos) throws IOException {
            return read(pos, 4).order(ByteOrder.BIG_ENDIAN).getInt(0) & 0xFFFFFFFFL;
        }

        long u32le(long pos) throws IOException {
            return read(pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(0) & 0xFFFFFFFFL;
        }

        int i32le(long pos) throws IOException {
            return read(pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        }

        String ascii(long pos, int n) throws IOException {
            ByteBuffer b = read(pos, n);
            byte[] bytes = new byte[n];
            b.get(0, bytes);
            return new String(bytes, StandardCharsets.US_ASCII);
        }

        /** Feed {@code n} bytes from {@code pos} to the digest; fewer when the file ends first. */
        void digest(MessageDigest md, long pos, long n) throws IOException {
            if (big == null) big = ByteBuffer.allocate(BUFFER);
            long remaining = Math.min(n, Math.max(0, length - pos));
            long at = pos;
            while (remaining > 0) {
                big.clear();
                big.limit((int) Math.min(BUFFER, remaining));
                int got = ch.read(big, at);
                if (got <= 0) break;
                md.update(big.array(), 0, got);
                at += got;
                remaining -= got;
            }
        }

        private ByteBuffer read(long pos, int n) throws IOException {
            if (pos < 0 || pos + n > length) {
                throw new IOException("read past the end of the file");
            }
            small.clear();
            small.limit(n);
            readFully(small, pos);
            return small;
        }

        private void readFully(ByteBuffer b, long pos) throws IOException {
            long at = pos;
            while (b.hasRemaining()) {
                int got = ch.read(b, at);
                if (got <= 0) throw new IOException("read past the end of the file");
                at += got;
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JRE", e);
        }
    }

    static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
