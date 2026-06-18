/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * DR-022 — minimal NumPy {@code .npy} v1.0 reader, scoped to the
 * {@code uint8} fluid-segmentation tensors we actually consume.
 *
 * <p>Spec: <a href="https://numpy.org/doc/stable/reference/generated/numpy.lib.format.html">numpy.lib.format</a>.
 * We deliberately support only {@code |u1} / {@code &lt;u1} / {@code u1}
 * because no other dtype reaches the Java side today.
 */
public final class NpyReader {

    private static final byte[] MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};

    private NpyReader() { }

    public static LabelVolume read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in);
        }
    }

    public static LabelVolume read(InputStream in) throws IOException {
        DataInputStream din = new DataInputStream(in);

        byte[] magic = new byte[6];
        din.readFully(magic);
        for (int i = 0; i < 6; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("not a .npy stream (bad magic)");
            }
        }
        int major = din.readUnsignedByte();
        int minor = din.readUnsignedByte();
        if (major != 1 || minor != 0) {
            throw new IOException("unsupported .npy version " + major + "." + minor
                    + "; only 1.0 is supported");
        }
        int low = din.readUnsignedByte();
        int high = din.readUnsignedByte();
        int headerLen = low | (high << 8);
        byte[] hdr = new byte[headerLen];
        din.readFully(hdr);
        String header = new String(hdr, StandardCharsets.US_ASCII);

        String descr = extractString(header, "descr");
        if (descr == null) {
            throw new IOException("missing 'descr' in npy header: " + header);
        }
        if (!(descr.equals("|u1") || descr.equals("<u1") || descr.equals("u1"))) {
            throw new IOException("dtype " + descr
                    + " not supported; only uint8 is needed for fluid labels");
        }

        // Fortran-ordered arrays would need axis-swapping on read; the
        // pipeline never writes them, so any incoming True signals an
        // unexpected producer and we fail loud rather than silently
        // misindex the volume.
        Boolean fortran = extractBool(header, "fortran_order");
        if (fortran == null) {
            throw new IOException("missing 'fortran_order' in npy header: " + header);
        }
        if (fortran) {
            throw new IOException("fortran_order=True is not supported");
        }

        int[] shape = extractShape(header);
        if (shape.length == 0) {
            throw new IOException("zero-dimensional npy arrays are not supported");
        }

        int dimZ;
        int dimY;
        int dimX;
        if (shape.length == 1) {
            dimZ = 1;
            dimY = 1;
            dimX = shape[0];
        } else if (shape.length == 2) {
            dimZ = 1;
            dimY = shape[0];
            dimX = shape[1];
        } else if (shape.length == 3) {
            dimZ = shape[0];
            dimY = shape[1];
            dimX = shape[2];
        } else {
            throw new IOException("shape rank " + shape.length + " not supported");
        }

        long total = 1L;
        for (int d : shape) {
            total *= d;
        }
        if (total > Integer.MAX_VALUE) {
            throw new IOException("array too large: " + total + " bytes");
        }
        byte[] flat = new byte[(int) total];
        din.readFully(flat);
        return new LabelVolume(dimZ, dimY, dimX, flat);
    }

    private static String extractString(String header, String key) {
        int i = header.indexOf("'" + key + "'");
        if (i < 0) {
            return null;
        }
        int colon = header.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int q1 = header.indexOf('\'', colon + 1);
        if (q1 < 0) {
            return null;
        }
        int q2 = header.indexOf('\'', q1 + 1);
        if (q2 < 0) {
            return null;
        }
        return header.substring(q1 + 1, q2);
    }

    private static Boolean extractBool(String header, String key) {
        int i = header.indexOf("'" + key + "'");
        if (i < 0) {
            return null;
        }
        int colon = header.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        String tail = header.substring(colon + 1).stripLeading();
        if (tail.startsWith("True")) {
            return Boolean.TRUE;
        }
        if (tail.startsWith("False")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static int[] extractShape(String header) throws IOException {
        int i = header.indexOf("'shape'");
        if (i < 0) {
            throw new IOException("missing 'shape' in npy header: " + header);
        }
        int open = header.indexOf('(', i);
        int close = header.indexOf(')', open);
        if (open < 0 || close < 0) {
            throw new IOException("malformed 'shape' tuple in npy header: " + header);
        }
        String body = header.substring(open + 1, close).trim();
        if (body.isEmpty()) {
            return new int[0];
        }
        String[] parts = body.split(",");
        List<Integer> dims = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            dims.add(Integer.parseInt(t));
        }
        int[] out = new int[dims.size()];
        for (int j = 0; j < out.length; j++) {
            out[j] = dims.get(j);
        }
        return out;
    }
}
