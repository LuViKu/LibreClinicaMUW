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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 2 and 3 of the de-identification check for a Spectralis {@code .e2e}
 * file: the server reads the patient-data chunks itself and does not take the
 * browser's word that they were stripped.
 *
 * <p>The chunk layout is the one {@link ImageFingerprint} and the SPA's
 * {@code e2eParser.ts} walk, and the one {@code oct_converter}'s
 * {@code e2e_binary.py} declares: a 36-byte file header, 52-byte main
 * directories linked by {@code prev}, 44-byte sub-directory entries, and
 * chunks of a 60-byte header plus payload. A chunk of type 9 is the patient
 * record, 127 bytes:
 * <pre>
 *   0  first_name  31   must be all NUL
 *  31  surname     51   all NUL, or exactly the subject label NUL-padded
 *  82  title       15   must be all NUL
 *  97  birthdate    4   u32, must be 0
 * 101  sex          1   must be 0
 * 102  patient_id  25   all NUL, or exactly the subject label NUL-padded
 * </pre>
 * (MUW's convention puts the study label in the surname slot, so that slot
 * is allowed to carry it.)
 *
 * <p>Fail-closed: a file whose structure cannot be walked, a chunk that runs
 * past the end of the file, a file with image chunks but no patient record,
 * and a patient record with non-zero bytes beyond the 127 all count as
 * violations. A violation is a field <em>name</em>; no value read from the
 * file is ever returned or logged.
 */
public final class E2eDeidentificationVerifier {

    /** Outcome: the field names that failed, empty when the file is clean. */
    public record Result(List<String> violations, int patientChunks, int imageChunks) {
        public boolean ok() {
            return violations.isEmpty();
        }
    }

    public static final String STRUCTURE = "e2e.structure";
    public static final String FIRST_NAME = "e2e.firstName";
    public static final String SURNAME = "e2e.surname";
    public static final String TITLE = "e2e.title";
    public static final String PATIENT_ID = "e2e.patientId";
    public static final String BIRTHDATE = "e2e.birthdate";
    public static final String SEX = "e2e.sex";
    public static final String PATIENT_DATA_MISSING = "e2e.patientDataMissing";
    public static final String PATIENT_DATA_EXTRA = "e2e.patientDataExtraBytes";

    private static final byte[] MULTI_VOLUME_MAGIC = "E2EMultipleVolumeFile".getBytes(StandardCharsets.ISO_8859_1);
    private static final int FILE_HEADER_BYTES = 36;
    private static final int MAIN_DIRECTORY_BYTES = 52;
    private static final int SUB_DIRECTORY_ENTRY_BYTES = 44;
    private static final int CHUNK_HEADER_BYTES = 60;
    private static final int MAIN_DIR_NUM_ENTRIES_OFFSET = 36;
    private static final int MAIN_DIR_CURRENT_OFFSET = 40;
    private static final int MAIN_DIR_PREV_OFFSET = 44;
    private static final int CHUNK_SIZE_OFFSET = 24;
    private static final int CHUNK_TYPE_OFFSET = 52;
    private static final long CHUNK_TYPE_PATIENT_DATA = 9;
    private static final long CHUNK_TYPE_IMAGE = 0x40000000L;
    private static final int MAX_DIRECTORIES = 100_000;
    private static final int MAX_CHUNKS = 5_000_000;

    static final int FIRST_NAME_OFF = 0;
    static final int FIRST_NAME_LEN = 31;
    static final int SURNAME_OFF = 31;
    static final int SURNAME_LEN = 51;
    static final int TITLE_OFF = 82;
    static final int TITLE_LEN = 15;
    static final int BIRTHDATE_OFF = 97;
    static final int SEX_OFF = 101;
    static final int PATIENT_ID_OFF = 102;
    static final int PATIENT_ID_LEN = 25;
    static final int PATIENT_DATA_BYTES = 127;

    private E2eDeidentificationVerifier() {}

