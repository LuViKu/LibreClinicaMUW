/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.controller.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal Spectralis .e2e files for the de-identification tests, in the
 * layout {@code generate_fixtures.py} documents: a file header, one main
 * directory, two sub-directory entries and two chunks (a type-9 patient
 * record and one image chunk).
 */
final class E2eTestFiles {

    private E2eTestFiles() {}

    /** A single-volume file whose patient record is {@code patient127}. */
    static byte[] e2e(byte[] patient127) {
        List<byte[]> payloads = List.of(patient127, new byte[40]);
        int[] types = {9, 0x40000000};
        int entriesAt = 36 + 52;
        int chunksAt = entriesAt + 44 * 2;
        int total = chunksAt + payloads.stream().mapToInt(p -> 60 + p.length).sum();
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.put(Arrays.copyOf("CMDb".getBytes(StandardCharsets.ISO_8859_1), 12)).putInt(1).put(new byte[20]);
        out.put(Arrays.copyOf("MDbDir".getBytes(StandardCharsets.ISO_8859_1), 12)).putInt(1).put(new byte[20]);
        out.putInt(2).putInt(36).putInt(0).putInt(0);
        List<Integer> starts = new ArrayList<>();
        int at = chunksAt;
        for (byte[] p : payloads) {
            starts.add(at);
            at += 60 + p.length;
        }
        for (int i = 0; i < 2; i++) {
            out.putInt(36).putInt(starts.get(i)).putInt(payloads.get(i).length).putInt(0);
            out.putInt(1).putInt(1).putInt(1).putInt(0).putShort((short) 0).putShort((short) 0)
               .putInt(types[i]).putInt(0);
        }
        for (int i = 0; i < 2; i++) {
            out.put(Arrays.copyOf("MDbChunk".getBytes(StandardCharsets.ISO_8859_1), 12))
               .putInt(0).putInt(0).putInt(starts.get(i)).putInt(payloads.get(i).length).putInt(0);
            out.putInt(1).putInt(1).putInt(1).putInt(0).putShort((short) 0).putShort((short) 0)
               .putInt(types[i]).putInt(0);
            out.put(payloads.get(i));
        }
        return out.array();
    }

    /** A patient record with only the surname slot (offset 31) filled. */
    static byte[] patient(String surname) {
        byte[] p = new byte[127];
        byte[] s = surname.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(s, 0, p, 31, s.length);
        return p;
    }
}
