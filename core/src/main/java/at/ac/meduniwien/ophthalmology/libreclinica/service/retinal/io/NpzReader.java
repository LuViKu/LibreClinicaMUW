/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DR-022 — reads NumPy {@code .npz} archives (a plain Zip of {@code .npy}
 * entries) using {@link NpyReader} for each member.
 *
 * <p>Used to decode {@code fluidseg.npz} returned by the fluid runner;
 * the implementation is dependency-free (stdlib {@link ZipInputStream}).
 */
public final class NpzReader {

    private NpzReader() { }

    public static Map<String, LabelVolume> readAll(Path npz) throws IOException {
        Map<String, LabelVolume> out = new LinkedHashMap<>();
        try (ZipInputStream zin = open(npz)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (!name.endsWith(".npy")) {
                    zin.closeEntry();
                    continue;
                }
                LabelVolume vol = NpyReader.read(new NonClosingInputStream(zin));
                out.put(stripNpy(name), vol);
                zin.closeEntry();
            }
        }
        return out;
    }

    public static LabelVolume read(Path npz, String entryName) throws IOException {
        try (ZipInputStream zin = open(npz)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (name.endsWith(".npy") && stripNpy(name).equals(entryName)) {
                    return NpyReader.read(new NonClosingInputStream(zin));
                }
                zin.closeEntry();
            }
        }
        throw new IOException("entry '" + entryName + "' not found in " + npz);
    }

    public static LabelVolume readFirst(Path npz) throws IOException {
        try (ZipInputStream zin = open(npz)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().endsWith(".npy")) {
                    return NpyReader.read(new NonClosingInputStream(zin));
                }
                zin.closeEntry();
            }
        }
        throw new IOException("no .npy entry in " + npz);
    }

    private static ZipInputStream open(Path npz) throws IOException {
        return new ZipInputStream(new BufferedInputStream(Files.newInputStream(npz)));
    }

    private static String stripNpy(String name) {
        return name.endsWith(".npy") ? name.substring(0, name.length() - 4) : name;
    }

    /**
     * {@link NpyReader#read(InputStream)} wraps the stream in a
     * {@link java.io.DataInputStream}; without this guard a {@code close()}
     * on the wrapper would close the underlying {@link ZipInputStream}
     * and abort iteration.
     */
    private static final class NonClosingInputStream extends InputStream {
        private final InputStream delegate;
        NonClosingInputStream(InputStream delegate) { this.delegate = delegate; }
        @Override public int read() throws IOException { return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }
        @Override public void close() { /* no-op — keep the zip stream alive */ }
    }
}