    /**
     * @param label the study subject's label the upload is for; a name slot
     *              may hold exactly this. Null or blank: slots must be empty.
     */
    public static Result verify(Path file, String label) {
        Set<String> violations = new LinkedHashSet<>();
        int[] counts = new int[2];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            walk(new Chan(ch), label, violations, counts);
        } catch (IOException | RuntimeException e) {
            violations.add(STRUCTURE);
        }
        return new Result(new ArrayList<>(violations), counts[0], counts[1]);
    }

    private static void walk(Chan r, String label, Set<String> violations, int[] counts) throws IOException {
        if (r.length < FILE_HEADER_BYTES + MAIN_DIRECTORY_BYTES) {
            violations.add(STRUCTURE);
            return;
        }
        long skip = r.startsWith(MULTI_VOLUME_MAGIC) ? 64 : 0;
        if (skip + FILE_HEADER_BYTES + MAIN_DIRECTORY_BYTES > r.length) {
            violations.add(STRUCTURE);
            return;
        }
        int first = r.u8(skip);
        if (first < 0x20 || first >= 0x7f) {
            violations.add(STRUCTURE);
            return;
        }

        List<Long> directories = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long current = r.u32le(skip + FILE_HEADER_BYTES + MAIN_DIR_CURRENT_OFFSET);
        while (current != 0) {
            long absolute = current + skip;
            if (absolute + MAIN_DIRECTORY_BYTES > r.length) {
                violations.add(STRUCTURE);
                return;
            }
            if (!seen.add(absolute) || directories.size() >= MAX_DIRECTORIES) {
                violations.add(STRUCTURE);
                return;
            }
            directories.add(absolute);
            long prev = r.u32le(absolute + MAIN_DIR_PREV_OFFSET);
            if (prev == current) break;
            current = prev;
        }

        List<Long> chunkStarts = new ArrayList<>();
        for (long dir : directories) {
            long numEntries = r.u32le(dir + MAIN_DIR_NUM_ENTRIES_OFFSET);
            long entry = dir + MAIN_DIRECTORY_BYTES;
            for (long i = 0; i < numEntries; i++) {
                if (entry + SUB_DIRECTORY_ENTRY_BYTES > r.length) {
                    violations.add(STRUCTURE);
                    return;
                }
                long pos = r.u32le(entry);
                long start = r.u32le(entry + 4);
                if (start > pos && start > 0) {
                    if (chunkStarts.size() >= MAX_CHUNKS) {
                        violations.add(STRUCTURE);
                        return;
                    }
                    chunkStarts.add(start);
                }
                entry += SUB_DIRECTORY_ENTRY_BYTES;
            }
        }
        if (chunkStarts.isEmpty()) {
            violations.add(STRUCTURE);
            return;
        }

        byte[] labelBytes = label == null ? new byte[0] : label.trim().getBytes(StandardCharsets.ISO_8859_1);
        for (long start : chunkStarts) {
            long chunk = start + skip;
            if (chunk + CHUNK_HEADER_BYTES > r.length) {
                violations.add(STRUCTURE);
                continue;
            }
            long type = r.u32le(chunk + CHUNK_TYPE_OFFSET);
            if (type == CHUNK_TYPE_IMAGE) {
                counts[1]++;
            } else if (type == CHUNK_TYPE_PATIENT_DATA) {
                counts[0]++;
                long payload = chunk + CHUNK_HEADER_BYTES;
                if (payload + PATIENT_DATA_BYTES > r.length) {
                    violations.add(STRUCTURE);
                    continue;
                }
                byte[] p = r.bytes(payload, PATIENT_DATA_BYTES);
                if (!allZero(p, FIRST_NAME_OFF, FIRST_NAME_LEN)) violations.add(FIRST_NAME);
                if (!zeroOrLabel(p, SURNAME_OFF, SURNAME_LEN, labelBytes)) violations.add(SURNAME);
                if (!allZero(p, TITLE_OFF, TITLE_LEN)) violations.add(TITLE);
                if (!allZero(p, BIRTHDATE_OFF, 4)) violations.add(BIRTHDATE);
                if (p[SEX_OFF] != 0) violations.add(SEX);
                if (!zeroOrLabel(p, PATIENT_ID_OFF, PATIENT_ID_LEN, labelBytes)) violations.add(PATIENT_ID);
                // Bytes the record declares beyond the 127 it is known to
                // have are not something this check can vouch for.
                long declared = r.u32le(chunk + CHUNK_SIZE_OFFSET);
                if (declared > PATIENT_DATA_BYTES) {
                    long extraEnd = Math.min(declared, r.length - payload);
                    if (!r.allZero(payload + PATIENT_DATA_BYTES, extraEnd - PATIENT_DATA_BYTES)) {
                        violations.add(PATIENT_DATA_EXTRA);
                    }
                }
            }
        }
        if (counts[0] == 0 && counts[1] > 0) {
            violations.add(PATIENT_DATA_MISSING);
        }
    }

    static boolean allZero(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    /** All NUL, or exactly {@code label} followed only by NULs. */
    static boolean zeroOrLabel(byte[] b, int off, int len, byte[] label) {
        if (allZero(b, off, len)) return true;
        if (label.length == 0 || label.length > len) return false;
        for (int i = 0; i < label.length; i++) {
            if (b[off + i] != label[i]) return false;
        }
        return allZero(b, off + label.length, len - label.length);
    }

    /** Positional, bounds-checked reads. */
    private static final class Chan {
        private final FileChannel ch;
        final long length;

        Chan(FileChannel ch) throws IOException {
            this.ch = ch;
            this.length = ch.size();
        }

        byte[] bytes(long pos, int n) throws IOException {
            if (pos < 0 || pos + n > length) throw new IOException("read past the end of the file");
            ByteBuffer b = ByteBuffer.allocate(n);
            long at = pos;
            while (b.hasRemaining()) {
                int got = ch.read(b, at);
                if (got <= 0) throw new IOException("read past the end of the file");
                at += got;
            }
            return b.array();
        }

        int u8(long pos) throws IOException {
            return bytes(pos, 1)[0] & 0xFF;
        }

        long u32le(long pos) throws IOException {
            return ByteBuffer.wrap(bytes(pos, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        }

        boolean startsWith(byte[] magic) throws IOException {
            if (length < magic.length) return false;
            byte[] head = bytes(0, magic.length);
            for (int i = 0; i < magic.length; i++) {
                if (head[i] != magic[i]) return false;
            }
            return true;
        }

        boolean allZero(long pos, long n) throws IOException {
            long at = pos;
            long remaining = n;
            while (remaining > 0) {
                int step = (int) Math.min(64 * 1024, remaining);
                byte[] b = bytes(at, step);
                for (byte x : b) {
                    if (x != 0) return false;
                }
                at += step;
                remaining -= step;
            }
            return true;
        }
    }
}
