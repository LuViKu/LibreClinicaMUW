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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The server's own reading of an .e2e's patient records. The files are built
 * byte by byte in the layout {@code generate_fixtures.py} and
 * {@code oct_converter}'s {@code e2e_binary.py} describe, so a change to the
 * walker that the real layout would not survive shows up here.
 */
public class E2eDeidentificationVerifierTest {

    private static final String LABEL = "HAE-001";
    private static final int IMAGE = 0x40000000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** One chunk of the file: its type and payload. */
    private record Chunk(int type, byte[] payload, Integer declaredSize) {
        Chunk(int type, byte[] payload) {
            this(type, payload, null);
        }
    }

    /** The 127-byte type-9 payload, every field settable; the default is fully blank. */
    private static final class Patient {
        private final byte[] b = new byte[127];

        Patient firstName(String s) { return put(0, s); }
        Patient surname(String s) { return put(31, s); }
        Patient title(String s) { return put(82, s); }
        Patient patientId(String s) { return put(102, s); }

        Patient birthdate(int v) {
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(97, v);
            return this;
        }

        Patient sex(char c) {
            b[101] = (byte) c;
            return this;
        }

        private Patient put(int off, String s) {
            byte[] x = s.getBytes(StandardCharsets.ISO_8859_1);
            System.arraycopy(x, 0, b, off, x.length);
            return this;
        }

        byte[] bytes() {
            return b.clone();
        }
    }

    private static byte[] ascii(String s, int len) {
        return Arrays.copyOf(s.getBytes(StandardCharsets.ISO_8859_1), len);
    }

