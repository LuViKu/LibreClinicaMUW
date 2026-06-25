/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

/**
 * DR-022 — flat row-major (C-order) {@code uint8} label volume produced
 * by {@link NpyReader} / {@link NpzReader}.
 *
 * <p>{@code flat} stores unsigned bytes 0..255; consumers should access
 * them via {@link #at(int, int, int)} which applies the {@code & 0xFF}
 * mask, since Java's {@code byte} is signed.
 */
public record LabelVolume(int dimZ, int dimY, int dimX, byte[] flat) {

    public int at(int z, int y, int x) {
        return flat[z * dimY * dimX + y * dimX + x] & 0xFF;
    }
}