    /** A single-volume file: header, one main directory, entries, then the chunks. */
    private static byte[] build(List<Chunk> chunks, int prefix) {
        int dirAt = prefix + 36;
        int entriesAt = dirAt + 52;
        int chunksAt = entriesAt + 44 * chunks.size();
        ByteBuffer out = ByteBuffer.allocate(chunksAt + chunks.stream().mapToInt(c -> 60 + c.payload().length).sum())
                .order(ByteOrder.LITTLE_ENDIAN);
        if (prefix == 64) out.put(ascii("E2EMultipleVolumeFile", 64));
        out.put(ascii("CMDb", 12)).putInt(1).put(new byte[20]);
        out.put(ascii("MDbDir", 12)).putInt(1).put(new byte[20]);
        out.putInt(chunks.size()).putInt(36).putInt(0).putInt(0);
        int start = chunksAt - prefix;
        List<Integer> starts = new ArrayList<>();
        for (Chunk c : chunks) {
            starts.add(start);
            start += 60 + c.payload().length;
        }
        for (int i = 0; i < chunks.size(); i++) {
            out.putInt(36).putInt(starts.get(i)).putInt(chunks.get(i).payload().length).putInt(0);
            out.putInt(1).putInt(1).putInt(1).putInt(0).putShort((short) 0).putShort((short) 0)
               .putInt(chunks.get(i).type()).putInt(0);
        }
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            out.put(ascii("MDbChunk", 12)).putInt(0).putInt(0).putInt(starts.get(i));
            out.putInt(c.declaredSize() != null ? c.declaredSize() : c.payload().length).putInt(0);
            out.putInt(1).putInt(1).putInt(1).putInt(0).putShort((short) 0).putShort((short) 0)
               .putInt(c.type()).putInt(0);
            out.put(c.payload());
        }
        return out.array();
    }

    private static byte[] image() {
        return new byte[40];
    }

    private Path write(byte[] bytes) throws IOException {
        Path p = tmp.newFile().toPath();
        Files.write(p, bytes);
        return p;
    }

    private List<String> violations(byte[] bytes, String label) throws IOException {
        return E2eDeidentificationVerifier.verify(write(bytes), label).violations();
    }

    private byte[] oneVolume(Patient p) {
        return build(List.of(new Chunk(9, p.bytes()), new Chunk(IMAGE, image())), 0);
    }

    /* ---------------- passes ---------------- */

    @Test
    public void aFullyBlankPatientRecordPasses() throws IOException {
        E2eDeidentificationVerifier.Result r =
                E2eDeidentificationVerifier.verify(write(oneVolume(new Patient())), LABEL);
        assertTrue(r.violations().toString(), r.ok());
        assertEquals(1, r.patientChunks());
        assertEquals(1, r.imageChunks());
    }

    @Test
    public void theLabelInTheSurnameSlotPasses() throws IOException {
        assertEquals(List.of(), violations(oneVolume(new Patient().surname(LABEL)), LABEL));
    }

    @Test
    public void theLabelInThePatientIdSlotPasses() throws IOException {
        assertEquals(List.of(), violations(oneVolume(new Patient().patientId(LABEL)), LABEL));
    }

    @Test
    public void theLabelInBothSlotsPasses() throws IOException {
        assertEquals(List.of(), violations(oneVolume(new Patient().surname(LABEL).patientId(LABEL)), LABEL));
    }

    @Test
    public void aMultiVolumeContainerIsWalkedPastItsPrefix() throws IOException {
        byte[] ok = build(List.of(new Chunk(9, new Patient().surname(LABEL).bytes()), new Chunk(IMAGE, image())), 64);
        assertEquals(List.of(), violations(ok, LABEL));
        byte[] dirty = build(List.of(new Chunk(9, new Patient().firstName("Max").bytes()), new Chunk(IMAGE, image())), 64);
        assertEquals(List.of(E2eDeidentificationVerifier.FIRST_NAME), violations(dirty, LABEL));
    }

    @Test
    public void aFileWithNoImagesAndABlankPatientRecordPasses() throws IOException {
        assertEquals(List.of(), violations(build(List.of(new Chunk(9, new Patient().bytes())), 0), LABEL));
    }

    /* ---------------- each field ---------------- */

    @Test
    public void aFirstNameFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.FIRST_NAME),
                violations(oneVolume(new Patient().firstName("Max")), LABEL));
    }

    @Test
    public void aNameInTheSurnameSlotFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.SURNAME),
                violations(oneVolume(new Patient().surname("Mustermann")), LABEL));
    }

    @Test
    public void theLabelPlusAnythingInTheSurnameSlotFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.SURNAME),
                violations(oneVolume(new Patient().surname(LABEL + " Muster")), LABEL));
        assertEquals(List.of(E2eDeidentificationVerifier.SURNAME),
                violations(oneVolume(new Patient().surname("X" + LABEL)), LABEL));
    }

    @Test
    public void aDifferentSubjectsLabelFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.SURNAME),
                violations(oneVolume(new Patient().surname("HAE-002")), LABEL));
    }

    @Test
    public void aTitleFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.TITLE),
                violations(oneVolume(new Patient().title("Dr.")), LABEL));
    }

    @Test
    public void aBirthdateFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.BIRTHDATE),
                violations(oneVolume(new Patient().birthdate(0x12345678)), LABEL));
    }

    @Test
    public void aSexFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.SEX),
                violations(oneVolume(new Patient().sex('M')), LABEL));
    }

    @Test
    public void aHospitalPatientIdFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.PATIENT_ID),
                violations(oneVolume(new Patient().patientId("0012345678")), LABEL));
    }

    @Test
    public void everyFieldAtOnceReportsEveryField() throws IOException {
        Patient p = new Patient().firstName("Max").surname("Muster").title("Dr").birthdate(1).sex('F')
                .patientId("0012345678");
        assertEquals(List.of(E2eDeidentificationVerifier.FIRST_NAME, E2eDeidentificationVerifier.SURNAME,
                        E2eDeidentificationVerifier.TITLE, E2eDeidentificationVerifier.BIRTHDATE,
                        E2eDeidentificationVerifier.SEX, E2eDeidentificationVerifier.PATIENT_ID),
                violations(oneVolume(p), LABEL));
    }

    @Test
    public void anySlotIsDirtyWhenTheUploadHasNoLabel() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.SURNAME),
                violations(oneVolume(new Patient().surname(LABEL)), null));
        assertEquals(List.of(), violations(oneVolume(new Patient()), null));
    }

    @Test
    public void everyPatientChunkIsChecked() throws IOException {
        byte[] bytes = build(List.of(
                new Chunk(9, new Patient().surname(LABEL).bytes()),
                new Chunk(IMAGE, image()),
                new Chunk(9, new Patient().surname("Mustermann").bytes())), 0);
        assertEquals(List.of(E2eDeidentificationVerifier.SURNAME), violations(bytes, LABEL));
    }

    /* ---------------- fail-closed ---------------- */

    @Test
    public void imagesWithoutAPatientRecordFail() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.PATIENT_DATA_MISSING),
                violations(build(List.of(new Chunk(IMAGE, image())), 0), LABEL));
    }

    @Test
    public void bytesBeyondTheKnownRecordMustBeBlank() throws IOException {
        byte[] extra = new byte[140];
        System.arraycopy(new Patient().bytes(), 0, extra, 0, 127);
        assertEquals(List.of(), violations(build(List.of(new Chunk(9, extra)), 0), LABEL));
        extra[130] = 'x';
        assertEquals(List.of(E2eDeidentificationVerifier.PATIENT_DATA_EXTRA),
                violations(build(List.of(new Chunk(9, extra)), 0), LABEL));
    }

    @Test
    public void aTruncatedPatientRecordFails() throws IOException {
        byte[] whole = oneVolume(new Patient().surname(LABEL));
        // Cut inside the patient chunk's payload.
        int patientChunkAt = 36 + 52 + 44 * 2;
        byte[] cut = Arrays.copyOf(whole, patientChunkAt + 60 + 50);
        assertEquals(List.of(E2eDeidentificationVerifier.STRUCTURE), violations(cut, LABEL));
    }

    @Test
    public void aFileWithNoChunksFails() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.STRUCTURE), violations(build(List.of(), 0), LABEL));
    }

    @Test
    public void garbageAndTinyFilesFail() throws IOException {
        assertEquals(List.of(E2eDeidentificationVerifier.STRUCTURE), violations(new byte[10], LABEL));
        byte[] noise = new byte[400];
        Arrays.fill(noise, (byte) 0xFF);
        assertEquals(List.of(E2eDeidentificationVerifier.STRUCTURE), violations(noise, LABEL));
        byte[] zeros = new byte[400];
        assertEquals(List.of(E2eDeidentificationVerifier.STRUCTURE), violations(zeros, LABEL));
    }

    @Test
    public void aDirectoryPointingOutsideTheFileFails() throws IOException {
        byte[] whole = oneVolume(new Patient());
        // main directory's `current` -> far beyond the end
        ByteBuffer.wrap(whole).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + 40, 0x7fffff00);
        assertEquals(List.of(E2eDeidentificationVerifier.STRUCTURE), violations(whole, LABEL));
    }

    @Test
    public void aChunkStartingOutsideTheFileFails() throws IOException {
        byte[] whole = oneVolume(new Patient());
        // the first sub-directory entry's `start`
        ByteBuffer.wrap(whole).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + 52 + 4, 0x7fffff00);
        assertTrue(violations(whole, LABEL).contains(E2eDeidentificationVerifier.STRUCTURE));
    }

    @Test
    public void aMissingFileFails() {
        assertEquals(List.of(E2eDeidentificationVerifier.STRUCTURE),
                E2eDeidentificationVerifier.verify(tmp.getRoot().toPath().resolve("nope.e2e"), LABEL).violations());
    }

    /* ---------------- hygiene ---------------- */

    @Test
    public void violationsAreFieldNamesAndNeverValues() throws IOException {
        Patient p = new Patient().firstName("Maximilian").surname("Mustermann").patientId("0012345678");
        String blob = violations(oneVolume(p), LABEL).toString();
        for (String secret : List.of("Maximilian", "Mustermann", "0012345678")) {
            assertFalse(blob, blob.contains(secret));
        }
    }

    @Test
    public void theBuilderMatchesTheDocumentedLayout() throws IOException {
        // Guards the helper: a patient chunk's payload is the 127 bytes at
        // 60 past its start, in the order oct_converter declares.
        byte[] f = oneVolume(new Patient().firstName("A").surname("B").title("C").sex('D').patientId("E"));
        int payload = 36 + 52 + 44 * 2 + 60;
        assertEquals('A', f[payload]);
        assertEquals('B', f[payload + 31]);
        assertEquals('C', f[payload + 82]);
        assertEquals('D', f[payload + 101]);
        assertEquals('E', f[payload + 102]);
    }
}
